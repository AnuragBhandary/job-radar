package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.evidence.ApplicationEvidenceContext;
import com.anuragbhandary.jobradar.evidence.EvidenceIntegrityException;
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
import java.util.ArrayList;
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
 * <h2>Evidence and provenance</h2>
 * Career answers are grounded on the application's evidence context - the same
 * selection the resume was printed from - and every stored draft cites the
 * evidence-bank ids it rests on, so "what was this answer based on?" resolves to
 * items, not to copied prose.
 *
 * <h2>What it will not draft</h2>
 * Why this company, why this role, the cover letter, and technology-experience
 * questions - nothing else. Everything else on a form is a fact about the applicant,
 * and a model asked for a notice period will produce a confident, plausible, wrong
 * one. The check is {@link Concept#aiEligible()} and the concept's category, and
 * {@link KnowledgeService#propose} refuses anything else outright.
 *
 * <h2>Why there is a cap</h2>
 * Each draft is a model call against a free tier that allows twenty a minute.
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
    private final AnswerPlan plan;
    private final ApplicationEvidence evidence;

    public ProposalService(ApplicationFieldRepository fields, AnswerProposer proposer,
            KnowledgeService knowledge, AnswerPlan plan, ApplicationEvidence evidence) {
        this.fields = fields;
        this.proposer = proposer;
        this.knowledge = knowledge;
        this.plan = plan;
        this.evidence = evidence;
    }

    @Transactional
    public AttemptStatus proposeFor(Long attemptId, ApplicationContext context,
            AttemptStatus fallback) {
        return proposeFor(attemptId, context, null, fallback);
    }

    /**
     * Attaches a proposal to every field of this attempt that could have one.
     *
     * @param evidenceContext the evidence this application was prepared from, or null
     *                        to rebuild it from the attempt's rendered resume
     * @return what the attempt's fields now add up to
     */
    @Transactional
    public AttemptStatus proposeFor(Long attemptId, ApplicationContext context,
            ApplicationEvidenceContext evidenceContext, AttemptStatus fallback) {
        List<ApplicationField> rows = fields.findByAttemptIdOrderByIdAsc(attemptId);
        if (rows.isEmpty()) {
            return fallback;
        }
        ApplicationEvidenceContext grounding = evidenceContext != null
                ? evidenceContext : evidenceOf(attemptId).orElse(null);
        int drafted = 0;
        for (ApplicationField field : rows) {
            if (field.getPendingAssertionId() != null) {
                continue;
            }
            Optional<Concept> concept = conceptOf(field);
            try {
                // Prose the form filler already wrote - the cover letter. It has
                // its text and needs a row to be approved against, not a draft; its
                // citations are the evidence the letter was grounded on.
                if (field.getState() == FieldState.AWAITING_APPROVAL
                        && concept.isPresent() && concept.get().aiEligible()
                        && field.getResolvedValue() != null
                        && !field.getResolvedValue().isBlank()) {
                    List<Evidence> cited = new ArrayList<>(evidence.letterEvidence(attemptId));
                    cited.add(Evidence.context("written during this preparation", null));
                    record(field, concept.get(), context, field.getResolvedValue(), cited);
                    continue;
                }
                if (field.getState() != FieldState.AWAITING_ANSWER
                        || drafted >= MAX_DRAFTS) {
                    continue;
                }
                if (draftUnanswered(field, concept.orElse(Concept.UNRECOGNISED), context, grounding)) {
                    drafted++;
                }
            } catch (RuntimeException e) {
                // A draft that could not be made is a field still awaiting an
                // answer, which is exactly what it already said.
                log.debug("No proposal for '{}': {}", field.getRawLabel(), e.getMessage());
            }
        }
        return FieldRecorder.statusFor(fields.findByAttemptIdOrderByIdAsc(attemptId));
    }

    /**
     * Asks for a new draft of a field that already has one.
     *
     * <p>The new one is made <em>before</em> the old one is retired, so a model that
     * is unavailable or that declines leaves the applicant with the draft he already
     * had. The old proposal is rejected rather than deleted.
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
        ApplicationEvidenceContext grounding;
        try {
            grounding = evidence.forAttempt(field.getAttemptId()).orElse(null);
        } catch (EvidenceIntegrityException e) {
            throw new IllegalStateException(e.getMessage());
        }

        ProposedAnswer proposal = proposer.propose(concept, context, field.getRawLabel(), grounding)
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

    private Optional<ApplicationEvidenceContext> evidenceOf(Long attemptId) {
        try {
            return evidence.forAttempt(attemptId);
        } catch (RuntimeException e) {
            // Unavailable evidence means no grounded draft; the proposer refuses on
            // its own when the bank cannot be used, and the field says why.
            log.debug("No evidence context for attempt {}: {}", attemptId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Tries to answer a question nothing else could, without asking him.
     *
     * <p>A technology question is positioned first - in Java, from his evidence - and
     * the model is then asked only to phrase a conclusion that was already decided,
     * from the bank items behind it. An open-ended question goes to the drafting path
     * with the application's evidence. Everything else stays awaiting his answer.
     *
     * @return true when a model was actually called
     */
    private boolean draftUnanswered(ApplicationField field, Concept concept,
            ApplicationContext context, ApplicationEvidenceContext grounding) {

        AnswerPlan.Plan route = plan.planFor(concept, field.getRawLabel(), context,
                field.isRequired());
        if (route.route() != AnswerRoute.AI_PROPOSE) {
            return false;
        }
        if (!proposer.isUsable()) {
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
            List<Evidence> cited = new ArrayList<>(proposer.evidenceFor(proposal.get()));
            cited.add(Evidence.context("positioning", route.positioning().describe()));
            record(field, positioningConcept(concept), context, proposal.get().answer(), cited);
            // The positioning is not stored. He does not gain Kubernetes
            // experience by answering a question about it.
            field.setExplanation(route.because());
            fields.save(field);
            return true;
        }
        if (concept.aiEligible()) {
            draft(field, concept, context, grounding);
            return true;
        }
        return false;
    }

    /**
     * Which concept a positioned answer is stored against: the generic experience
     * concept, scoped to the application. Inventing a concept per technology at
     * runtime would let a model create knowledge keys.
     */
    private static Concept positioningConcept(Concept concept) {
        return concept.allowsExperiencePositioning() ? concept
                : Concepts.TECHNOLOGY_EXPERIENCE;
    }

    private void draft(ApplicationField field, Concept concept, ApplicationContext context,
            ApplicationEvidenceContext grounding) {
        if (!proposer.isUsable()) {
            return;
        }
        Optional<ProposedAnswer> proposal = proposer.propose(concept, context,
                field.getRawLabel(), grounding);
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
            ApplicationContext context, String value, List<Evidence> evidenceList) {

        Scope scope = knowledge.defaultScopeFor(concept, context);
        Assertion stored = knowledge.propose(concept, value, scope, evidenceList,
                field.getRawLabel());
        field.setPendingAssertionId(stored.getId());
        field.setResolvedValue(value);
        field.setState(FieldState.AWAITING_APPROVAL);
        field.setSource(com.anuragbhandary.jobradar.knowledge.KnowledgeSource.AI_PROPOSED);
        field.setConfidence(com.anuragbhandary.jobradar.knowledge.Confidence.MEDIUM);
        field.setEvidenceList(evidenceList);
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
