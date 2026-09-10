package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.knowledge.ApplicationContext;
import com.anuragbhandary.jobradar.knowledge.ApplicationContextFactory;
import com.anuragbhandary.jobradar.knowledge.Assertion;
import com.anuragbhandary.jobradar.knowledge.AssertionRepository;
import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.Concepts;
import com.anuragbhandary.jobradar.knowledge.Confidence;
import com.anuragbhandary.jobradar.knowledge.Evidence;
import com.anuragbhandary.jobradar.knowledge.KnowledgeResolver;
import com.anuragbhandary.jobradar.knowledge.KnowledgeService;
import com.anuragbhandary.jobradar.knowledge.KnowledgeSource;
import com.anuragbhandary.jobradar.knowledge.Resolution;
import com.anuragbhandary.jobradar.knowledge.Scope;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the applicant can do to one field, and everything he cannot.
 *
 * <p>All of it here rather than in a controller, so the rules are testable
 * without HTTP and so there is one implementation behind both the JSON endpoints
 * and the plain-form fallback. A validation that lives in a controller is a
 * validation that exists once per client.
 *
 * <h2>The four refusals that matter</h2>
 * <ol>
 *   <li><b>Stale.</b> Every action carries the {@link ApplicationField#getVersion()
 *       version} it was rendered from. A draft regenerated in another tab must not
 *       be approved by a button that still points at the old one - the prose that
 *       would reach an employer is then prose nobody read.</li>
 *   <li><b>Wrong proposal.</b> Approving also names the assertion. Version alone
 *       would not catch a regeneration that happened to leave the row otherwise
 *       untouched.</li>
 *   <li><b>Wrong state.</b> A rejected draft cannot be approved, and an approval
 *       cannot be applied to a field that has nothing awaiting one.</li>
 *   <li><b>Unsafe scope.</b> Delegated to {@link KnowledgeService}, which throws.
 *       The server is the boundary; a client sending GLOBAL for sponsorship is
 *       refused whatever the form offered.</li>
 * </ol>
 *
 * <h2>Where an answer goes</h2>
 * Three places, and the third is conditional:
 * <ul>
 *   <li>the field row, so this application uses it;</li>
 *   <li>an {@link Assertion} at the scope he chose, so the knowledge system knows
 *       it - scoped, provenanced, and supersedable;</li>
 *   <li>{@link AnswerStore}, <b>only at global scope</b>. That store is the flat
 *       {@code extra-answers} list the old system had, it applies everywhere by
 *       construction, and it is still what fills a real form. Writing a
 *       country-scoped answer into it would recreate exactly the generalisation
 *       the knowledge system was built to stop, so a narrower scope deliberately
 *       does not reach it and waits for the resolver's own gate.</li>
 * </ul>
 */
@Service
public class FieldActions {

    private static final Logger log = LoggerFactory.getLogger(FieldActions.class);

    private static final String VIA = "preparation screen";

    private final ApplicationFieldRepository fields;
    private final ApplicationAttemptRepository attempts;
    private final PostingRepository postings;
    private final AssertionRepository assertions;
    private final KnowledgeService knowledge;
    private final KnowledgeResolver resolver;
    private final ApplicationContextFactory contexts;
    private final ProposalService proposals;
    private final AnswerStore answerStore;

