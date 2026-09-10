package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.WorkMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Answers worked out from the context, in Java, with no stored value.
 *
 * <p>Deterministic on purpose and by policy: a model is never asked anything a
 * subtraction can settle. These are also the answers most worth getting right -
 * work authorisation and sponsorship are asked in opposite polarity, are asked on
 * nearly every form, and are auto-reject triggers on most boards.
 *
 * <p>Each derivation returns evidence naming the context fields it read, so the
 * review screen can show the reasoning rather than only the conclusion.
 */
@Component
public class Derivations {

    /** One deterministic answer, or empty when the context does not support one. */
    @FunctionalInterface
    public interface Derivation {
        Optional<Resolution> derive(Concept concept, ApplicationContext context);
    }

    private final ApplicantProfile profile;
    /** The employment periods, for the one derivation that needs dates. */
    private final com.anuragbhandary.jobradar.apply.resume.ResumeModel resume;
    private final Map<String, Derivation> byConcept;

    /**
     * Without the resume, which is how most tests build it.
     *
     * <p>The only derivation that needs it is the years one, and it returns empty
     * rather than guessing when it is absent - so a context built this way
     * behaves exactly as it did before the derivation existed.
     */
    public Derivations(ApplicantProfile profile) {
        this(profile, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public Derivations(ApplicantProfile profile,
            com.anuragbhandary.jobradar.apply.resume.ResumeModel resume) {
        this.profile = profile;
        this.resume = resume;
        this.byConcept = Map.of(
                Concepts.SPONSORSHIP_REQUIRED.id(), this::sponsorship,
                Concepts.WORK_AUTHORISATION.id(), this::workAuthorisation,
                Concepts.RELOCATION_WILLING.id(), this::relocation,
                Concepts.SALARY_EXPECTATION.id(), this::salary,
                Concepts.REMOTE_WORK_WILLING.id(), this::remoteWilling,
                Concepts.RESUME_UPLOAD.id(), this::resume,
                Concepts.COVER_LETTER_TEXT.id(), this::coverLetter,
                Concepts.YEARS_OF_EXPERIENCE.id(), this::yearsOfExperience);
    }

    public boolean hasDerivation(Concept concept) {
        return concept != null && byConcept.containsKey(concept.id());
    }

    public Optional<Resolution> derive(Concept concept, ApplicationContext context) {
        Derivation derivation = concept == null ? null : byConcept.get(concept.id());
        return derivation == null ? Optional.empty() : derivation.derive(concept, context);
    }

    /**
     * How long he has been working, counted from the dates on his own resume.
     *
     * <p>Added because two stored answers disagreed - "Less than 2 years" and
     * "1" - both approved, both global, and whichever the database returned first
     * won. Neither was wrong; they were the same fact written for two different
     * form controls, and storing a <em>presentation</em> as knowledge is what let
     * them contradict each other.
     *
     * <p>So the knowledge layer holds one number, derived from the periods on the
     * resume, and the form layer decides whether this particular box wants "1", a
     * range, or one of a dropdown's bands. Nothing here knows about dropdowns.
     *
     * <h2>Rounded down, deliberately</h2>
     * Eleven months is "1", not "almost 2". A number on an application is read as
     * a floor by whoever reads it, and rounding up is the one direction that
     * turns an honest answer into a slightly dishonest one.
     */
    private Optional<Resolution> yearsOfExperience(Concept concept,
            ApplicationContext context) {
        if (resume == null || resume.experience() == null || resume.experience().isEmpty()) {
            return Optional.empty();
        }
        long months = 0;
        List<Evidence> evidence = new java.util.ArrayList<>();
        for (var job : resume.experience()) {
            Optional<Long> span = monthsIn(job.period());
            if (span.isEmpty()) {
                // A period nobody can parse is not zero months. Refusing the
                // whole derivation is right: a total missing one job is a number
                // that looks precise and is wrong.
                return Optional.empty();
            }
            months += span.get();
            evidence.add(Evidence.resume(
                    job.company() == null ? "a role" : job.company(), job.period()));
        }
        if (months <= 0) {
            return Optional.empty();
        }
        long years = months / 12;
        String value = String.valueOf(years);
        String said = years == 0
                ? "under a year"
                : years + (years == 1 ? " year" : " years");
        return Optional.of(Resolution.derived(concept, value, Confidence.HIGH, evidence,
                "counted from the employment dates on your resume - " + months
                        + " months, which is " + said + " rounded down"));
    }

    /**
     * The months in "Jul 2025 - Jun 2026", inclusive of both ends.
     *
     * <p>Handles the three dashes a resume actually uses and the word "Present".
     * Anything else returns empty rather than a guess, which stops the whole
     * derivation - see the note above about a total that quietly omits a job.
     */
    static Optional<Long> monthsIn(String period) {
        if (period == null || period.isBlank()) {
            return Optional.empty();
        }
        String[] halves = period.split("\\s*[\\u2013\\u2014-]\\s*", 2);
        if (halves.length != 2) {
            return Optional.empty();
        }
        Optional<java.time.YearMonth> from = monthOf(halves[0]);
        Optional<java.time.YearMonth> to = halves[1].toLowerCase(java.util.Locale.ROOT)
                .contains("present") || halves[1].toLowerCase(java.util.Locale.ROOT)
                        .contains("current")
                ? Optional.of(java.time.YearMonth.now())
                : monthOf(halves[1]);
        if (from.isEmpty() || to.isEmpty() || to.get().isBefore(from.get())) {
            return Optional.empty();
        }
        return Optional.of(java.time.temporal.ChronoUnit.MONTHS.between(
                from.get(), to.get()) + 1);
    }

    private static Optional<java.time.YearMonth> monthOf(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([A-Za-z]{3,9})\\s+(\\d{4})").matcher(text.trim());
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            java.time.Month month = java.time.Month.valueOf(
                    matcher.group(1).substring(0, 3).toUpperCase(java.util.Locale.ROOT)
                            .equals("SEP") ? "SEPTEMBER"
                            : fullMonth(matcher.group(1)));
            return Optional.of(java.time.YearMonth.of(
                    Integer.parseInt(matcher.group(2)), month));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String fullMonth(String abbreviation) {
        String key = abbreviation.substring(0, 3).toUpperCase(java.util.Locale.ROOT);
        return switch (key) {
            case "JAN" -> "JANUARY";
            case "FEB" -> "FEBRUARY";
            case "MAR" -> "MARCH";
            case "APR" -> "APRIL";
            case "MAY" -> "MAY";
            case "JUN" -> "JUNE";
            case "JUL" -> "JULY";
            case "AUG" -> "AUGUST";
            case "SEP" -> "SEPTEMBER";
            case "OCT" -> "OCTOBER";
            case "NOV" -> "NOVEMBER";
            case "DEC" -> "DECEMBER";
            default -> key;
        };
    }

    // ------------------------------------------------------------------
    // The two that are asked in opposite polarity and both auto-reject
    // ------------------------------------------------------------------

    /**
     * "Will you now or in the future require sponsorship?"
     *
     * <p>Answered from the <em>employment</em> country - where he will actually
     * sit - and not from the posting's country. The two differ in the case the
     * whole strategy is built around:
     *
     * <pre>
     *   Bengaluru, onsite          employment IN   authorised     No
     *   Berlin, onsite             employment DE   not authorised Yes
     *   New York, onsite           employment US   not authorised Yes
     *   US employer, remote from   employment IN   authorised     No     &lt;- not a
     *     India (posting says so)                                          US relocation
     *   "Remote", nothing stated   employment ?    unknown        Yes, at MEDIUM
     * </pre>
     *
     * <p>Answered honestly in every case. Saying "no" to get past a filter
     * produces an interview that ends the moment the question is asked properly,
     * having spent the one application the posting allows.
     */
    private Optional<Resolution> sponsorship(Concept concept, ApplicationContext context) {
        String employment = context.employmentCountryCode();
        List<Evidence> evidence = contextEvidence(context);

        if (employment == null) {
            // Remote, and the posting never said from where. Both directions of
            // this answer are harmful if wrong - claiming a permit he does not
            // have surfaces at the offer stage, and declaring a need for one he
            // does not need is an auto-reject - so the useful thing is not to
            // pick a "safe" direction but to refuse to fill it unread.
            //
            // MEDIUM does that: it never auto-fills. The value offered alongside
            // is the likeliest reading, which for a remote role is that he works
            // from home, and the explanation says exactly how much that is worth.
            boolean authorisedAtHome = context.homeCountryCode() != null
                    && context.authorisedCountryCodes().contains(context.homeCountryCode());
            return Optional.of(Resolution.derived(concept, yesNo(!authorisedAtHome),
                    Confidence.MEDIUM, evidence,
                    "the posting says remote but never says where from. Answered as "
                            + "though you work from "
                            + CountryCodes.displayName(context.homeCountryCode())
                            + ", which is the likeliest reading and not something the "
                            + "posting has confirmed - check it before this is sent."));
        }

        boolean authorised = context.authorisedCountryCodes().contains(employment);
        String where = CountryCodes.displayName(employment);
        String why = authorised
                ? "you may work in " + where + " without a permit, so no sponsorship is needed"
                : "this job is worked from " + where + ", where you would need a permit";
        if (context.statesRemoteFromHome() && context.authorisedCountryCodes().contains(employment)) {
            why = "the posting says a remote employee may be in " + where
                    + ", where you need no permit, so this is not a relocation at all";
        }
        return Optional.of(Resolution.derived(concept, yesNo(!authorised), Confidence.HIGH,
                evidence, why));
    }

    /**
     * "Are you legally authorised to work in X?"
     *
     * <p>The inverse of {@link #sponsorship}, and the one that must not be
     * optimistic: claiming authorisation he does not have is a false statement on
     * an application, and it surfaces at the offer stage rather than the
     * screening stage, which is much worse.
     */
    private Optional<Resolution> workAuthorisation(Concept concept, ApplicationContext context) {
        String employment = context.employmentCountryCode();
        List<Evidence> evidence = contextEvidence(context);

        if (employment == null) {
            boolean authorisedAtHome = context.homeCountryCode() != null
                    && context.authorisedCountryCodes().contains(context.homeCountryCode());
            return Optional.of(Resolution.derived(concept, yesNo(authorisedAtHome),
                    Confidence.MEDIUM, evidence,
                    "the posting says remote but never says where from. Answered as "
                            + "though you work from "
                            + CountryCodes.displayName(context.homeCountryCode())
                            + ", which is the likeliest reading and not something the "
                            + "posting has confirmed - check it before this is sent."));
        }
        boolean authorised = context.authorisedCountryCodes().contains(employment);
        return Optional.of(Resolution.derived(concept, yesNo(authorised), Confidence.HIGH,
                evidence,
                authorised
                        ? "you may work in " + CountryCodes.displayName(employment)
                                + " without a permit"
                        : "you would need a permit for "
                                + CountryCodes.displayName(employment)));
    }

    // ------------------------------------------------------------------

    /**
     * "Are you willing to relocate?"
     *
     * <p>Context-sensitive in a way that is easy to miss: for a role already
     * worked from his own city the honest answer is that there is nothing to
     * relocate for, and answering the profile's blanket "yes" reads as a
     * willingness to move for a job that never asked.
     */
    private Optional<Resolution> relocation(Concept concept, ApplicationContext context) {
        if (profile == null || profile.workAuthorisation() == null) {
            return Optional.empty();
        }
        boolean willing = profile.workAuthorisation().willRelocate();
        List<Evidence> evidence = new ArrayList<>(contextEvidence(context));
        evidence.add(Evidence.profile("work-authorisation.will-relocate", String.valueOf(willing)));

        if (!context.requiresRelocation() && context.workMode() != null
                && context.workMode().isRemote()) {
            return Optional.of(Resolution.derived(concept, yesNo(willing), Confidence.MEDIUM,
                    evidence,
                    "this role is remote, so relocation may not be what is being asked. "
                            + "Your profile says " + (willing ? "yes" : "no") + "."));
        }
        return Optional.of(Resolution.derived(concept, yesNo(willing), Confidence.HIGH, evidence,
                "your profile says you are " + (willing ? "" : "not ") + "willing to relocate"));
    }

    /**
     * The salary band for the employment country.
     *
     * <p>Keyed on the employment country rather than the posting's, for the same
     * reason as sponsorship: a euro figure on a role worked from Mumbai on an
     * Indian contract is a rounding error, and a rupee figure on a German form
     * reads as a joke.
     */
    private Optional<Resolution> salary(Concept concept, ApplicationContext context) {
        if (profile == null || profile.compensation() == null) {
            return Optional.empty();
        }
        String employment = context.employmentCountryCode();
        ApplicantProfile.Compensation.Band band = profile.compensation()
                .bandFor(CountryCodes.toLegacy(employment, context.strategicClass()));
        if (band == null) {
            return Optional.empty();
        }
        List<Evidence> evidence = new ArrayList<>(contextEvidence(context));
        evidence.add(Evidence.profile("compensation.bands", band.textAnswer()));
        // Names the band that was actually used, not the country that was asked
        // for. bandFor falls back to the home band when a country has none of its
        // own, and an explanation that said "the band configured for Canada" when
        // it had quietly used the Indian one would be the explanation lying.
        String where = employment == null
                ? "your home country, because the posting does not say where a remote "
                        + "employee may sit"
                : CountryCodes.displayName(employment);
        boolean ownBand = profile.compensation().bands() != null
                && profile.compensation().bands().containsKey(
                        CountryCodes.toLegacy(employment, context.strategicClass()));
        // A fallback band is a figure for the wrong economy, and salary is the
        // one field where a wrong number cannot be walked back: an INR
        // expectation typed into a US form anchors the negotiation an order of
        // magnitude low, and no later correction un-anchors it. So a borrowed
        // band is good enough to show him and never good enough to send
        // unread - MEDIUM keeps it out of isAutoFillable and in front of a
        // person.
        return Optional.of(Resolution.derived(concept, band.textAnswer(),
                ownBand && employment != null ? Confidence.HIGH : Confidence.MEDIUM,
                evidence,
                ownBand
                        ? "the " + band.currency() + " band configured for " + where
                        : "the " + band.currency() + " band, which is the fallback - no band "
                                + "is configured for " + where + ", so this figure is for "
                                + "the wrong economy and needs checking before it is sent"));
    }

    /**
     * The resume this preparation rendered.
     *
     * <p>Not knowledge about him - it is a file produced by the run - but it is
     * the answer to a question the form asks, and a resolver that cannot answer
     * it can never replace the mapper. Empty when no documents have been
     * prepared, which is the honest answer for a hypothetical application.
     */
    private Optional<Resolution> resume(Concept concept, ApplicationContext context) {
        if (context == null || context.resumePath() == null) {
            return Optional.empty();
        }
        return Optional.of(Resolution.derived(concept, context.resumePath(), Confidence.HIGH,
                List.of(Evidence.context("tailored for this posting", context.resumePath())),
                "the resume rendered for this application"));
    }

    /** The letter drafted for this posting, where the form has a box for one. */
    private Optional<Resolution> coverLetter(Concept concept, ApplicationContext context) {
        if (context == null || context.coverLetterText() == null
                || context.coverLetterText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Resolution.derived(concept, context.coverLetterText(),
                Confidence.MEDIUM,
                List.of(Evidence.context("drafted for this posting", "cover letter")),
                "drafted for this posting, and never sent without being read"));
    }

    /** "Are you able to work remotely?" - answerable from the work mode alone. */
    private Optional<Resolution> remoteWilling(Concept concept, ApplicationContext context) {
        if (profile == null || profile.availability() == null) {
            return Optional.empty();
        }
        boolean open = profile.availability().openToRemote();
        List<Evidence> evidence = new ArrayList<>(contextEvidence(context));
        evidence.add(Evidence.profile("availability.open-to-remote", String.valueOf(open)));
        return Optional.of(Resolution.derived(concept, yesNo(open), Confidence.HIGH, evidence,
                "your profile says you are " + (open ? "" : "not ") + "open to remote work"));
    }

    // ------------------------------------------------------------------

    /** The context fields a derivation read, named so the reasoning is checkable. */
    private static List<Evidence> contextEvidence(ApplicationContext context) {
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(Evidence.context("job country",
                context.countryCode() == null ? "not established"
                        : CountryCodes.displayName(context.countryCode())));
        String employment = context.employmentCountryCode();
        evidence.add(Evidence.context("employment country",
                employment == null ? "not stated by the posting"
                        : CountryCodes.displayName(employment)));
        if (context.workMode() != null) {
            evidence.add(Evidence.context("work mode",
                    context.workMode().name().toLowerCase(java.util.Locale.ROOT)
                            .replace('_', ' ')));
        }
        if (!context.remoteEligibleFrom().isEmpty()) {
            evidence.add(Evidence.context("remote employees may be in",
                    String.join(", ", context.remoteEligibleFrom())));
        }
        if (context.employerCountryCode() != null) {
            evidence.add(Evidence.context("employer country",
                    CountryCodes.displayName(context.employerCountryCode())));
        }
        if (!context.authorisedCountryCodes().isEmpty()) {
            evidence.add(Evidence.profile("work-authorisation.authorised-in",
                    String.join(", ", context.authorisedCountryCodes())));
        }
        return List.copyOf(evidence);
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    /** Whether the work mode says anything at all about where he may sit. */
    static boolean scopeStated(WorkMode mode, List<String> eligibleFrom) {
        return mode == WorkMode.REMOTE_GLOBAL || !eligibleFrom.isEmpty();
    }
}
