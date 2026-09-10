package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * What the system knows, what it worked out, and what is wrong with it.
 *
 * <p>Read-only. It changes nothing and proposes everything, because the things it
 * finds are decisions rather than corrections - an assertion that shadows a
 * correct derivation might be a mistake or might be something he meant, and a
 * process that quietly deletes his stated answers is worse than one that lists
 * them.
 *
 * <h2>What it is actually for</h2>
 * Two audiences. It is how he sees that the system already knows his degree, his
 * university and forty technologies without being asked - which is the claim this
 * phase makes and the one worth checking. And it is how the four real defects
 * sitting in the live database got found: two pending assertions asking for
 * information a derivation already produces correctly, and two rules whose stored
 * <em>value</em> is a sentence explaining a rule rather than an answer to type
 * into a box.
 */
@Service
public class KnowledgeAudit {

    private final AssertionRepository assertions;
    private final ProfileFacts profileFacts;
    private final ExperienceIndex experience;
    private final ResumeModel resume;
    private final Derivations derivations;

    public KnowledgeAudit(AssertionRepository assertions, ProfileFacts profileFacts,
            ExperienceIndex experience, ResumeModel resume, Derivations derivations) {
        this.assertions = assertions;
        this.profileFacts = profileFacts;
        this.experience = experience;
        this.resume = resume;
        this.derivations = derivations;
    }

    /**
     * @param known      concepts answered without asking anyone
     * @param technologies what his resume shows he has used
     * @param findings   things worth a decision, most serious first
     */
    public record Report(List<Line> known, List<String> technologies, List<Finding> findings,
            int pending, int live) {

        public long serious() {
            return findings.stream().filter(finding -> finding.severity() == Severity.WRONG)
                    .count();
        }
    }

    /** One concept the system can already answer, and where the answer came from. */
    public record Line(String concept, String value, String source) {
    }

    /** How much a finding matters. Ordinal order is the reporting order. */
    public enum Severity {
        /**
         * Would produce a wrong answer.
         *
         * <p>Not on a form today: the resolver is still non-authoritative and
         * {@code FieldMapper} fills every real form. These are answers waiting at
         * the authority gate, and each one is a reason that gate stays shut.
         */
        WRONG,
        /** Interrupting him for something already derivable. */
        UNNECESSARY,
        /** Two entries for one thing. */
        DUPLICATE,
        /** Needs a decision only he can make. */
        DECISION
    }

    /**
     * @param remedy what to do, phrased as an action rather than a diagnosis. A
     *               finding nobody can act on is a finding nobody reads twice.
     */
    public record Finding(Severity severity, String what, String remedy, Long assertionId) {
    }

    // ------------------------------------------------------------------

    public Report run() {
        List<Line> known = new ArrayList<>();
        for (Concept concept : Concepts.all()) {
            profileFacts.valueFor(concept).ifPresent(fact -> known.add(new Line(
                    concept.id(),
                    fact.voluntaryDecline() ? "(declined on purpose)" : fact.value(),
                    "profile — " + evidencePath(fact))));
        }
        educationFrom(resume).ifPresent(known::add);

        List<Assertion> live = assertions.findBySupersededByIdIsNull();
        live.stream().filter(Assertion::isUsable).forEach(assertion -> known.add(new Line(
                assertion.getConceptId(), abbreviate(assertion.getValue()),
                "a rule you approved — " + assertion.scope().describe())));

        List<Finding> findings = new ArrayList<>();
        findings.addAll(malformedValues(live));
        findings.addAll(pendingThatDerivationAnswers(live));
        findings.addAll(rulesContradictingDerivations(live));
        findings.addAll(duplicates(live));
        findings.addAll(pendingNeedingHim(live));
        findings.sort(java.util.Comparator.comparingInt(f -> f.severity().ordinal()));

        return new Report(List.copyOf(known), new ArrayList<>(experience.terms()),
                List.copyOf(findings),
                (int) live.stream().filter(Assertion::isNeedsReview).count(),
                (int) live.stream().filter(Assertion::isUsable).count());
    }

    // ------------------------------------------------------------------