    public FieldActions(ApplicationFieldRepository fields,
            ApplicationAttemptRepository attempts, PostingRepository postings,
            AssertionRepository assertions, KnowledgeService knowledge,
            KnowledgeResolver resolver, ApplicationContextFactory contexts,
            ProposalService proposals, AnswerStore answerStore) {
        this.fields = fields;
        this.attempts = attempts;
        this.postings = postings;
        this.assertions = assertions;
        this.knowledge = knowledge;
        this.resolver = resolver;
        this.contexts = contexts;
        this.proposals = proposals;
        this.answerStore = answerStore;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    public ApplicationField field(Long fieldId) {
        return fields.findById(fieldId).orElseThrow(
                () -> ActionRefused.notFound("There is no field " + fieldId + "."));
    }

    public List<ApplicationField> forAttempt(Long attemptId) {
        return fields.findByAttemptIdOrderByIdAsc(attemptId);
    }

    /**
     * The scopes this answer may legitimately be saved at, safest first.
     *
     * <p>Computed here and never in a page. The rule that a context-sensitive
     * concept has no global option is a property of the concept, and a client that
     * decided for itself would be one release away from offering the option the
     * server then refuses - an interface that has lied.
     */
    public List<ScopeOption> scopesFor(Long fieldId) {
        ApplicationField field = field(fieldId);
        Optional<Concept> concept = conceptOf(field);
        if (concept.isEmpty()) {
            // Unclassified. It can still be answered, for this application, and
            // it cannot be generalised because there is nothing to generalise to.
            return List.of();
        }
        ApplicationContext context = contextFor(field);
        Scope preferred = knowledge.defaultScopeFor(concept.get(), context);
        List<ScopeOption> options = new ArrayList<>();
        for (Scope scope : knowledge.offeredScopes(concept.get(), context)) {
            // APPLICATION is what answering already does - the value is on the
            // field row and reaches the form on the next run. Offering it beside
            // "just this once" is two options for one outcome, and a picker with
            // a redundant option is a picker that gets read less carefully.
            if (scope.level() == Scope.Level.APPLICATION) {
                continue;
            }
            options.add(ScopeOption.of(scope, scope.equals(preferred)));
        }
        return List.copyOf(options);
    }

    /**
     * Why this field says what it says.
     *
     * <p>Includes what the new resolver would answer, marked as a second opinion.
     * It is not what filled the form and this phase does not make it so; showing
     * it is how the gap between the two stays visible while the shadow evidence
     * accumulates.
     */
    public FieldExplanation explain(Long fieldId) {
        ApplicationField field = field(fieldId);
        Optional<Concept> concept = conceptOf(field);
        ApplicationContext context = contextFor(field);

        List<String> evidence = field.evidenceList().stream().map(Evidence::describe).toList();
        List<String> alsoApplied = new ArrayList<>();
        List<String> conflict = new ArrayList<>();
        String secondOpinion = null;

        if (concept.isPresent()) {
            Resolution resolution = resolver.resolve(concept.get(), context);
            secondOpinion = resolution.state() + ": "
                    + (resolution.hasValue() ? resolution.value() : "no answer")
                    + (resolution.explanation() == null ? ""
                            : " - " + resolution.explanation());
            if (resolution.state() == Resolution.State.CONFLICT) {
                resolution.competing().forEach(a -> conflict.add(describe(a)));
            } else {
                resolution.competing().forEach(a -> alsoApplied.add(describe(a)));
            }
        }

        return new FieldExplanation(
                field.getRawLabel(),
                field.getConceptId(),
                concept.map(Concept::label).orElse("Not classified"),
                concept.map(Concept::contextSensitive).orElse(false),
                field.getResolvedValue(),
                field.getSource() == null ? null : field.getSource().name(),
                sourceLabel(field.getSource()),
                field.getConfidence() == null ? null : field.getConfidence().name(),
                field.getExplanation(),
                context == null ? null : context.describe(),
                evidence,
                List.copyOf(alsoApplied),
                List.copyOf(conflict),
                field.getAutomationState().name(),
                field.getFailureReason(),
                field.isUserEdited(),
                field.getOriginalValue(),
                field.getOriginalSource() == null ? null : field.getOriginalSource().name(),
                secondOpinion);
    }

    /**
     * The fields of one attempt whose question has two stored answers that
     * disagree.
     *
     * <p>The whole attempt at once, and the context built once: resolving is a
     * query per concept, and asking field by field turns a page render into forty
     * round trips answering the same question.
     */
    public java.util.Set<Long> conflictedFields(Long attemptId) {
        List<ApplicationField> rows = forAttempt(attemptId);
        if (rows.isEmpty()) {
            return java.util.Set.of();
        }
        ApplicationContext context = contextFor(rows.getFirst());
        java.util.Set<Long> conflicted = new java.util.LinkedHashSet<>();
        for (ApplicationField field : rows) {
            Optional<Concept> concept = conceptOf(field);
            if (concept.isPresent()
                    && resolver.resolve(concept.get(), context).state()
                            == Resolution.State.CONFLICT) {
                conflicted.add(field.getId());
            }
        }
        return java.util.Set.copyOf(conflicted);
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Replaces a settled value with one the applicant typed.
     *
     * <p>Scoped to this application and nowhere else. Overriding is a correction
     * to what is about to be sent, not a statement about him - if it is also a
     * statement about him, {@link #answer} with a scope is the way to say so.
     */
    @Transactional
    public ApplicationField override(Long fieldId, long version, String value) {
        ApplicationField field = checked(fieldId, version);
        String cleaned = require(value);
        if (field.getState() == FieldState.AWAITING_APPROVAL) {
            throw ActionRefused.wrongState(
                    "This field has a draft waiting on you; approve or reject it instead of "
                            + "editing around it.");
        }
        field.overriddenBy(cleaned, KnowledgeSource.USER_INPUT);
        field.setConfidence(Confidence.HIGH);
        field.setExplanation("you changed this during preparation");
        conceptOf(field).ifPresent(concept ->
                knowledge.answerNow(contextFor(field), concept, cleaned));
        return save(field);
    }

    /**
     * Answers a question nothing could answer, and optionally remembers it.
     *
     * <p>The valuable action in the whole screen. Answering unblocks this
     * application; choosing a scope means the next form asking the same thing does
     * not have to ask again - and choosing it explicitly is what stops the answer
     * reaching a form where it is wrong.
     *
     * @param scopeLevel null to answer here only. Anything else is validated
     *                   against the concept by {@link KnowledgeService}, which
     *                   throws rather than narrowing quietly.
     */
    @Transactional
    public ApplicationField answer(Long fieldId, long version, String value,
            String scopeLevel, String scopeValue) {

        ApplicationField field = checked(fieldId, version);
        String cleaned = require(value);
        if (field.getState() == FieldState.AWAITING_APPROVAL) {
            throw ActionRefused.wrongState(
                    "This field has a draft waiting on you. Approve, edit or reject it.");
        }
        Optional<Concept> concept = conceptOf(field);
        ApplicationContext context = contextFor(field);

        field.overriddenBy(cleaned, KnowledgeSource.USER_INPUT);
        field.setConfidence(Confidence.HIGH);
        field.setExplanation("you answered this during preparation");

        if (concept.isPresent()) {
            knowledge.answerNow(context, concept.get(), cleaned);
            if (scopeLevel != null && !scopeLevel.isBlank()) {
                learn(concept.get(), context, field, cleaned, scopeLevel, scopeValue);
            }
        }
        return save(field);
    }

    /**
     * Accepts a draft, optionally rewritten first.
     *
     * <p>The source stays {@code AI_PROPOSED} on both the assertion and the field.
     * Approving records that he read it, not that he wrote it, and losing that
     * distinction is how an approved draft became indistinguishable from a typed
     * fact in the version before this one.
     */
    @Transactional
    public ApplicationField approve(Long fieldId, long version, Long assertionId,
            String editedValue) {

        ApplicationField field = checked(fieldId, version);
        Assertion proposal = matchingProposal(field, assertionId);
        if (field.getState() != FieldState.AWAITING_APPROVAL) {
            throw ActionRefused.wrongState(
                    "This field is not waiting on an approval.");
        }
        Assertion approved = knowledge.approve(proposal.getId(), editedValue, VIA)
                .orElseThrow(() -> ActionRefused.notFound("That draft no longer exists."));

        field.setResolvedValue(approved.getValue());
        field.setState(FieldState.RESOLVED);
        field.setConfidence(Confidence.HIGH);
        field.setUserEdited(approved.isUserEdited());
        field.setExplanation(approved.isUserEdited()
                ? "you edited this draft and approved it"
                : "you read this draft and approved it");
        return save(field);
    }

    /**
     * Discards a draft and puts the field back where it was.
     *
     * <p>Back to awaiting an answer when the form requires one, and to skipped
     * when it does not - not to "resolved with nothing", which is how a required
     * question ends up submitted blank.
     */
    @Transactional
    public ApplicationField reject(Long fieldId, long version, Long assertionId) {
        ApplicationField field = checked(fieldId, version);
        Assertion proposal = matchingProposal(field, assertionId);
        knowledge.reject(proposal.getId(), VIA);

        field.setPendingAssertionId(null);
        field.setResolvedValue(null);
        field.setSource(null);
        field.setConfidence(null);
        field.setState(field.isRequired()
                ? FieldState.AWAITING_ANSWER : FieldState.SKIPPED_OPTIONAL);
        field.setAutomationState(field.isRequired()
                ? AutomationState.NOT_ATTEMPTED : AutomationState.NOT_APPLICABLE);
        field.setExplanation("you rejected the draft - answer it yourself, or leave it");
        return save(field);
    }

    /**
     * Asks for a different draft.
     *
     * <p>The new one is made before the old one is retired, so a model that is
     * unavailable leaves the applicant with the draft he had. See
     * {@link ProposalService#regenerate}.
     */
    @Transactional
    public ApplicationField regenerate(Long fieldId, long version, Long assertionId) {
        ApplicationField field = checked(fieldId, version);
        matchingProposal(field, assertionId);
        try {
            proposals.regenerate(field, contextFor(field));
        } catch (IllegalStateException e) {
            throw ActionRefused.unavailable(e.getMessage(),
                    "The draft you already have is untouched.");
        } catch (IllegalArgumentException e) {
            throw ActionRefused.invalid(e.getMessage(), "Answer it yourself instead.");
        }
        return save(field(fieldId));
    }

    // ------------------------------------------------------------------

    /**
     * Stores the answer at the scope he chose, and only then anywhere flat.
     *
     * <p>The global case is the one bridge into the old {@code extra-answers}
     * store, and it is safe precisely because global is what that store already
     * means. Everything narrower stays in the knowledge system, where the scope
     * is enforced.
     */
    private void learn(Concept concept, ApplicationContext context, ApplicationField field,
            String value, String scopeLevel, String scopeValue) {
        Scope.Level level;
        try {
            level = Scope.Level.valueOf(scopeLevel.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ActionRefused.invalid("'" + scopeLevel + "' is not a scope.",
                    "Choose one of the offered options.");
        }
        Scope scope = level == Scope.Level.GLOBAL ? Scope.global()
                : Scope.of(level, scopeValue == null || scopeValue.isBlank()
                        ? valueFor(level, context) : scopeValue);
        if (level != Scope.Level.GLOBAL && (scope.value() == null || scope.value().isBlank())) {
            throw ActionRefused.invalid(
                    "This application does not say what its " + readable(level) + " is.",
                    "Save it for this application only.");
        }
        try {
            knowledge.learn(context, concept, value, scope, KnowledgeSource.USER_INPUT,
                    field.getRawLabel(), VIA);
        } catch (IllegalArgumentException e) {
            // The write path refused the scope. Surfaced rather than narrowed:
            // narrowing quietly would hide the bug that asked for it.
            throw ActionRefused.unsafe(e.getMessage());
        }
        if (level == Scope.Level.GLOBAL) {
            try {
                answerStore.remember(field.getRawLabel(), value);
            } catch (RuntimeException e) {
                log.warn("Stored the assertion but not the flat answer: {}", e.getMessage());
            }
        }
    }

    private static String valueFor(Scope.Level level, ApplicationContext context) {
        if (context == null) {
            return null;
        }
        return switch (level) {
            case APPLICATION -> context.postingId() == null ? null
                    : context.postingId().toString();
            case COMPANY -> context.company();
            case COUNTRY -> context.employmentCountryCode();
            case WORK_MODE -> context.workMode() == null ? null : context.workMode().name();
            case STRATEGIC_CLASS -> context.strategicClass() == null ? null
                    : context.strategicClass().name();
            case GLOBAL -> null;
        };
    }

    /** Loads the field and refuses if the page it came from is out of date. */
    private ApplicationField checked(Long fieldId, long version) {
        ApplicationField field = field(fieldId);
        if (field.getVersion() != version) {
            throw ActionRefused.stale(
                    "This field changed after the page was loaded, so that action would "
                            + "have applied to something you have not seen.");
        }
        return field;
    }

    /**
     * The proposal this action names, or a refusal saying it is not the current one.
     *
     * <p>Both halves matter. A draft that was regenerated has a different
     * assertion and the same field, and approving the old id would approve prose
     * that is no longer on screen.
     */
    private Assertion matchingProposal(ApplicationField field, Long assertionId) {
        Long pending = field.getPendingAssertionId();
        if (pending == null) {
            throw ActionRefused.wrongState("This field has no draft waiting on you.");
        }
        if (assertionId != null && !assertionId.equals(pending)) {
            throw ActionRefused.stale("That draft has been replaced by a newer one.");
        }
        return assertions.findById(pending).orElseThrow(
                () -> ActionRefused.notFound("That draft no longer exists."));
    }

    /** Saves the field and re-states what the attempt as a whole now says. */
    private ApplicationField save(ApplicationField field) {
        ApplicationField saved = fields.save(field);
        attempts.findById(saved.getAttemptId()).ifPresent(attempt -> {
            if (attempt.getStatus() == AttemptStatus.SUBMITTED) {
                return;
            }
            AttemptStatus status = FieldRecorder.statusFor(
                    fields.findByAttemptIdOrderByIdAsc(saved.getAttemptId()));
            if (status != attempt.getStatus()) {
                attempt.setStatus(status);
                attempts.save(attempt);
            }
        });
        return saved;
    }

    private Optional<Concept> conceptOf(ApplicationField field) {
        return field.getConceptId() == null ? Optional.empty()
                : Concepts.byId(field.getConceptId());
    }

    /**
     * The context this field's answer belongs to.
     *
     * <p>Rebuilt from the posting rather than stored on the field, so a posting
     * that has been re-screened - given a work mode, an employment country - is
     * read as it is now rather than as it was when the form was filled.
     */
    ApplicationContext contextFor(ApplicationField field) {
        Optional<ApplicationAttempt> attempt = attempts.findById(field.getAttemptId());
        if (attempt.isEmpty()) {
            return contexts.empty();
        }
        Optional<Posting> posting = postings.findById(attempt.get().getPostingId());
        return posting.map(found -> contexts.of(found, attempt.get().getCompany(),
                        attempt.get().getResumePath(), attempt.get().getCoverLetter()))
                .orElseGet(contexts::empty);
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw ActionRefused.invalid("An answer is needed.",
                    "A blank one would fill the field with nothing, which is worse than "
                            + "leaving it blocked.");
        }
        return value.trim();
    }

    /** The same words the field list uses, so one field cannot be described twice. */
    private static String sourceLabel(KnowledgeSource source) {
        if (source == null) {
            return "no answer";
        }
        return switch (source) {
            case SESSION -> "answered during this preparation";
            case USER_INPUT -> "you answered this";
            case USER_RULE -> "a rule you approved";
            case PROFILE -> "your profile";
            case RESUME -> "your resume";
            case DERIVED -> "worked out from this application";
            case HISTORICAL -> "an earlier application";
            case AI_PROPOSED -> "drafted by the assistant";
        };
    }

    private static String describe(Assertion assertion) {
        return "'" + assertion.getValue() + "' — " + assertion.scope().describe()
                + ", " + assertion.getSource();
    }

    private static String readable(Scope.Level level) {
        return level.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
