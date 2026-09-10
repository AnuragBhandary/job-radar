package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.apply.form.FieldClassifier;
import com.anuragbhandary.jobradar.apply.form.FieldKind;
import com.anuragbhandary.jobradar.apply.form.FormField;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Turns a question plus a context into an answer, or into an honest refusal.
 *
 * <p><strong>Pure and deterministic.</strong> No browser, no clock beyond the
 * date, and <em>no model</em>: given the same assertions and the same context it
 * returns the same result. A model may draft a proposal - see
 * {@link com.anuragbhandary.jobradar.knowledge.ai.AnswerProposer} - but the
 * routing that decides whether a proposal is even wanted happens here, in Java,
 * where it can be tested exhaustively.
 *
 * <h2>How it decides</h2>
 * Candidates are gathered from every source, then ranked once. The four things
 * the plan asked to be kept apart really are kept apart:
 *
 * <ol>
 *   <li><b>Applicability</b> - a candidate whose scope does not cover this
 *       context is not a candidate. This is the safety property: a German
 *       sponsorship rule is not "outranked" for an Indian application, it is
 *       never in the running.</li>
 *   <li><b>Source authority</b> - see {@link KnowledgeSource}.</li>
 *   <li><b>Scope specificity</b> - among equally authoritative candidates, the
 *       narrower one wins.</li>
 *   <li><b>Freshness</b> - never changes who wins, only how much the winner is
 *       trusted. A stale volatile fact is shown rather than sent.</li>
 * </ol>
 *
 * <p>Anything left tied on authority <em>and</em> specificity with a different
 * value is a {@link Resolution.State#CONFLICT}, not a coin toss.
 */
@Service
public class KnowledgeResolver {

    private final AssertionRepository assertions;
    private final ProfileFacts profileFacts;
    private final Derivations derivations;
    private final SessionAnswers session;
    private final FieldClassifier classifier;

    public KnowledgeResolver(AssertionRepository assertions, ProfileFacts profileFacts,
            Derivations derivations, SessionAnswers session, FieldClassifier classifier) {
        this.assertions = assertions;
        this.profileFacts = profileFacts;
        this.derivations = derivations;
        this.session = session;
        this.classifier = classifier;
    }

    /**
     * One candidate answer, before anything has been decided between them.
     *
     * @param assertion the stored row, when the candidate came from one
     */
    private record Candidate(
            String value,
            KnowledgeSource source,
            Scope scope,
            Confidence confidence,
            List<Evidence> evidence,
            Assertion assertion,
            Resolution.State state,
            String explanation) {

        int authority() {
            return source.authority();
        }

        int specificity() {
            return scope.specificity();
        }
    }

    // ------------------------------------------------------------------
    // Concept identification: deterministic first, always
    // ------------------------------------------------------------------

    /**
     * Which concept a form field is asking about.
     *
     * <p>The classifier runs first and its answer is trusted. It is forty ordered
     * rules, every one of them written after a real misclassification - "Race /
     * Ethnicity" reading as CITY, a referral question filled with his own name -
     * and none of that is worth re-deriving. Aliases only get a say when the
     * classifier declines, which is where the questions that are not form-field
     * archetypes live: passports, ages, degrees.
     */
    public Concept conceptFor(FormField field) {
        if (field == null) {
            return Concept.UNRECOGNISED;
        }
        FieldKind kind = field.kind() != null && field.kind() != FieldKind.UNKNOWN
                ? field.kind() : classifier.classify(field);
        if (kind != null && kind != FieldKind.UNKNOWN) {
            Optional<Concept> byKind = Concepts.forKind(kind);
            if (byKind.isPresent()) {
                return byKind.get();
            }
        }
        return Concepts.byAlias(field.label()).orElse(Concept.UNRECOGNISED);
    }

    public Concept conceptFor(String questionLabel) {
        return Concepts.byAlias(questionLabel).orElse(Concept.UNRECOGNISED);
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    public Resolution resolve(FormField field, ApplicationContext context) {
        return resolve(conceptFor(field), context);
    }

    public Resolution resolve(Concept concept, ApplicationContext context) {
        return resolve(concept, context, LocalDate.now());
    }

    /** Today is a parameter so freshness is testable without waiting a year. */
    public Resolution resolve(Concept concept, ApplicationContext context, LocalDate today) {
        if (concept == null || concept.isUnrecognised()) {
            return Resolution.unknown(Concept.UNRECOGNISED,
                    "this question has not been classified, so nothing is known about it");
        }
        if (concept.answerType() == Concept.AnswerType.NONE) {
            // Consent, and the cover-letter attachment slot. Recognised, and
            // never answered: agreeing to a company's terms on someone's behalf
            // is not form filling.
            return Resolution.declined(concept,
                    "recognised and deliberately not answered - this one is yours to read");
        }

        List<Candidate> candidates = gather(concept, context, today);
        if (candidates.isEmpty()) {
            return Resolution.unknown(concept,
                    "nothing applies to " + describeContext(context));
        }

        candidates.sort(Comparator
                .comparingInt(Candidate::authority)
                .thenComparingInt(Candidate::specificity));

        Candidate best = candidates.getFirst();
        List<Candidate> tied = candidates.stream()
                .filter(c -> c.authority() == best.authority()
                        && c.specificity() == best.specificity())
                .toList();

        Set<String> distinct = new LinkedHashSet<>();
        tied.forEach(c -> distinct.add(c.value() == null ? "" : c.value()));
        if (distinct.size() > 1) {
            // Equally applicable, equally authoritative, and they disagree. The
            // one thing that must never be resolved by picking whichever the
            // database returned first.
            return Resolution.conflict(concept, tied.stream()
                    .map(Candidate::assertion)
                    .filter(java.util.Objects::nonNull)
                    .toList());
        }

        List<Assertion> alsoApplied = candidates.stream()
                .skip(1)
                .map(Candidate::assertion)
                .filter(java.util.Objects::nonNull)
                .toList();

        return switch (best.state()) {
            case DECLINED -> Resolution.declined(concept, best.explanation());
            case DERIVED -> Resolution.derived(concept, best.value(), best.confidence(),
                    best.evidence(), best.explanation());
            default -> new Resolution(best.state(), best.value(), concept, best.confidence(),
                    best.source(), best.evidence(), best.assertion(), alsoApplied,
                    best.explanation());
        };
    }

    // ------------------------------------------------------------------

    private List<Candidate> gather(Concept concept, ApplicationContext context, LocalDate today) {
        List<Candidate> candidates = new ArrayList<>();

        // 1. What he said a moment ago, about this application.
        if (context != null) {
            session.get(context.postingId(), concept.id()).ifPresent(value ->
                    candidates.add(new Candidate(value, KnowledgeSource.SESSION,
                            Scope.application(context.postingId()), Confidence.HIGH,
                            List.of(Evidence.context("answered during this preparation", value)),
                            null, Resolution.State.KNOWN,
                            "you answered this during this preparation")));
        }

        // 2. Stored assertions whose scope covers this context.
        for (Assertion assertion : assertions.findByConceptIdAndSupersededByIdIsNull(
                concept.id())) {
            // Three separate reasons a stored row is not a candidate, and they
            // are checked before anything is ranked. Source authority decides
            // between candidates; it does not promote something that should
            // never have been one.
            //
            // The third is the reason this comment exists. A row holding "In all
            // countries other than India, the answer is yes, I need sponsorship"
            // is a USER_RULE, which outranks DERIVED - so it won, and that whole
            // sentence was what would have been typed into a yes/no box.
            if (assertion.getApproval() == ApprovalState.REJECTED
                    || assertion.isInvalid()
                    || !assertion.scope().appliesTo(context)) {
                continue;
            }
            candidates.add(fromAssertion(concept, assertion, today));
        }

        // 3. Stable profile facts.
        profileFacts.valueFor(concept).ifPresent(fact -> candidates.add(new Candidate(
                fact.value(), KnowledgeSource.PROFILE, Scope.global(), Confidence.HIGH,
                fact.evidence(), null,
                fact.voluntaryDecline() ? Resolution.State.DECLINED : Resolution.State.KNOWN,
                fact.voluntaryDecline()
                        ? "left blank in your profile, and declining is the intended answer"
                        : "copied from your profile")));

        // 4. Deterministic derivation.
        derivations.derive(concept, context).ifPresent(resolution -> candidates.add(
                new Candidate(resolution.value(), KnowledgeSource.DERIVED,
                        derivedScope(context), resolution.confidence(), resolution.evidence(),
                        null, Resolution.State.DERIVED, resolution.explanation())));

        return candidates;
    }

    /**
     * A derivation is as specific as the context it read.
     *
     * <p>Scoped to the employment country when there is one, so that an explicit
     * country rule and a derivation about the same country meet on equal
     * specificity and are separated on authority - which is the ordering the plan
     * asks for: an approved rule beats a computed one.
     */
    private static Scope derivedScope(ApplicationContext context) {
        String employment = context == null ? null : context.employmentCountryCode();
        return employment == null ? Scope.global() : Scope.country(employment);
    }

    private Candidate fromAssertion(Concept concept, Assertion assertion, LocalDate today) {
        Confidence confidence = assertion.getConfidence();

        // Unapproved knowledge resolves but never auto-fills. Historical answers
        // and unapproved AI drafts are evidence about him, not statements by him.
        if (!assertion.isUsable()) {
            confidence = Confidence.LOW;
        } else if (concept.volatileFact() && assertion.isStale(today)) {
            // Freshness changes trust, never the winner, and never the stored
            // value. A notice period from a year ago is probably still right and
            // is not something to overwrite on a guess.
            confidence = confidence.downgrade();
        }

        String why = switch (assertion.getSource()) {
            case USER_RULE -> "a rule you approved for " + assertion.scope().describe();
            case USER_INPUT -> "you answered this for " + assertion.scope().describe();
            case HISTORICAL -> "you gave this answer on an earlier application ("
                    + assertion.scope().describe() + ")";
            case AI_PROPOSED -> assertion.getApproval() == ApprovalState.APPROVED
                    ? "an assistant draft you approved for " + assertion.scope().describe()
                    : "an assistant draft awaiting your approval";
            default -> "stored knowledge for " + assertion.scope().describe();
        };
        if (concept.volatileFact() && assertion.isStale(today)) {
            why += ", which is past its check-by date of " + assertion.getVerifyBy();
        }

        Resolution.State state = assertion.getSource() == KnowledgeSource.AI_PROPOSED
                && assertion.getApproval() != ApprovalState.APPROVED
                        ? Resolution.State.AI_PROPOSED : Resolution.State.KNOWN;

        return new Candidate(assertion.getValue(), assertion.getSource(), assertion.scope(),
                confidence, assertion.evidenceList(), assertion, state, why);
    }

    private static String describeContext(ApplicationContext context) {
        return context == null ? "this question" : context.describe();
    }

    /**
     * The whole decision, written out.
     *
     * <p>For diagnosing an answer that looks wrong, which is a different job
     * from explaining one that looks right: it lists the candidates that were
     * considered <em>and</em> the ones that were never in the running, because
     * "why did it not use my German rule?" is answered by the second list.
     *
     * <p>Prints values. It is a local command about his own data, run by him,
     * and an explanation with the answers redacted would explain nothing.
     */
    public String trace(Concept concept, ApplicationContext context, LocalDate today) {
        StringBuilder out = new StringBuilder();
        out.append("Concept:  ").append(concept.id())
                .append(concept.contextSensitive() ? "  (context-sensitive)" : "").append('\n');
        out.append("Context:  ").append(context == null ? "none" : context.describe())
                .append('\n');

        List<Assertion> all = concept.isUnrecognised() ? List.of()
                : assertions.findByConceptIdAndSupersededByIdIsNull(concept.id());
        List<Assertion> applicable = all.stream()
                .filter(a -> a.getApproval() != ApprovalState.REJECTED)
                .filter(a -> !a.isInvalid())
                .filter(a -> a.scope().appliesTo(context))
                .toList();
        List<Assertion> notApplicable = all.stream()
                .filter(a -> !applicable.contains(a))
                .toList();

        out.append("\nStored assertions for this concept: ").append(all.size()).append('\n');
        for (Assertion assertion : applicable) {
            out.append("  applies    ").append(describe(assertion)).append('\n');
        }
        for (Assertion assertion : notApplicable) {
            out.append("  not here   ").append(describe(assertion))
                    .append(assertion.getApproval() == ApprovalState.REJECTED
                            ? "  [discarded]"
                            : assertion.isInvalid()
                                    ? "  [not a usable answer: "
                                            + assertion.getInvalidReason() + "]"
                                    : "  [scope does not cover this application]")
                    .append('\n');
        }
        if (all.isEmpty()) {
            out.append("  (none)\n");
        }

        Resolution resolution = resolve(concept, context, today);
        out.append("\nCandidates considered, best first:\n");
        for (Candidate candidate : gather(concept, context, today).stream()
                .sorted(Comparator.comparingInt(Candidate::authority)
                        .thenComparingInt(Candidate::specificity))
                .toList()) {
            out.append(String.format("  %-12s %-22s %s%n", candidate.source(),
                    candidate.scope().describe(),
                    candidate.value() == null ? "(declined)" : candidate.value()));
        }

        out.append('\n').append(resolution.why());
        out.append("State: ").append(resolution.state())
                .append(" | auto-fillable: ").append(resolution.isAutoFillable())
                .append(" | needs review: ").append(resolution.needsReview()).append('\n');
        return out.toString();
    }

    private static String describe(Assertion assertion) {
        return String.format("%-22s %-12s %-11s %s", assertion.scope().describe(),
                assertion.getSource(), assertion.getApproval(),
                assertion.getValue() == null ? "(none)" : assertion.getValue());
    }

    /**
     * Every live assertion that would apply here, ranked, for an explain screen.
     *
     * <p>Read-only and side-effect free: the resolver already computes this to
     * decide, and exposing it is what makes "why did Job Radar choose this?"
     * answerable without a second, differently-behaving code path.
     */
    public List<Assertion> applicableAssertions(Concept concept, ApplicationContext context) {
        if (concept == null) {
            return List.of();
        }
        return assertions.findByConceptIdAndSupersededByIdIsNull(concept.id()).stream()
                .filter(a -> a.getApproval() != ApprovalState.REJECTED)
                .filter(a -> !a.isInvalid())
                .filter(a -> a.scope().appliesTo(context))
                .sorted(Comparator
                        .comparingInt((Assertion a) -> a.getSource().authority())
                        .thenComparingInt(a -> a.scope().specificity()))
                .toList();
    }
}
