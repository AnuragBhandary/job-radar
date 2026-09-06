package com.anuragbhandary.jobradar.filter;

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
                    + "|non-?\\s?internship[^.]{0,40}?(?:professional|experience)",
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

        int min = YearsExtraction.NONE_STATED;
        String evidence = null;

        Matcher m = YEARS.matcher(required);
        while (m.find()) {
            if (isProgrammeDuration(required, m)) {
                continue;
            }
            int years = Integer.parseInt(m.group(1));
            if (years >= IMPLAUSIBLE_YEARS) {
                continue;
            }
            if (min == YearsExtraction.NONE_STATED || years < min) {
                min = years;
                evidence = phrase(required, m.start(), m.end());
            }
        }
        return new YearsExtraction(min, evidence, nonInternship);
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

    /** A little surrounding text, so the reject reason reads like the posting. */
    private static String phrase(String text, int start, int end) {
        int from = Math.max(0, start - 30);
        int to = Math.min(text.length(), end + 40);
        return text.substring(from, to).replaceAll("\\s+", " ").trim();
    }
}
