package com.anuragbhandary.jobradar.knowledge;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one way knowledge is written.
 *
 * <p>Before this there were three, and they disagreed. {@code AnswerStore} kept
 * an answer in memory and spliced it into {@code applicant.yml};
 * {@code AssistantController.saveAnswer} spliced a differently-shortened key into
 * the same file with its own backup handling; {@code learn --write} appended a
 * third variant from the CLI. Three writers, two key-shortening implementations,
 * one hand-maintained file, and no record anywhere of where an answer came from.
 *
 * <p>Everything now goes through here, and every write records a source, a scope
 * and an approval state.
 *
 * <h2>Scope is never widened for him</h2>
 * {@link #remember} refuses a scope the concept does not allow, so there is no
 * code path - not a convenience default, not a bulk import - that can turn a
 * German sponsorship answer into a global rule. Answering the same question for
 * two countries produces two country-scoped assertions and never a third global
 * one; broadening a rule is a thing he does deliberately, on purpose, once.
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final AssertionRepository assertions;
    private final SessionAnswers session;

    public KnowledgeService(AssertionRepository assertions, SessionAnswers session) {
        this.assertions = assertions;
        this.session = session;
    }

    // ------------------------------------------------------------------
    // Scope choice
    // ------------------------------------------------------------------

    /**
     * The scope the learning UI should offer first.
     *
     * <p>Conservative by construction: a context-sensitive concept defaults to
     * the employment country, a per-employer question to the company, and only a
     * genuinely stable fact to global. Where the context cannot supply the
     * default's value - a remote posting that never said which country - it
     * falls back to this application alone, which is always safe and never
     * wrong.
     */
    public Scope defaultScopeFor(Concept concept, ApplicationContext context) {
        return scopeFor(concept, concept.defaultScope(), context)
                .orElseGet(() -> Scope.application(context == null ? null : context.postingId()));
    }

    /**
     * Every scope this answer could reasonably be saved at, narrowest first.
     *
     * <p>What a scope picker renders. A level whose value the context cannot
     * supply is left out rather than offered and then silently ignored.
     */
    public List<Scope> offeredScopes(Concept concept, ApplicationContext context) {
        List<Scope> offered = new ArrayList<>();
        for (Scope.Level level : Scope.Level.values()) {
            if (!concept.allowsScope(level)) {
                continue;
            }
            scopeFor(concept, level, context).ifPresent(offered::add);
        }
        return List.copyOf(offered);
    }

    private Optional<Scope> scopeFor(Concept concept, Scope.Level level,
            ApplicationContext context) {
        if (!concept.allowsScope(level)) {
            return Optional.empty();
        }
        if (level == Scope.Level.GLOBAL) {
            return Optional.of(Scope.global());
        }
        if (context == null) {
            return Optional.empty();
        }
        String value = switch (level) {
            case APPLICATION -> context.postingId() == null ? null
                    : context.postingId().toString();
            case COMPANY -> context.company();
            // The employment country, not the posting's: for a role worked from
            // Mumbai for a Berlin company, the answer belongs to India.
            case COUNTRY -> context.employmentCountryCode();
            case WORK_MODE -> context.workMode() == null ? null : context.workMode().name();
            case STRATEGIC_CLASS -> context.strategicClass() == null ? null
                    : context.strategicClass().name();
            case GLOBAL -> null;
        };
        // A scope with no value matches nothing, so storing one would create an
        // assertion that can never be resolved. Better to not offer the level.
        return value == null || value.isBlank() ? Optional.empty()
                : Optional.of(Scope.of(level, value));
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Answers the question for this application only. Nothing is persisted. */
    public void answerNow(ApplicationContext context, Concept concept, String value) {
        if (context != null) {
            session.record(context.postingId(), concept.id(), value);
        }
    }

    /**
     * Uses an answer now, and remembers it at a scope he chose.
     *
     * <p>The order matters and is the plan's: the current application gets the
     * answer regardless, and remembering it is a second, separable act. An
     * application must never be blocked because the scope picker was cancelled.
     */
    @Transactional
    public Assertion learn(ApplicationContext context, Concept concept, String value,
            Scope scope, KnowledgeSource source, String question, String via) {
        answerNow(context, concept, value);
        return remember(concept, value, scope, source, List.of(), question, via, true);
    }

    /**
     * Stores an assertion, superseding whatever it replaces.
     *
     * @param approved false for anything that must be read before it is used -
     *                 every AI proposal, and every migrated answer whose scope
     *                 nobody chose
     * @throws IllegalArgumentException when the concept forbids the scope. Not a
     *                                  silent downgrade: a caller asking to store
     *                                  sponsorship globally has a bug, and
     *                                  quietly narrowing it would hide the bug
     *                                  while looking like it worked.
     */
    @Transactional
    public Assertion remember(Concept concept, String value, Scope scope,
            KnowledgeSource source, List<Evidence> evidence, String question,
            String via, boolean approved) {

        if (!concept.allowsScope(scope.level())) {
            throw new IllegalArgumentException(
                    "'" + concept.id() + "' may not be stored at " + scope.level()
                            + (concept.contextSensitive()
                                    ? " - it is context-sensitive, so an answer given for one "
                                            + "country must not answer another"
                                    : "") + ". Allowed: " + concept.allowedScopes());
        }

        Assertion assertion = new Assertion(concept.id(), value, scope, source);
        // Checked on the way in, so a malformed row cannot be created by a path
        // that did not exist when the two in the live database were. Recorded
        // rather than refused: the write is a fact about what he said, and
        // rejecting it outright would lose it.
        typeProblem(concept, value).ifPresent(assertion::markInvalid);
        assertion.setSourceQuestion(question);
        assertion.setEvidenceList(evidence);
        assertion.setConfidence(source.isUserStated() ? Confidence.HIGH : Confidence.MEDIUM);
        if (approved) {
            assertion.approve(via);
        }
        Assertion saved = assertions.save(assertion);
        // After the save, so the older rows can point at a real id rather than a
        // placeholder. Superseding is a link, and a link to nothing is a lie in
        // the one table whose job is to explain itself.
        supersedeMatching(concept, scope, saved);
        // The concept, the scope and the provenance - never the value. This log
        // ends up in a terminal, in a scrollback buffer and occasionally in a
        // screenshot, and the values here are salary expectations, addresses and
        // visa status.
        log.info("Learned {} at {} from {} ({}, {} characters)", concept.id(),
                scope.describe(), source, approved ? "approved" : "awaiting approval",
                value == null ? 0 : value.length());
        return saved;
    }

    /**
     * Why a value cannot be an answer to this concept, if it cannot.
     *
     * <p>The one place the type check is phrased for a person. "This is a
     * sentence, not a yes or no" is something he can act on; "TYPE_MISMATCH" is
     * not.
     */
    public static java.util.Optional<String> typeProblem(Concept concept, String value) {
        if (concept == null || value == null || value.isBlank()
                || concept.answerType().accepts(value)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(switch (concept.answerType()) {
            case BOOLEAN -> "this is a sentence, and the question wants a yes or a no";
            case NUMBER -> "this is not a number";
            case DATE -> "this is not a date";
            case CHOICE -> "this is too long to be one of a form's options";
            default -> "this is not a usable answer for " + concept.id();
        });
    }

    /**
     * Marks every stored row whose value cannot be an answer to its concept.
     *
     * <p>A repair rather than a migration: nothing is deleted, nothing is
     * rewritten, and the value stays exactly as he left it. What changes is that
     * the row stops competing in resolution, which is the thing that was actually
     * wrong with it.
     *
     * @return the rows it marked, so the caller can say which and why
     */
    @Transactional
    public List<Assertion> markMalformed() {
        List<Assertion> marked = new ArrayList<>();
        for (Assertion assertion : assertions.findBySupersededByIdIsNull()) {
            if (assertion.isInvalid()) {
                continue;
            }
            Optional<Concept> concept = Concepts.byId(assertion.getConceptId());
            if (concept.isEmpty()) {
                continue;
            }
            typeProblem(concept.get(), assertion.getValue()).ifPresent(problem -> {
                assertion.markInvalid(problem);
                assertions.save(assertion);
                marked.add(assertion);
                // The concept and the reason, never the value. This reaches a
                // terminal and a scrollback buffer, and the values here are visa
                // status and salary expectations.
                log.warn("Marked {} at {} unusable: {}", assertion.getConceptId(),
                        assertion.scope().describe(), problem);
            });
        }
        return List.copyOf(marked);
    }

    /**
     * Records a model's draft. Unapproved, always.
     *
     * <p>The state an AI answer starts in and cannot skip: nothing reads an
     * unapproved assertion as fillable, so the only way a drafted sentence
     * reaches an employer is through {@link #approve}.
     */
    @Transactional
    public Assertion propose(Concept concept, String value, Scope scope,
            List<Evidence> evidence, String question) {
        if (!concept.aiEligible()) {
            throw new IllegalArgumentException(
                    "'" + concept.id() + "' is not open to a drafted answer");
        }
        Assertion assertion = new Assertion(concept.id(), value, scope,
                KnowledgeSource.AI_PROPOSED);
        assertion.setSourceQuestion(question);
        assertion.setEvidenceList(evidence);
        assertion.setConfidence(Confidence.LOW);
        assertion.setApproval(ApprovalState.UNAPPROVED);
        return assertions.save(assertion);
    }

    /**
     * Accepts a draft, optionally rewritten.
     *
     * <p>The source stays {@code AI_PROPOSED} forever. Approving records that he
     * read it, not that he wrote it, and the old system lost exactly this
     * distinction by writing approved drafts back into the profile as plain
     * pairs - after which they were indistinguishable from facts he had typed.
     */
    @Transactional
    public Optional<Assertion> approve(Long assertionId, String editedValue, String via) {
        return assertions.findById(assertionId).map(assertion -> {
            if (editedValue != null && !editedValue.isBlank()
                    && !editedValue.equals(assertion.getValue())) {
                assertion.setValue(editedValue.trim());
                assertion.setUserEdited(true);
            }
            assertion.approve(via);
            assertion.setConfidence(Confidence.HIGH);
            return assertions.save(assertion);
        });
    }

    @Transactional
    public Optional<Assertion> reject(Long assertionId, String via) {
        return assertions.findById(assertionId).map(assertion -> {
            assertion.reject(via);
            return assertions.save(assertion);
        });
    }

    /**
     * Records what was actually sent, as evidence for next time.
     *
     * <p>Historical, unapproved, and scoped to the context it was used in. It
     * will resolve and it will never auto-fill: that a German form was told "yes"
     * is a useful thing to be reminded of and not a fact about him.
     */
    @Transactional
    public Assertion recordHistorical(Concept concept, String value, Scope scope,
            String question, Long attemptId) {
        Assertion assertion = new Assertion(concept.id(), value, scope,
                KnowledgeSource.HISTORICAL);
        assertion.setSourceQuestion(question);
        assertion.setEvidenceList(List.of(Evidence.prior(
                "attempt " + attemptId, value)));
        assertion.setConfidence(Confidence.MEDIUM);
        assertion.setApproval(ApprovalState.UNAPPROVED);
        return assertions.save(assertion);
    }

    /** Marks a volatile assertion for re-checking on a date. */
    @Transactional
    public void setVerifyBy(Long assertionId, LocalDate verifyBy) {
        assertions.findById(assertionId).ifPresent(assertion -> {
            assertion.setVerifyBy(verifyBy);
            assertions.save(assertion);
        });
    }

    /**
     * Gives a migrated answer the scope nobody ever chose for it.
     *
     * <p>The way a pending assertion becomes usable. It supersedes rather than
     * mutates, so the row that says "this arrived from extra-answers with no
     * country recorded" survives next to the row that says "he decided it was
     * Germany" - and the second is a decision with a date on it rather than an
     * edit that erased the first.
     *
     * @throws IllegalArgumentException when the concept forbids the scope. The
     *                                  server is the boundary; a client sending
     *                                  GLOBAL for sponsorship is refused here
     *                                  whatever the form offered.
     */
    @Transactional
    public Assertion rescope(Long assertionId, Scope scope, String via) {
        Assertion original = assertions.findById(assertionId).orElseThrow(
                () -> new IllegalArgumentException("No assertion " + assertionId));
        Concept concept = Concepts.byId(original.getConceptId()).orElseThrow(
                () -> new IllegalArgumentException(
                        "'" + original.getConceptId() + "' is not a known concept, so it "
                                + "cannot be given a scope yet"));
        return replace(original, concept, original.getValue(), scope, via,
                "scope chosen by you; was " + original.scope().describe());
    }

    /**
     * Moves an answer stored under a made-up key onto a real concept.
     *
     * <p>Three answers in the live database are filed under keys built by
     * truncating the question at fifty characters -
     * {@code legacy.which_working_setup_gets_the_best_work_out_of_yo}. The
     * answers are good and he approved them; the key is not a concept, so
     * nothing resolves against it and every form asking the question in a
     * slightly different way asked him again.
     *
     * <p>A rekeying, not a generalisation: the value is unchanged, the old row is
     * superseded rather than deleted, and the scope is the concept's own default
     * rather than one invented here. Still not silent - it happens when he runs
     * the command, and the audit is what tells him it is worth running.
     *
     * @throws IllegalArgumentException when the concept forbids the scope, the
     *                                  same refusal every other write path makes
     */
    @Transactional
    public Assertion adopt(Long assertionId, Concept concept, String via) {
        Assertion original = assertions.findById(assertionId).orElseThrow(
                () -> new IllegalArgumentException("No assertion " + assertionId));
        Scope scope = concept.allowsScope(Scope.Level.GLOBAL)
                ? Scope.global() : Scope.of(concept.defaultScope(), null);
        if (scope.isPending()) {
            throw new IllegalArgumentException(
                    "'" + concept.id() + "' needs a scope value, so this one is yours to "
                            + "choose on the Knowledge page");
        }
        Assertion replacement = new Assertion(concept.id(), original.getValue(), scope,
                KnowledgeSource.USER_RULE);
        replacement.setSourceQuestion(original.getSourceQuestion());
        replacement.setEvidenceList(original.evidenceList());
        replacement.setNote("adopted from " + original.getConceptId()
                + ", which was a key made by truncating the question");
        replacement.setConfidence(Confidence.HIGH);
        replacement.approve(via);
        Assertion saved = assertions.save(replacement);

        original.supersededBy(saved);
        original.setNeedsReview(false);
        assertions.save(original);

        log.info("Adopted {} as {} at {}", original.getConceptId(), concept.id(),
                scope.describe());
        return saved;
    }

    /**
     * Changes what an assertion says, keeping what it used to say.
     *
     * <p>A new row, and the old one pointed at it. Application history is not
     * rewritten: what was sent to an employer in March stays true in the record
     * whatever he decides in September.
     */
    @Transactional
    public Assertion edit(Long assertionId, String newValue, Scope scope, String via) {
        Assertion original = assertions.findById(assertionId).orElseThrow(
                () -> new IllegalArgumentException("No assertion " + assertionId));
        Concept concept = Concepts.byId(original.getConceptId()).orElseThrow(
                () -> new IllegalArgumentException(
                        "'" + original.getConceptId() + "' is not a known concept"));
        return replace(original, concept, newValue,
                scope == null ? original.scope() : scope, via,
                "edited by you; was '" + original.getValue() + "' at "
                        + original.scope().describe());
    }

    /** Retires an assertion without deleting it. */
    @Transactional
    public Optional<Assertion> discard(Long assertionId, String via) {
        return assertions.findById(assertionId).map(assertion -> {
            assertion.reject(via);
            assertion.setNeedsReview(false);
            assertion.setNote((assertion.getNote() == null ? "" : assertion.getNote() + " ")
                    + "discarded by you");
            return assertions.save(assertion);
        });
    }

    /** Leaves it pending, and stops asking. */
    @Transactional
    public Optional<Assertion> keepPending(Long assertionId) {
        return assertions.findById(assertionId).map(assertion -> {
            assertion.setNeedsReview(false);
            assertion.setNote((assertion.getNote() == null ? "" : assertion.getNote() + " ")
                    + "left pending on purpose");
            return assertions.save(assertion);
        });
    }

    private Assertion replace(Assertion original, Concept concept, String value, Scope scope,
            String via, String note) {

        if (!concept.allowsScope(scope.level())) {
            throw new IllegalArgumentException(
                    "'" + concept.id() + "' may not be stored at " + scope.level()
                            + (concept.contextSensitive()
                                    ? " - it is context-sensitive, so an answer given for one "
                                            + "country must not answer another"
                                    : "") + ". Allowed: " + concept.allowedScopes());
        }
        if (scope.isPending()) {
            throw new IllegalArgumentException(
                    "A " + scope.level() + " scope needs a value to match on");
        }

        Assertion replacement = new Assertion(concept.id(), value, scope,
                KnowledgeSource.USER_RULE);
        replacement.setSourceQuestion(original.getSourceQuestion());
        replacement.setEvidenceList(original.evidenceList());
        replacement.setNote(note);
        replacement.setConfidence(Confidence.HIGH);
        replacement.approve(via);
        Assertion saved = assertions.save(replacement);

        original.supersededBy(saved);
        original.setNeedsReview(false);
        assertions.save(original);

        log.info("Rescoped {} to {} ({})", concept.id(), scope.describe(), via);
        return saved;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    public List<Assertion> live() {
        return assertions.findBySupersededByIdIsNull();
    }

    public List<Assertion> awaitingApproval() {
        return assertions.findByApprovalAndSupersededByIdIsNull(ApprovalState.UNAPPROVED);
    }

    /** Migrated with a scope nobody chose. The review queue the migration builds. */
    public List<Assertion> needingReview() {
        return assertions.findByNeedsReviewTrueAndSupersededByIdIsNull();
    }

    public List<Assertion> forConcept(String conceptId) {
        return assertions.findByConceptIdAndSupersededByIdIsNull(conceptId);
    }

    /**
     * Retires an existing assertion covering exactly the same ground.
     *
     * <p>Same concept, same scope, still live. Superseded rather than deleted or
     * updated in place, so "what did I tell them in March?" stays answerable
     * after the answer changes - the same reasoning that already stores the cover
     * letter as sent rather than regenerating it.
     */
    private void supersedeMatching(Concept concept, Scope scope, Assertion replacement) {
        Instant now = Instant.now();
        for (Assertion existing : assertions.findByConceptIdAndSupersededByIdIsNull(
                concept.id())) {
            if (!existing.getId().equals(replacement.getId()) && existing.scope().equals(scope)) {
                existing.supersededBy(replacement);
                existing.setNote((existing.getNote() == null ? "" : existing.getNote() + " ")
                        + "superseded " + now);
                assertions.save(existing);
            }
        }
    }
}