    /**
     * Rules whose stored value is not a usable answer to their own question.
     *
     * <p>Asks {@link Concept.AnswerType#accepts}, and nothing else. The first
     * version of this check had its own heuristic - longer than sixty characters,
     * or containing a full stop - and it reported the wrong thing about a real
     * row: "I am a citizen or permanent resident of the country where I plan to
     * live and work from" is eighty-four characters and is a perfectly
     * well-formed answer to a work-authorisation question, which boards offer
     * verbatim as an option.
     *
     * <p>What is wrong with that row is that it is <em>false for the country it
     * was filed under</em>, and that is a different finding made by a different
     * check. Two checks that overlap report one row twice and disagree about why.
     */
    private List<Finding> malformedValues(List<Assertion> live) {
        List<Finding> found = new ArrayList<>();
        for (Assertion assertion : live) {
            if (!assertion.isUsable() || assertion.getValue() == null) {
                continue;
            }
            Optional<Concept> concept = Concepts.byId(assertion.getConceptId());
            if (concept.isEmpty()) {
                continue;
            }
            KnowledgeService.typeProblem(concept.get(), assertion.getValue())
                    .ifPresent(problem -> found.add(new Finding(Severity.WRONG,
                            "'" + assertion.getConceptId() + "' at "
                                    + assertion.scope().describe() + " holds \""
                                    + abbreviate(assertion.getValue()) + "\" - " + problem,
                            "A rule outranks a derivation, so this is what would be typed "
                                    + "the day the resolver takes over. Run "
                                    + "`knowledge --repair` to stop it competing, and the "
                                    + "derivation answers instead.",
                            assertion.getId())));
        }
        return found;
    }

    /**
     * Pending answers for questions the deterministic derivations already handle.
     *
     * <p>Two of the live pending rows are work-authorisation questions waiting on
     * a country he has never chosen. He does not need to choose one: the
     * derivation reads the employment country off each application and answers
     * correctly for all of them, which is exactly what a country-scoped rule
     * cannot do. Asking him is asking for something less useful than what the
     * system already has.
     */
    private List<Finding> pendingThatDerivationAnswers(List<Assertion> live) {
        List<Finding> found = new ArrayList<>();
        for (Assertion assertion : live) {
            if (!assertion.isNeedsReview()) {
                continue;
            }
            Optional<Concept> concept = Concepts.byId(assertion.getConceptId());
            if (concept.isEmpty() || !concept.get().contextSensitive()) {
                continue;
            }
            boolean derivable = derivations
                    .derive(concept.get(), Contexts.germanyOnsite()).isPresent()
                    && derivations.derive(concept.get(), Contexts.indiaOnsite()).isPresent();
            if (derivable) {
                found.add(new Finding(Severity.UNNECESSARY,
                        "'" + assertion.getConceptId() + "' is waiting for you to pick a "
                                + "country, and is derived correctly for every country "
                                + "without one",
                        "Discard it. The derivation reads the employment country off each "
                                + "application, which one country-scoped rule cannot.",
                        assertion.getId()));
            }
        }
        return found;
    }

    /**
     * Rules that assert the opposite of what the derivation concludes.
     *
     * <p>The check a type validator cannot make. One live rule says "I am a
     * citizen or permanent resident of the country where I plan to live and work
     * from" and is scoped to Germany - a well-formed answer to a
     * work-authorisation question, and false for the country it was filed under.
     * The derivation for Germany says he is not authorised, and the rule outranks
     * it.
     *
     * <p>Reported rather than repaired. A stored rule disagreeing with a
     * derivation is not automatically the rule being wrong - he may know
     * something the derivation does not - so both are shown and the decision is
     * his. What is not acceptable is neither of them noticing.
     */
    private List<Finding> rulesContradictingDerivations(List<Assertion> live) {
        List<Finding> found = new ArrayList<>();
        for (Assertion assertion : live) {
            if (!assertion.isUsable() || assertion.scope().value() == null
                    || assertion.getScopeLevel() != Scope.Level.COUNTRY) {
                continue;
            }
            Optional<Concept> concept = Concepts.byId(assertion.getConceptId());
            if (concept.isEmpty() || !concept.get().contextSensitive()
                    || KnowledgeService.typeProblem(concept.get(), assertion.getValue())
                            .isPresent()) {
                // Already reported as malformed, with a remedy of its own.
                continue;
            }
            ApplicationContext where = Contexts.onsiteIn(assertion.scope().value());
            Optional<Resolution> derived = derivations.derive(concept.get(), where);
            if (derived.isEmpty() || derived.get().confidence() != Confidence.HIGH) {
                continue;
            }
            Optional<Boolean> stored = com.anuragbhandary.jobradar.apply.form.SemanticOptions
                    .polarityOf(concept.get(), assertion.getValue());
            Optional<Boolean> computed = com.anuragbhandary.jobradar.apply.form.SemanticOptions
                    .polarityOf(concept.get(), derived.get().value());
            if (stored.isEmpty() || computed.isEmpty()
                    || stored.get().equals(computed.get())) {
                continue;
            }
            found.add(new Finding(Severity.WRONG,
                    "'" + assertion.getConceptId() + "' at " + assertion.scope().describe()
                            + " says \"" + abbreviate(assertion.getValue())
                            + "\", and the derivation for that country concludes \""
                            + derived.get().value() + "\" - " + derived.get().explanation(),
                    "A rule outranks a derivation, so this is the answer that would be "
                            + "used. Discard it if the derivation is right, or correct it "
                            + "if it is not.",
                    assertion.getId()));
        }
        return found;
    }

