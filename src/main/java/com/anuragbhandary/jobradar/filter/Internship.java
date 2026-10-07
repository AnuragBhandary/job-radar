package com.anuragbhandary.jobradar.filter;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internships, which are wanted only when they can turn into a job.
 *
 * <p>Until 2026-09-28 every internship was rejected on its title. For someone
 * who has already graduated most of them are closed - they ask for a student who
 * is still enrolled - but an Indian internship that takes graduates and converts
 * to full time (a pre-placement offer) is a paid, verifiable first step at a real
 * employer.
 *
 * <p>So an internship title is let through, and then three facts decide it:
 * it must be in India or remote into India (an internship abroad needs a student
 * visa), and it must not require current enrolment. Whether it can convert is
 * reported, not required: most postings do not say.
 */
public final class Internship {

    private static final Pattern TITLE = Pattern.compile(
            "(?<![\\p{L}])(?:intern|internship|interns)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Wording that restricts a posting to people still studying. Calibrated on
     * the phrasings Indian and US boards use: enrolment, a graduation year still
     * to come, a batch, a final or penultimate year, returning to school.
     */
    private static final Pattern STUDENT_ONLY = Pattern.compile(
            "\\bcurrently\\s+(?:enrolled|pursuing|studying|a\\s+student)"
                    + "|\\bmust\\s+be\\s+(?:a\\s+)?(?:current(?:ly)?\\s+)?(?:enrolled|student)"
                    + "|\\benrolled\\s+(?:full[- ]time\\s+)?in\\s+(?:a|an)\\s+(?:full[- ]time\\s+)?(?:accredited\\s+)?"
                    + "(?:bachelor|master|degree|university|undergraduate|graduate|b\\.?tech|m\\.?tech)"
                    + "|\\bpursuing\\s+(?:a\\s+|an\\s+|your\\s+)?(?:bachelor|master|b\\.?tech|m\\.?tech|b\\.?e\\b"
                    + "|degree|undergraduate|graduate\\s+degree|ph\\.?d)"
                    // "a graduation date between December 2026 and June 2027" (Amex).
                    + "|\\b(?:expected\\s+)?graduat(?:e|ing|ion)\\s+(?:in|by|between|date|year)\\s*[:\\-]?\\s*"
                    + "(?:\\w+\\s+){0,2}20(?:2[6-9]|3\\d)"
                    + "|\\b20(?:2[6-9]|3\\d)\\s+(?:batch|graduates?|pass[- ]?outs?)\\b"
                    + "|\\bbatch\\s+(?:of\\s+)?20(?:2[6-9]|3\\d)\\b"
                    // Not "Enterprise Tech 30 Class of 2026", an award list.
                    + "|(?<!\\d\\s)\\bclass\\s+of\\s+20(?:2[6-9]|3\\d)\\b"
                    + "|\\b(?:final|pre[- ]final|penultimate)[- ]year\\s+(?:students?|of\\s+(?:study|your)"
                    + "|undergraduates?|postgraduates?|masters?|bachelor)"
                    + "|\\breturn(?:ing)?\\s+to\\s+(?:school|university|college|your\\s+studies)"
                    + "|\\bremaining\\s+(?:semester|term|year)s?\\s+(?:of|in|at)\\b"
                    // Stripe's Bangalore intern (2026-09-28): "through pursuit of a
                    // Bachelor's or Master's degree".
                    + "|\\bpursuit\\s+of\\s+(?:a|an|your)\\s+(?:bachelor|master|degree|undergraduate)"
                    // UK placements and graduate schemes (2026-10-07): G-Research's "A
                    // current undergraduate, master's or PhD student", Next's "must be
                    // studying a relevant University Degree course that includes a
                    // dedicated placement year", Cummins's "candidates who qualify for a
                    // placement year" and "Working towards a degree".
                    + "|\\ba\\s+current\\s+(?:undergraduate|postgraduate|master'?s|masters|ph\\.?d|bachelor'?s"
                    + "|university)\\b[^.]{0,40}\\bstudent"
                    + "|\\bmust\\s+be\\s+(?:currently\\s+)?studying"
                    + "|\\bcurrent(?:ly)?\\s+stud(?:y|ying)\\s+towards"
                    + "|\\bworking\\s+towards\\s+(?:a|an|your)\\s+(?:\\w+\\s+){0,2}(?:degree|bachelor|master)"
                    + "|\\bqualify\\s+for\\s+a\\s+placement\\s+year"
                    + "|\\b(?:includes?|including)\\s+a\\s+(?:dedicated\\s+|\\d{1,2}[- ]month\\s+)?placement(?:\\s+year)?"
                    + "|\\b(?:second|2nd|third|3rd)\\s+year\\s+of\\s+(?:university|your\\s+degree|your\\s+studies)",
            Pattern.CASE_INSENSITIVE);

    /** "Currently pursuing or recently completed": open to a graduate after all. */
    private static final Pattern GRADUATES_WELCOME = Pattern.compile(
            "\\b(?:recently\\s+(?:completed|graduated)|recent\\s+graduates?|have\\s+graduated"
                    + "|or\\s+(?:have\\s+)?completed|graduated\\s+(?:in|within)|freshers?\\s+(?:and|or)"
                    + "|open\\s+to\\s+(?:recent\\s+)?graduates)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CONVERSION = Pattern.compile(
            "\\bpre[- ]?placement\\s+offer|\\bPPOs?\\b"
                    + "|\\bconver(?:t|ts|ted|sion)\\s+(?:in)?to\\s+(?:a\\s+)?full[- ]time"
                    + "|\\bfull[- ]time\\s+(?:offer|role|position|opportunit(?:y|ies)|employment)"
                    + "\\s+(?:upon|after|on|based\\s+on|at\\s+the\\s+end)"
                    + "|\\bpotential\\s+(?:for|to)\\s+(?:a\\s+)?full[- ]time"
                    + "|\\breturn\\s+offer",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DURATION = Pattern.compile(
            "\\b(\\d{1,2})\\s*(?:[-–]\\s*\\d{1,2}\\s*)?[- ]?(months?|weeks?)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * A graduate programme or new-grad role: open to a recent graduate by
     * definition, and often only to this year's.
     */
    private static final Pattern GRADUATE_PROGRAMME = Pattern.compile(
            "(?<![\\p{L}])(?:graduate|grad|new[- ]?grads?|campus|early[- ]careers?|apprentice\\w*"
                    + "|placement|class\\s+of|20(?:2[6-9]|3\\d))(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Only this year's graduates. Deliveroo's new-grad role (2026-10-01) asked for
     * a degree "graduated within the last year", which the graduates-welcome
     * wording below would otherwise read as an opening.
     */
    private static final Pattern RECENT_ONLY = Pattern.compile(
            "\\bgraduat(?:ed|ion)\\s+(?:with)?in\\s+the\\s+(?:last|past)\\s+(?:year|12\\s+months|twelve\\s+months)",
            Pattern.CASE_INSENSITIVE);

    private Internship() {
    }

    public static boolean isGraduateProgramme(String title) {
        return title != null && GRADUATE_PROGRAMME.matcher(title).find();
    }

    public static boolean isInternship(String title) {
        return title != null && TITLE.matcher(title).find();
    }

    /** The phrase that limits the posting to students, or empty. */
    static Optional<String> studentOnly(String description) {
        if (description == null) {
            return Optional.empty();
        }
        Matcher recent = RECENT_ONLY.matcher(description);
        if (recent.find()) {
            return Optional.of(phrase(description, recent.start(), recent.end()));
        }
        Matcher m = STUDENT_ONLY.matcher(description);
        while (m.find()) {
            // The clause around the match, which can open it back up to graduates.
            int from = Math.max(0, m.start() - 80);
            int to = Math.min(description.length(), m.end() + 80);
            if (GRADUATES_WELCOME.matcher(description.substring(from, to)).find()) {
                continue;
            }
            return Optional.of(phrase(description, m.start(), m.end()));
        }
        return Optional.empty();
    }

    /** Wording that says the internship can become a job, or empty. */
    public static Optional<String> conversion(String description) {
        if (description == null) {
            return Optional.empty();
        }
        Matcher m = CONVERSION.matcher(description);
        return m.find() ? Optional.of(phrase(description, m.start(), m.end())) : Optional.empty();
    }

    /** The first stated length in months or weeks ("6 months"), or empty. */
    public static Optional<String> duration(String description) {
        if (description == null) {
            return Optional.empty();
        }
        Matcher m = DURATION.matcher(description);
        while (m.find()) {
            String after = description.substring(m.end(), Math.min(description.length(), m.end() + 25))
                    .toLowerCase(Locale.ROOT);
            // "6 months of experience" is a requirement, not a length.
            if (after.contains("experience")) {
                continue;
            }
            return Optional.of(m.group().replaceAll("\\s+", " ").strip());
        }
        return Optional.empty();
    }

    private static String phrase(String text, int start, int end) {
        int from = Math.max(0, start - 30);
        int to = Math.min(text.length(), end + 30);
        return text.substring(from, to).replaceAll("\\s+", " ").strip();
    }
}
