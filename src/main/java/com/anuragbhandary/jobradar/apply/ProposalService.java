package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.knowledge.AnswerPlan;
import com.anuragbhandary.jobradar.knowledge.AnswerRoute;
import com.anuragbhandary.jobradar.knowledge.ApplicationContext;
import com.anuragbhandary.jobradar.knowledge.Assertion;
import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.Concepts;
import com.anuragbhandary.jobradar.knowledge.Evidence;
import com.anuragbhandary.jobradar.knowledge.KnowledgeService;
import com.anuragbhandary.jobradar.knowledge.Scope;
import com.anuragbhandary.jobradar.knowledge.ai.AnswerProposer;
import com.anuragbhandary.jobradar.knowledge.ai.ProposedAnswer;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the open-ended questions a form asked into drafts awaiting approval.
 *
 * <p>The bridge between {@link AnswerProposer}, which knows how to draft and how
 * to check a draft, and {@link ApplicationField}, which is what the preparation
 * screen reads. A draft is persisted as an unapproved
 * {@link com.anuragbhandary.jobradar.knowledge.Assertion} and the field points at
 * it, so approving, editing, regenerating and rejecting all act on a row with a
 * history rather than on a string in a page.
 *
 * <h2>What it will not draft</h2>
 * Three concepts are AI-eligible and no others: why this company, why this role,
 * and the cover letter. Everything else on a form is a fact about the applicant,
 * and a model asked for a notice period will produce a confident, plausible,
 * wrong one. The check is {@link Concept#aiEligible()} and
 * {@link KnowledgeService#propose} refuses anything else outright.
 *
 * <h2>Why there is a cap</h2>
 * Each draft is a model call against a free tier that allows twenty a minute, and
 * a form with eight unrecognised questions would spend the budget on questions
 * nobody can draft anyway. Three is more than any real form has asked for.
 */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);

    /** Model calls per preparation. See the class note. */
    private static final int MAX_DRAFTS = 3;

    private final ApplicationFieldRepository fields;
    private final AnswerProposer proposer;
    private final KnowledgeService knowledge;
    /** Decides which unanswered questions are answerable without interrupting him. */
    private final com.anuragbhandary.jobradar.knowledge.AnswerPlan plan;

    public ProposalService(ApplicationFieldRepository fields, AnswerProposer proposer,
            KnowledgeService knowledge,
            com.anuragbhandary.jobradar.knowledge.AnswerPlan plan) {
        this.fields = fields;
        this.proposer = proposer;
        this.knowledge = knowledge;
        this.plan = plan;
    }

    /**
     * Attaches a proposal to every field of this attempt that could have one.
     *
     * <p>Two kinds of field qualify, and only one of them costs a model call.
     * A field the form filler already wrote prose into - the cover letter - has
     * its text; it needs a row to be approved against, not a second draft. A
     * required question nothing could answer is where a draft is actually worth
     * asking for.
     *
     * @return what the attempt's fields now add up to
     */
    @Transactional
    public AttemptStatus proposeFor(Long attemptId, ApplicationContext context,
            AttemptStatus fallback) {
        List<ApplicationField> rows = fields.findByAttemptIdOrderByIdAsc(attemptId);
        if (rows.isEmpty()) {
            return fallback;
        }
        int drafted = 0;
        for (ApplicationField field : rows) {
            if (field.getPendingAssertionId() != null) {
                continue;
            }
            Optional<Concept> concept = conceptOf(field);
            try {
                // Prose the form filler already wrote - the cover letter. It has
                // its text and needs a row to be approved against, not a draft.
                if (field.getState() == FieldState.AWAITING_APPROVAL
                        && concept.isPresent() && concept.get().aiEligible()
                        && field.getResolvedValue() != null
                        && !field.getResolvedValue().isBlank()) {
                    record(field, concept.get(), context, field.getResolvedValue(),
                            List.of(Evidence.context("written during this preparation",
                                    null)));
                    continue;
                }
                if (field.getState() != FieldState.AWAITING_ANSWER
                        || drafted >= MAX_DRAFTS) {
                    continue;
                }
                // Nothing answered it. Ask what kind of question it is before
                // deciding it needs him: a technology question is answerable from
                // evidence he already has, and used to be the largest source of
                // interruptions on a form.
                if (draftUnanswered(field, concept.orElse(Concept.UNRECOGNISED), context)) {
                    drafted++;
                }
            } catch (RuntimeException e) {
                // A draft that could not be made is a field still awaiting an
                // answer, which is exactly what it already said. Nothing about a
                // filled form is lost by this failing.
                log.debug("No proposal for '{}': {}", field.getRawLabel(), e.getMessage());
            }
        }
        return FieldRecorder.statusFor(fields.findByAttemptIdOrderByIdAsc(attemptId));
    }

    /**
     * Asks for a new draft of a field that already has one.
     *
     * <p>The new one is made <em>before</em> the old one is retired, so a model
     * that is unavailable or that declines leaves the applicant with the draft he
     * already had rather than with nothing. The old proposal is rejected rather
     * than deleted: what a previous version said is part of the record.
     *
     * @return the new assertion, or empty when nothing could be drafted
     */
    @Transactional
    public Optional<Assertion> regenerate(ApplicationField field, ApplicationContext context) {
        Concept concept = conceptOf(field).orElseThrow(() -> new IllegalArgumentException(
                "This question has not been classified, so there is nothing to draft from."));
        if (!concept.aiEligible()) {
            throw new IllegalArgumentException(
                    "'" + concept.label() + "' is a fact about you, not something to draft.");
        }
        if (!proposer.isUsable()) {
            throw new IllegalStateException(
                    "No language model is configured, so nothing can be drafted.");
        }

        ProposedAnswer proposal = proposer.propose(concept, context, field.getRawLabel())
                .orElseThrow(() -> new IllegalStateException(
                        "The model did not answer. Try again in a moment."));
        if (!proposal.isUsable()) {
            throw new IllegalStateException(rejection(proposal));
        }

        Long previous = field.getPendingAssertionId();
        Assertion stored = record(field, concept, context, proposal.answer(),
                proposer.evidenceFor(proposal));
        if (previous != null && !previous.equals(stored.getId())) {
            knowledge.reject(previous, "replaced by a new draft");
        }
        return Optional.of(stored);
    }

    // ------------------------------------------------------------------

    /**
     * Tries to answer a question nothing else could, without asking him.
     *
     * <p>Two routes reach a draft. A technology question is positioned first -
     * from his own resume, in Java - and the model is then asked only to phrase a
     * conclusion that was already decided. An open-ended question goes to the
     * existing drafting path unchanged.
     *
     * <p>Everything else is left exactly as it was: awaiting his answer. That is
     * the honest outcome for a missing fact, and the category on the concept is
     * what keeps a question about a work permit out of here.
     *
     * @return true when a model was actually called, so the cap counts calls
     *         rather than fields considered
     */
    private boolean draftUnanswered(ApplicationField field, Concept concept,
            ApplicationContext context) {

        AnswerPlan.Plan route = plan.planFor(concept, field.getRawLabel(), context,
                field.isRequired());
        if (route.route() != AnswerRoute.AI_PROPOSE) {
            return false;
        }
        if (!proposer.isUsable()) {
            // Worth recording even with no model configured: it turns "your
            // input required" into "your input required, and here is what you
            // have that is relevant", which is most of the value.
            if (route.isPositioned()) {
                field.setExplanation(route.because());
                fields.save(field);
            }
            return false;
        }

        if (route.isPositioned()) {
            Optional<ProposedAnswer> proposal = proposer.positioned(
                    positioningConcept(concept), route.positioning(), context,
                    field.getRawLabel());
            if (proposal.isEmpty()) {
                return false;
            }
            if (!proposal.get().isUsable()) {
                field.setExplanation(rejection(proposal.get()));
                fields.save(field);
                return true;
            }
            record(field, positioningConcept(concept), context, proposal.get().answer(),
                    evidenceOf(route.positioning()));
            // The positioning is not stored. He does not gain Kubernetes
            // experience by answering a question about it, and the next form is
            // positioned from the same resume rather than from this prose.
            field.setExplanation(route.because());
            fields.save(field);
            return true;
        }
        if (concept.aiEligible()) {
            draft(field, concept, context);
            return true;
        }
        return false;
    }

    /**
     * Which concept a positioned answer is stored against.
     *
     * <p>Technology questions arrive unclassified and there is no concept per
     * technology, so the draft is stored against the generic experience concept
     * and scoped to the application. The alternative - inventing a concept per
     * technology at runtime - would let a model create knowledge keys, which is
     * the one thing the concept registry exists to prevent.
     */
    private static Concept positioningConcept(Concept concept) {
        return concept.allowsExperiencePositioning() ? concept
                : Concepts.TECHNOLOGY_EXPERIENCE;
    }

    /** The resume lines the positioning was built from, as citations. */
    private static List<Evidence> evidenceOf(
            com.anuragbhandary.jobradar.knowledge.experience.Positioning positioning) {
        List<Evidence> evidence = new java.util.ArrayList<>();
        positioning.evidence().forEach(entry -> entry.evidence().stream().limit(2)
                .forEach(item -> evidence.add(
                        Evidence.resume(item.display(), item.describe()))));
        if (evidence.isEmpty()) {
            evidence.add(Evidence.context("no direct or related experience on record",
                    positioning.subject()));
        }
        return List.copyOf(evidence);
    }

    private void draft(ApplicationField field, Concept concept, ApplicationContext context) {
        if (!proposer.isUsable()) {
            return;
        }
        Optional<ProposedAnswer> proposal = proposer.propose(concept, context,
                field.getRawLabel());
        if (proposal.isEmpty()) {
            return;
        }
        if (!proposal.get().isUsable()) {
            // A rejected draft is shown as the reason it was rejected, never as an
            // answer. The field stays awaiting an answer, which is the truth.
            field.setExplanation(rejection(proposal.get()));
            fields.save(field);
            return;
        }
        record(field, concept, context, proposal.get().answer(),
                proposer.evidenceFor(proposal.get()));
    }

    /** Stores the draft and points the field at it. Unapproved, always. */
    private Assertion record(ApplicationField field, Concept concept,
            ApplicationContext context, String value, List<Evidence> evidence) {

        Scope scope = knowledge.defaultScopeFor(concept, context);
        Assertion stored = knowledge.propose(concept, value, scope, evidence,
                field.getRawLabel());
        field.setPendingAssertionId(stored.getId());
        field.setResolvedValue(value);
        field.setState(FieldState.AWAITING_APPROVAL);
        field.setSource(com.anuragbhandary.jobradar.knowledge.KnowledgeSource.AI_PROPOSED);
        field.setConfidence(com.anuragbhandary.jobradar.knowledge.Confidence.MEDIUM);
        field.setEvidenceList(evidence);
        field.setExplanation("drafted for you to read - nothing written by a model is sent "
                + "without your approval");
        fields.save(field);
        return stored;
    }

    private static String rejection(ProposedAnswer proposal) {
        return proposal.rejectionReason() == null
                ? "The material does not support an answer to this one."
                : "The draft was refused: " + proposal.rejectionReason();
    }

    private static Optional<Concept> conceptOf(ApplicationField field) {
        return field.getConceptId() == null ? Optional.empty()
                : Concepts.byId(field.getConceptId());
    }
}
