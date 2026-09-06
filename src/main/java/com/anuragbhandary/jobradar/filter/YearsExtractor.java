package com.anuragbhandary.jobradar.filter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads the required years of experience out of a job description.
 *
 * <p>The rule is: find every plausible statement of a number of years, and take
 * the smallest. Taking the smallest is deliberately generous - a posting saying
 * "1-3 years" should read onto a year of internships - and it was checked against
 * the real corpus before being trusted: of 3,997 postings that mention a number
 * of years, only 21 (0.5%) contain both a number below 2 and a number of 5 or
 * more, which is the case where a stray small number could turn a genuine
 * rejection into an acceptance. Every one of those 21 was a Program Manager,
 * Director or Team Lead title that the title filter rejects first.
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
     * "1+ years of non-internship professional software development experience"
     * excludes internship time explicitly, which is the entirety of the
     * experience being screened for.
     */
    private static final Pattern NON_INTERNSHIP = Pattern.compile(
            "(\\d{1,2})\\s*\\+?\\s*years?\\s+of\\s+non-?\\s?internship",
            Pattern.CASE_INSENSITIVE);

    public YearsExtraction extract(String description) {
        if (description == null || description.isBlank()) {
            return YearsExtraction.none();
        }

        String nonInternship = null;
        Matcher amazon = NON_INTERNSHIP.matcher(description);
        if (amazon.find() && Integer.parseInt(amazon.group(1)) >= 1) {
            nonInternship = phrase(description, amazon.start(), amazon.end());
        }

        int min = YearsExtraction.NONE_STATED;
        String evidence = null;

        Matcher m = YEARS.matcher(description);
        while (m.find()) {
            if (isProgrammeDuration(description, m)) {
                continue;
            }
            int years = Integer.parseInt(m.group(1));
            if (years >= IMPLAUSIBLE_YEARS) {
                continue;
            }
            if (min == YearsExtraction.NONE_STATED || years < min) {
                min = years;
                evidence = phrase(description, m.start(), m.end());
            }
        }
        return new YearsExtraction(min, evidence, nonInternship);
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
