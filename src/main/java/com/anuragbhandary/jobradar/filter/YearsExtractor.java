package com.anuragbhandary.jobradar.filter;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads the required years of experience out of a job description.
 *
 * <p>The rule is: find every plausible statement of a number of years in the
 * <em>required</em> part of the description, and take the smallest. Taking the
 * smallest is deliberately generous - a posting saying "1-3 years" should read
 * onto a year of internships - and it was checked against the real corpus before
 * being trusted: of 3,997 postings that mention a number of years, only 21 (0.5%)
 * contain both a number below 2 and a number of 5 or more, which is the case
 * where a stray small number could turn a genuine rejection into an acceptance.
 * Every one of those 21 was a Program Manager, Director or Team Lead title that
 * the title filter rejects first.
 *
 * <p>The patterns below come from the same corpus rather than from imagination.
 * All of these occur in real postings and all must parse to the same minimum:
 * {@code 3+ years}, {@code 5-8 years}, {@code 3–4 years}, {@code 4 to 8 years},
 * {@code 2-12+ years}, {@code 6 - 10 years}, {@code 5 + years}, {@code 3+ yrs},
 * {@code 0 to 2+ years' experience}.
 */
@Component
public class YearsExtractor {

    /**
     * Above this, the number is not an entry requirement - it is company history
     * ("the first database provider to IPO in over 20 years") or a benefit.
     */
    private static final int IMPLAUSIBLE_YEARS = 15;

    /**
     * A leading number, an optional range, and a years unit.
     *
     * <p>Group 1 is the lower bound, which is the one that matters. The optional
     * group after it swallows the upper bound of a range so that "5-8 years"
     * yields 5 rather than also matching 8 separately.
     */
    private static final Pattern YEARS = Pattern.compile(
            "(\\d{1,2})\\s*\\+?\\s*"
                    + "(?:(?:[-–—]|to)\\s*\\d{1,2}\\s*\\+?\\s*)?"
                    + "(years?|yrs?)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Amazon's phrasing, and disqualifying whatever else the posting says.
     * Internship time is the entirety of the experience being screened for, so a
     * posting that excludes it excludes this candidate no matter what number it
     * quotes - or whether it quotes one at all.
     *
     * <p>Three real phrasings have to match, and only one of them carries a
     * number:
     * <pre>
     *   1+ years of non-internship professional software development experience
     *   Experience (non-internship) in professional software development
     *   Experience in professional, non-internship software development
     * </pre>
     * The second and third are Amazon's SDE II wording and used to escape
     * entirely, because the old pattern required {@code \d+ years of} in front.
     * That single gap put 26 mid-level AWS roles - Firecracker, Shield, RDS
     * Platform - into the candidate list, 30% of everything it contained.
     *
     * <p>So the number is gone from the pattern and proximity replaces it: the
     * phrase counts only when it sits within a clause of "professional" or
     * "experience". Matching the bare token everywhere would be simpler and
     * wrong - "internship and non-internship candidates welcome" is an invitation,
     * not a requirement, and reads as the exact opposite of what it is.
     */
    private static final Pattern NON_INTERNSHIP = Pattern.compile(
            "(?:professional|experience)[^.]{0,40}?non-?\\s?internship"
                    + "|non-?\\s?internship[^.]{0,40}?(?:professional|experience)"
                    // "2+ years of full-time software engineering experience",
                    // "experience (full-time)". The word right before
                    // "experience" is the test, so "a full-time role; experience
                    // with Kafka" does not count.
                    + "|full[-\\s]?time\\s+(?:professional\\s+|industry\\s+|work\\s+|software\\s+"
                    + "|engineering\\s+|development\\s+|paid\\s+){0,3}experience"
                    + "|experience\\s*\\(full[-\\s]?time\\)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Where the requirements stop and the wishlist starts.
     *
     * <p>Everything after this heading is what the employer would <em>like</em>,
     * and its numbers must not be read as the bar. Amazon's Network Dev Engineer
     * I in Bengaluru required "2+ years of IT Security experience" and preferred
     * "1+ years of automation scripting" - so taking the smallest number in the
     * whole document read the requirement as 1, cleared the entry-level gate and
     * shipped a two-year role as a candidate.
     *
     * <p>This is a different failure from the one measured above: not a small
     * number against a large one, but a preference against a requirement. Four
     * candidates were affected.
     */
    private static final Pattern PREFERRED_SECTION = Pattern.compile(
            "\\b(?:preferred\\s+qualifications|preferred\\s+skills|preferred\\s*:"
                    + "|nice[\\s-]to[\\s-]have|bonus\\s+points|good\\s+to\\s+have"
                    + "|desired\\s+qualifications)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Where the requirements <em>start</em>, when the posting says so.
     *
     * <p>Anchoring on this matters more than it looks. Stripe's descriptions
     * invite you to apply "even if you don't meet all the preferred
     * qualifications" - in prose, 57 characters before the real "Minimum
     * requirements" heading. Cutting at the first preferred marker therefore
     * discarded the requirements section entirely and read those postings as
     * stating nothing, which turned 6-, 8- and 10-year roles into candidates.
     *
     * <p>So the last of these headings wins - prose mentions come before the
     * heading, not after it - and the wishlist is then looked for beyond it.
     */
    private static final Pattern REQUIRED_SECTION = Pattern.compile(
            "\\b(?:minimum\\s+requirements|minimum\\s+qualifications"
                    + "|basic\\s+qualifications|required\\s+qualifications"
                    + "|what\\s+you.{0,3}ll\\s+need|requirements\\s*:)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Below this, a split is assumed to be spurious rather than structural.
     * A description opening on "Preferred Qualifications" has not told us what is
     * required; it has been formatted in a way this does not understand, and
     * reading the whole thing is the safer failure.
     *
     * <p>Kept small because Amazon's bullet lists are terse: posting 8377's two
     * required lines run to 96 characters before "Preferred:" arrives.
     */
    private static final int MIN_REQUIRED_LENGTH = 30;

    /** A programme or contract word straight after a number: a duration, not a bar. */
    private static final Pattern DURATION_AFTER = Pattern.compile(
            "^\\W{0,3}(?:program|programme|rotation|rotational|contract|fixed|term)",
            Pattern.CASE_INSENSITIVE);

    /**
     * A years requirement stated in the title, as in PhonePe's "Site Reliability
     * Engineer - AWS (4 to 8 Years)", whose description never repeats it.
     *
     * <p>Narrower than {@link #extract}, because a false positive here rejects a
     * whole role on thirty characters of evidence. Only a plural or range form
     * counts: a singular "2 Year" in a title is a duration ("Software Engineer,
     * 2 Year Rotational Programme"), and a number followed by a programme or
     * contract word is never read as a requirement.
     */
    public YearsExtraction extractFromTitle(String title) {
        if (title == null || title.isBlank()) {
            return YearsExtraction.none();
        }
        int min = YearsExtraction.NONE_STATED;
        Matcher m = YEARS.matcher(title);
        while (m.find()) {
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            if (unit.equals("year") || unit.equals("yr")) {
                continue;
            }
            String after = title.substring(m.end(), Math.min(title.length(), m.end() + 20));
            if (DURATION_AFTER.matcher(after).find()) {
                continue;
            }
            int years = Integer.parseInt(m.group(1));
            if (years < IMPLAUSIBLE_YEARS && (min == YearsExtraction.NONE_STATED || years < min)) {
                min = years;
            }
        }
        return min == YearsExtraction.NONE_STATED ? YearsExtraction.none()
                : new YearsExtraction(min, title.replaceAll("\\s+", " ").strip(), null);
    }

    public YearsExtraction extract(String description) {
        if (description == null || description.isBlank()) {
            return YearsExtraction.none();
        }

        // Searched across the whole description: Amazon states it in the basic
        // qualifications, but a posting that excludes internship experience
        // anywhere has excluded it.
        String nonInternship = null;
        Matcher exclusion = NON_INTERNSHIP.matcher(description);
        if (exclusion.find()) {
            nonInternship = phrase(description, exclusion.start(), exclusion.end());
        }

        String required = requiredSection(description);

        // Two minimums: over every number, and over the numbers nobody hedged. A
        // hedge may stop a smaller number undercutting a stated requirement, but it
        // never makes a posting read as stating nothing - Philips puts its only
        // requirement behind "Preferably 6+ years", and skipping that turned a
        // six-year role into a candidate.
        int min = YearsExtraction.NONE_STATED;
        String evidence = null;
        int plainMin = YearsExtraction.NONE_STATED;
        String plainEvidence = null;

        Matcher m = YEARS.matcher(required);
        while (m.find()) {
            if (isProgrammeDuration(required, m) || isDegreeAlternative(required, m)
                    || isUpperBound(required, m)) {
                continue;
            }
            int years = Integer.parseInt(m.group(1));
            if (years >= IMPLAUSIBLE_YEARS) {
                continue;
            }
            String here = phrase(required, m.start(), m.end());
            if (min == YearsExtraction.NONE_STATED || years < min) {
                min = years;
                evidence = here;
            }
            if (!isOptional(required, m)
                    && (plainMin == YearsExtraction.NONE_STATED || years < plainMin)) {
                plainMin = years;
                plainEvidence = here;
            }
        }
        // The qualification line, wherever it sits. Bosch writes it last, after its
        // "Good to Have" heading, so requiredSection() cut it off entirely: "B.E 3
        // to 6 years experience" and "BE, ME - Electronics background 4 to 7 years"
        // both read as stating nothing, and a three- and a four-year role reached
        // the digest as entry level.
        Matcher tail = YEARS.matcher(description);
        while (tail.find()) {
            if (isProgrammeDuration(description, tail) || isOptional(description, tail)
                    || isDegreeAlternative(description, tail) || isUpperBound(description, tail)
                    || !isQualification(description, tail)) {
                continue;
            }
            int years = Integer.parseInt(tail.group(1));
            if (years >= IMPLAUSIBLE_YEARS) {
                continue;
            }
            if (plainMin == YearsExtraction.NONE_STATED || years < plainMin) {
                plainMin = years;
                plainEvidence = phrase(description, tail.start(), tail.end());
            }
        }

        return plainMin != YearsExtraction.NONE_STATED
                ? new YearsExtraction(plainMin, plainEvidence, nonInternship)
                : new YearsExtraction(min, evidence, nonInternship);
    }

    /**
     * The part of the description that states what is actually required.
     *
     * <p>Two shapes, because boards use both. When the posting names its
     * requirements section, the span runs from the last such heading to the first
     * wishlist heading after it. When it does not - Amazon just opens with
     * bullets - everything before the first wishlist heading is taken instead.
     */
    private static String requiredSection(String description) {
        int start = 0;
        Matcher required = REQUIRED_SECTION.matcher(description);
        while (required.find()) {
            start = required.start();
        }

        Matcher preferred = PREFERRED_SECTION.matcher(description);
        int end = description.length();
        if (preferred.find(start)) {
            end = preferred.start();
        }

        // A span too short to hold a requirement means the headings were not
        // where this expected them. Reading the whole description is the safer
        // failure: it can only make the filter stricter, never more permissive.
        return end - start >= MIN_REQUIRED_LENGTH
                ? description.substring(start, end)
                : description;
    }

    /**
     * Distinguishes "a 2-year development programme" from "2 years of experience".
     *
     * <p>A hyphen directly before a singular "year" makes it an adjective
     * describing how long something lasts, not a requirement. This matters more
     * than its rarity suggests: Celonis advertises a graduate fast-track as a
     * "2-year development programme", and reading that as two years of required
     * experience would reject precisely the kind of role being searched for.
     */
    private static boolean isProgrammeDuration(String text, Matcher m) {
        if (!m.group(2).equalsIgnoreCase("year")) {
            return false;
        }
        // Look at the character immediately before the unit word.
        int unitStart = text.lastIndexOf(m.group(2), m.end());
        return unitStart > 0 && text.charAt(unitStart - 1) == '-';
    }

    /**
     * A hedge word shortly before a number, with no colon between them.
     *
     * <p>The colon matters: "Ideally you'd have: 4+ years" is Scale AI's heading for
     * its requirements, and "Preferred qualifications: 5+ years" opens a section.
     * A hedge that introduces a list is a heading, not a softener of the number.
     */
    private static final Pattern OPTIONAL_BEFORE = Pattern.compile(
            "\\b(?:optionally|optional|ideally|preferably|preferred|bonus|a plus|nice to have)\\b"
                    + "[^.;:\\n]{0,25}$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Distinguishes "optionally 1-2 years of Product Owner exposure" from the bar.
     *
     * <p>Taking the smallest number is only generous when every number is a
     * requirement. Bosch's "SW developer :Java" (2026-09) required "3-5 years of
     * professional experience in Java" and then, in the same paragraph with no
     * heading between, "optionally 1-2 years of experience ... as Product Owner".
     * The optional 1 won, and a three-year role was recommended as entry level.
     * A hedge placed in front of the number is the only signal there is, so this
     * reads the few words before it.
     */
    private static boolean isOptional(String text, Matcher m) {
        String before = text.substring(Math.max(0, m.start() - 40), m.start());
        return OPTIONAL_BEFORE.matcher(before).find();
    }

    /**
     * The qualification line's own vocabulary: a degree, a named background, or an
     * explicit count of years required.
     *
     * <p>Deliberately not "N years of experience", which is how every wishlist line
     * is written too: "NICE TO HAVES: - 2-4 years of experience as a Software
     * Engineer" is not a bar, and anchoring on the generic phrase rejected a real
     * candidate on it.
     */
    private static final Pattern QUALIFICATION_BEFORE = Pattern.compile(
            "\\b(?:b\\.?e|b\\.?tech|m\\.?e|m\\.?tech|b\\.?sc|m\\.?sc|bachelor'?s?|master'?s?"
                    + "|background|no\\.?\\s*of\\s+years\\s+of\\s+experience(?:\\s+required)?)\\b"
                    + "[^.;\\n]{0,30}$",
            Pattern.CASE_INSENSITIVE);

    /**
     * An alternative to a degree, not a requirement in its own right.
     *
     * <p>"Bachelor's degree, or 4+ years of equivalent experience" is Amazon's and
     * Google's standard phrasing and means the opposite of a four-year bar for
     * somebody who has the degree. Without this guard the rule below would reject
     * exactly the entry-level roles it exists to protect.
     */
    private static final Pattern ALTERNATIVE_TO_A_DEGREE =
            Pattern.compile("\\bor\\b[^.;\\n]{0,20}$", Pattern.CASE_INSENSITIVE);

    /** A ceiling rather than a floor: "up to 3 years", "at most 2 years". */
    private static final Pattern UPPER_BOUND_BEFORE = Pattern.compile(
            "\\b(?:up\\s*to|upto|maximum(?:\\s+of)?|max\\.?|at\\s+most|less\\s+than"
                    + "|fewer\\s+than|under|no\\s+more\\s+than|not\\s+more\\s+than|within)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * A number that caps something rather than requiring it.
     *
     * <p>Two real misses, both from 2026-09-19. HackerRank's L1 role asks for "an
     * early-career engineer with up to 3 years of relevant experience. Strong
     * freshers..." and was rejected as a three-year role. And a privacy footer,
     * "Your data is kept for up to 2 years in our candidate pool", rejected three
     * data and SRE roles in Germany and France as two-year roles.
     */
    private static boolean isUpperBound(String text, Matcher m) {
        String before = text.substring(Math.max(0, m.start() - 25), m.start())
                .replaceAll("[\\r\\n\\t\\u00a0]+", " ");
        return UPPER_BOUND_BEFORE.matcher(before).find();
    }

    /** A degree, then "or", then the number: "Bachelor's degree, or 4+ years". */
    private static final Pattern DEGREE_THEN_OR = Pattern.compile(
            "\\b(?:degree|diploma)\\b[^.;\\n]{0,40}\\bor\\s+(?:a\\s+)?"
                    + "(?:minimum\\s+(?:of\\s+)?|at\\s+least\\s+)?$",
            Pattern.CASE_INSENSITIVE);

    /** "3 years with a Master's degree": years that come with a degree, not instead of one. */
    private static final Pattern WITH_A_DEGREE_AFTER = Pattern.compile(
            "^\\s+(?:of\\s+\\w+\\s+)?with\\s+(?:a\\s+|an\\s+)?"
                    + "(?:master|ph\\.?d|doctorate|bachelor|m\\.?sc?\\b|b\\.?sc?\\b)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Years offered as a substitute for a degree, which is no bar to someone who
     * has the degree.
     *
     * <p>"Bachelor's degree in Computer Science, or 4+ years of equivalent practical
     * experience" asks for one or the other. Reading the 4 as a requirement rejects
     * an entry-level role. The shape it must not catch is "5 years with a
     * Bachelor's degree, or 3 years with a Master's degree", where each number
     * comes with its own degree and is a real bar.
     */
    private static boolean isDegreeAlternative(String text, Matcher m) {
        String before = text.substring(Math.max(0, m.start() - 70), m.start())
                .replaceAll("[\\r\\n\\t\\u00a0]+", " ");
        if (!DEGREE_THEN_OR.matcher(before).find()) {
            return false;
        }
        String after = text.substring(m.end(), Math.min(text.length(), m.end() + 40));
        return !WITH_A_DEGREE_AFTER.matcher(after).find();
    }

    /**
     * Whether a number states a qualification, wherever in the document it sits.
     *
     * <p>Anchored on the words around it rather than on a section heading, because
     * the postings this exists for have no requirements heading at all and put the
     * line after the wishlist.
     */
    private static boolean isQualification(String text, Matcher m) {
        // Newlines flattened first: boards render the qualification as a list, so
        // "B.E" and its years sit on separate lines, and an anchor that treated a
        // line break as the end of the clause never fired on a real posting.
        String before = text.substring(Math.max(0, m.start() - 40), m.start())
                .replaceAll("[\\r\\n\\t\\u00a0]+", " ");
        if (ALTERNATIVE_TO_A_DEGREE.matcher(before).find()) {
            return false;
        }
        return QUALIFICATION_BEFORE.matcher(before).find();
    }

    /** A little surrounding text, so the reject reason reads like the posting. */
    private static String phrase(String text, int start, int end) {
        int from = Math.max(0, start - 30);
        int to = Math.min(text.length(), end + 40);
        return text.substring(from, to).replaceAll("\\s+", " ").trim();
    }
}