    /** Two live entries saying different things about the same concept and scope. */
    private List<Finding> duplicates(List<Assertion> live) {
        Map<String, List<Assertion>> byKey = new LinkedHashMap<>();
        live.stream().filter(Assertion::isUsable).forEach(assertion ->
                byKey.computeIfAbsent(assertion.getConceptId() + "@"
                        + assertion.scope().describe(), key -> new ArrayList<>())
                        .add(assertion));

        List<Finding> found = new ArrayList<>();
        byKey.forEach((key, group) -> {
            if (group.size() < 2) {
                return;
            }
            boolean disagree = group.stream()
                    .map(assertion -> String.valueOf(assertion.getValue())
                            .toLowerCase(Locale.ROOT).trim())
                    .distinct().count() > 1;
            found.add(new Finding(disagree ? Severity.WRONG : Severity.DUPLICATE,
                    group.size() + " live answers for " + key
                            + (disagree ? ", and they disagree: " : ", all the same: ")
                            + group.stream().map(a -> "\"" + abbreviate(a.getValue()) + "\"")
                                    .reduce((a, b) -> a + " / " + b).orElse(""),
                    disagree
                            ? "Whichever the database returns first wins. Keep one."
                            : "Harmless, and worth tidying.",
                    group.getFirst().getId()));
        });
        return found;
    }

    /** What is left after the above: things only he can settle. */
    private List<Finding> pendingNeedingHim(List<Assertion> live) {
        List<Finding> found = new ArrayList<>();
        for (Assertion assertion : live) {
            if (!assertion.isNeedsReview()) {
                continue;
            }
            Optional<Concept> concept = Concepts.byId(assertion.getConceptId());
            if (concept.isPresent() && concept.get().contextSensitive()
                    && derivations.derive(concept.get(), Contexts.germanyOnsite())
                            .isPresent()) {
                continue;
            }
            String id = assertion.getConceptId();
            if (id.startsWith("legacy.")) {
                found.add(new Finding(Severity.DECISION,
                        "'" + id + "' was stored under a key made by truncating the "
                                + "question. There is now a concept for it.",
                        "Give it the new concept and a scope, or discard it - the "
                                + "answer is good, the key is not.",
                        assertion.getId()));
                continue;
            }
            if (concept.map(c -> c.allowsScope(Scope.Level.GLOBAL)).orElse(false)) {
                found.add(new Finding(Severity.DECISION,
                        "'" + id + "' is pending, and this concept may now be saved "
                                + "globally: \"" + abbreviate(assertion.getValue()) + "\"",
                        "If that answer is true everywhere, save it once and it stops "
                                + "being asked.",
                        assertion.getId()));
                continue;
            }
            found.add(new Finding(Severity.DECISION,
                    "'" + id + "' is pending and genuinely depends on something only you "
                            + "know",
                    "Leave it, or give it a scope on the Knowledge page.",
                    assertion.getId()));
        }
        return found;
    }

    // ------------------------------------------------------------------

    /** His degree and university, which no form should ever have to ask him for. */
    private static Optional<Line> educationFrom(ResumeModel resume) {
        if (resume == null || resume.education() == null || resume.education().isEmpty()) {
            return Optional.empty();
        }
        ResumeModel.Education highest = resume.education().getFirst();
        return Optional.of(new Line("education.institution",
                highest.degree() + ", " + highest.institution(),
                "resume — education[0]"));
    }

    private static String evidencePath(ProfileFacts.Fact fact) {
        return fact.evidence().isEmpty() ? "applicant.yml"
                : fact.evidence().getFirst().ref();
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "(none)";
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 70 ? trimmed : trimmed.substring(0, 67) + "...";
    }

    /**
     * The two contexts used to ask whether a derivation covers a concept.
     *
     * <p>Germany and India, because between them they exercise both sides of
     * every context-sensitive derivation this system has: permit needed and
     * permit not needed. A concept answered in both is answered everywhere.
     */
    private static final class Contexts {

        private Contexts() {
        }

        static ApplicationContext germanyOnsite() {
            return context("DE");
        }

        /** An onsite role in one country, for asking what a derivation says there. */
        static ApplicationContext onsiteIn(String countryCode) {
            return context(countryCode == null ? null
                    : countryCode.toUpperCase(java.util.Locale.ROOT));
        }

        static ApplicationContext indiaOnsite() {
            return context("IN");
        }

        private static ApplicationContext context(String country) {
            return new ApplicationContext(0L, "audit", "audit", "AUDIT", "Engineer",
                    "backend", country, country, country,
                    com.anuragbhandary.jobradar.domain.WorkMode.ONSITE, List.of(),
                    com.anuragbhandary.jobradar.domain.StrategicClass.UNCLASSIFIED,
                    java.util.Set.of("IN"), "IN", null, null, null);
        }
    }

    /** Unused today; kept so a missing verify-by cannot silently become "fresh". */
    static boolean isStale(Assertion assertion, LocalDate today) {
        return assertion.getVerifyBy() != null && assertion.isStale(today);
    }
}
