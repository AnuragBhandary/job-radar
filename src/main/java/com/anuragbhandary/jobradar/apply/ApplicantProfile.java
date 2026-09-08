package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.domain.Country;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who the applicant is, in the shape a job application form asks for it.
 *
 * <p>Deliberately its own {@code @ConfigurationProperties} root rather than another
 * nested record inside {@link com.anuragbhandary.jobradar.config.AppProperties}, for
 * one reason that matters more than tidiness: <strong>this file is the only
 * personal data in the project, and it must never be committed.</strong> Screening
 * rules are interesting to a reader of the repository; a home address and an EEO
 * self-identification are not. Keeping the two in separate files means the
 * personal one can be a gitignored import from {@code ~/.config/job-radar/} while
 * {@code application.yml} stays public. See {@code applicant.example.yml}.
 *
 * <p>Every field here exists because a real form asks for it. Nothing is stored
 * "just in case": each addition is one more thing to leak.
 */
@ConfigurationProperties(prefix = "job-radar.applicant")
public record ApplicantProfile(
        Name name,
        Contact contact,
        Address address,
        WorkAuthorisation workAuthorisation,
        Demographics demographics,
        Compensation compensation,
        Availability availability,
        List<ExtraAnswer> extraAnswers,
        List<String> assistantBriefing) {

    /**
     * What the assistant is told about the applicant before it says anything.
     *
     * <p>Here rather than in the assistant's own source for the reason this whole
     * record exists: the repository is public and this file is not. The facts that
     * make the advice worth having - what a year of experience is actually worth,
     * what a salary has to clear - are exactly the facts nobody should be able to
     * read on GitHub.
     *
     * <p>Empty is fine. The assistant still has {@code profile_summary}, and will
     * simply know less.
     */
    public List<String> assistantBriefing() {
        return assistantBriefing == null ? List.of() : assistantBriefing;
    }

    public record Name(String first, String middle, String last, String preferred) {

        public String full() {
            StringBuilder sb = new StringBuilder(first);
            if (middle != null && !middle.isBlank()) {
                sb.append(' ').append(middle);
            }
            return sb.append(' ').append(last).toString();
        }

        /** What most forms mean by "Full name" - they rarely want the middle name. */
        public String display() {
            return (preferred == null || preferred.isBlank() ? first : preferred) + " " + last;
        }
    }

    /**
     * @param phoneCountryCode kept apart from the number because forms split them
     *                         about half the time, and a "+91" typed into a field
     *                         that already has a country selector fails validation
     *                         without saying why
     */
    public record Contact(
            String email,
            String phoneCountryCode,
            String phoneNumber,
            String linkedin,
            String github,
            String portfolio,
            String leetcode) {

        /** E.164, for the fields that want one box. */
        public String phoneE164() {
            return phoneCountryCode + phoneNumber.replaceAll("\\s+", "");
        }
    }

    public record Address(
            String line1,
            String line2,
            String city,
            String state,
            String postalCode,
            String country,
            String nationality) {
    }

    /**
     * The two questions every ATS asks, and the reason this class is country-aware.
     *
     * <p>They are asked in opposite polarity and are constantly confused:
     * <ul>
     *   <li>"Are you legally authorised to work in X?" - India: <em>yes</em>.
     *       Anywhere else: <em>no</em>.</li>
     *   <li>"Will you now or in the future require sponsorship?" - India:
     *       <em>no</em>. Anywhere else: <em>yes</em>.</li>
     * </ul>
     *
     * <p>Answering either one with a fixed value gets one of them wrong for every
     * posting, and both of them are auto-reject triggers on most ATSs. So neither
     * is stored as a boolean; both are derived from the posting's country by
     * {@link com.anuragbhandary.jobradar.apply.form.FieldMapper}.
     *
     * @param authorisedIn         countries where no permit is needed
     * @param willRelocate         answer to "are you willing to relocate?"
     * @param needsSponsorshipNote free text for the forms that give a text box
     *                             instead of a yes/no
     */
    public record WorkAuthorisation(
            List<Country> authorisedIn,
            boolean willRelocate,
            String needsSponsorshipNote) {

        public boolean isAuthorisedIn(Country country) {
            return country != null && authorisedIn != null && authorisedIn.contains(country);
        }
    }

    /**
     * Voluntary EEO/diversity self-identification.
     *
     * <p>Every one of these is optional on every form that asks it, and the tool
     * treats a missing value as "decline to answer" rather than guessing. A blank
     * here is a legitimate final answer, not an incomplete profile.
     */
    public record Demographics(
            String gender,
            String race,
            String hispanicOrLatino,
            String veteranStatus,
            String disabilityStatus,
            String pronouns) {
    }

    /**
     * What to say when a form demands a number.
     *
     * <p>Per-country because the answer is not one figure: an INR expectation
     * pasted into a German form reads as a rounding error, and a EUR figure in an
     * Indian form reads as a joke. Keyed by {@link Country} with a currency and a
     * band, and {@code preferNotToSay} is used wherever the field is free text -
     * naming a number first is a negotiating loss, so the tool only does it when
     * the form will not submit otherwise.
     */
    public record Compensation(
            Map<Country, Band> bands,
            String preferNotToSay,
            boolean alwaysStateNumber) {

        public record Band(String currency, BigDecimal minimum, BigDecimal target, String period) {

            /** "1200000" - for the numeric fields that reject anything else. */
            public String numericAnswer() {
                return target.stripTrailingZeros().toPlainString();
            }

            /** "INR 12,00,000 - 18,00,000 per year" - for the text fields. */
            public String textAnswer() {
                return currency + " " + format(minimum) + " - " + format(target) + " " + period;
            }

            /**
             * Grouped thousands, pinned to {@link java.util.Locale#ROOT}.
             *
             * <p>Not the default locale: on a machine set to en-IN the same call
             * produces "12,00,000" and on en-GB "1,200,000". Both are legible to
             * a person, but the figure ends up in a database and in front of a
             * recruiter who may be in either place, and a number that changes
             * shape depending on where the tool ran is a number nobody can grep
             * for. It also made a test pass or fail by machine.
             */
            private static String format(BigDecimal value) {
                return String.format(java.util.Locale.ROOT, "%,d", value.longValue());
            }
        }

        public Band bandFor(Country country) {
            if (bands == null) {
                return null;
            }
            Band band = bands.get(country);
            // REMOTE postings are worked from Mumbai, so they are paid on the
            // Indian band unless one is set explicitly.
            return band != null ? band : bands.get(Country.INDIA);
        }
    }

    /**
     * @param noticePeriod  what to answer for "notice period", verbatim
     * @param earliestStart "Immediately" reads as unemployed on some forms and as
     *                      eager on others; it is configurable for that reason
     */
    public record Availability(String noticePeriod, String earliestStart, boolean openToRemote) {
    }

    /**
     * One configured answer, matched by a substring of the question.
     *
     * <p>A list of pairs rather than a {@code Map<String, String>}, and that is not
     * a style choice. Spring's relaxed binding canonicalises map keys, so a YAML
     * key containing a space or a slash - which every real question does - arrives
     * mangled or not at all unless it is written in bracket notation. The
     * behaviour is silent: the map binds, it is simply empty of the entries that
     * matter, and the escape hatch looks like it is working while answering
     * nothing. A list of records binds every character as written.
     *
     * @param match  a lowercased substring of the question as the form words it
     * @param answer what to put in the field, or the option to select
     */
    public record ExtraAnswer(String match, String answer) {
    }

    /**
     * Answers to questions this tool has met before and has no field for.
     *
     * <p>The escape hatch that stops every new company-specific question ("How did
     * you hear about us?", "Do you have a valid passport?") from needing a code
     * change - and the reason an unmatched question is reported rather than
     * guessed at. First match wins, so more specific entries belong higher.
     */
    public List<ExtraAnswer> extraAnswers() {
        return extraAnswers == null ? List.of() : extraAnswers;
    }
}
