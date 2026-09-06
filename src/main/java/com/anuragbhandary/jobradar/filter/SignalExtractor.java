package com.anuragbhandary.jobradar.filter;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Pulls the two facts a posting rarely states in its structured fields and that
 * decide more than the years requirement does: whether the employer will sponsor
 * a visa, and what the job actually pays.
 *
 * <p>Both are reported, never used to reject. Absence means nothing here - most
 * postings say neither - so a filter built on them would discard the whole board.
 * Measured on the corpus before being built: of 1,428 postings in the target
 * geographies, 55 mention sponsorship, 218 mention relocation and 306 state a
 * figure. That is a fifth of the list carrying information the digest was
 * throwing away.
 */
@Component
public class SignalExtractor {

    /**
     * Phrases that mean "not you", in rough order of how final they are.
     *
     * <p>These matter more than the encouraging ones. A posting that says it will
     * sponsor still might not; a posting that says it will not has answered.
     */
    private static final List<Pattern> BLOCKING = List.of(
            compile("(?:not|unable to|cannot|can't|do not|does not|won't|will not)"
                    + "\\s+(?:be\\s+able\\s+to\\s+)?(?:offer\\s+|provide\\s+)?sponsor"),
            compile("no\\s+(?:visa\\s+)?sponsorship"),
            compile("without\\s+(?:visa\\s+)?sponsorship"),
            compile("sponsorship\\s+is\\s+not\\s+(?:available|offered|provided)"),
            compile("must\\s+(?:already\\s+)?(?:have|hold|possess)[^.]{0,40}"
                    + "(?:right\\s+to\\s+work|work\\s+authoris?z?ation|work\\s+permit)"),
            compile("(?:existing|current|valid)\\s+right\\s+to\\s+work"),
            compile("security\\s+clearance"),
            compile("(?:must\\s+be|require[sd]?)[^.]{0,30}citizen"));

    /** Phrases that mean the employer has at least thought about it. */
    private static final List<Pattern> SUPPORTIVE = List.of(
            compile("(?:visa|immigration)\\s+sponsorship"),
            compile("(?:we|will|can|happy\\s+to)\\s+sponsor"),
            compile("sponsorship\\s+(?:is\\s+)?(?:available|provided|offered)"),
            compile("relocation\\s+(?:package|support|assistance|allowance|bonus)"),
            compile("help\\s+(?:you\\s+)?relocat"));

    /**
     * A stated figure, with enough of its sentence to be read by a human.
     *
     * <p>No number is parsed out and no comparison against the salary floors is
     * attempted. Ranges, periods, currencies, equity and "up to" all appear, and
     * a floor comparison that is wrong once is worse than none - it would be
     * quoted back later as though it came from the posting. The floor is already
     * printed beside this; a person can do the subtraction knowing what both
     * numbers are.
     */
    private static final Pattern SALARY = Pattern.compile(
            "(?:€|EUR\\b|£|GBP\\b|₹|INR\\b|\\bRs\\.?\\b)\\s*"
                    + "\\d{1,3}(?:[.,\\s]\\d{3})*(?:[.,]\\d{2})?\\s*(?:k\\b)?"
                    + "(?:\\s*(?:[-–—]|to)\\s*(?:€|EUR|£|₹|INR|Rs\\.?)?\\s*"
                    + "\\d{1,3}(?:[.,\\s]\\d{3})*(?:\\s*k\\b)?)?"
                    + "|\\b\\d{1,3}(?:\\.\\d+)?\\s*(?:lpa|lakhs?|lacs?)\\b",
            Pattern.CASE_INSENSITIVE);

    /** The leading run of digits and separators inside a salary match. */
    private static final Pattern FIRST_NUMBER =
            Pattern.compile("\\d{1,3}(?:[.,\\s]\\d{3})*(?:[.,]\\d{2})?");

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    /**
     * @return "blocked: ...", "supportive: ..." or null when the posting is silent
     */
    public String sponsorship(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String blocked = firstMatch(description, BLOCKING);
        if (blocked != null) {
            return "blocked: " + blocked;
        }
        String supportive = firstMatch(description, SUPPORTIVE);
        return supportive == null ? null : "supportive: " + supportive;
    }

    /** The first stated figure with its surrounding clause, or null. */
    public String salary(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        Matcher m = SALARY.matcher(description);
        while (m.find()) {
            if (!isPayScale(m.group())) {
                continue;
            }
            String window = window(description, m.start(), m.end(), 45, 55);
            // A bare figure in prose is usually funding raised or a discount
            // benefit. Requiring a pay word nearby is what separates "EUR 55.000
            // per year" from "raised EUR 40 million".
            if (window.matches("(?is).*\\b(salar|compensation|pay|package|gross|"
                    + "per\\s+year|per\\s+annum|annually|per\\s+month|ctc|lpa|"
                    + "base|remuneration|bruto|brutto).*")) {
                return window;
            }
        }
        return null;
    }

    /**
     * Whether the figure is big enough to be pay rather than a perk.
     *
     * <p>The pay-word guard alone is not enough, because benefits are quoted in
     * the same language: "learning &amp; development stipend (EUR 1,400 per year)"
     * and "scales to EUR 1,000 annually" both sit next to "per year" and both
     * arrived in the digest as salaries. Nobody is offered EUR 1,400 a year, so
     * magnitude separates them where wording cannot.
     */
    private static boolean isPayScale(String match) {
        Matcher digits = FIRST_NUMBER.matcher(match);
        if (!digits.find()) {
            return false;
        }
        // Drop a cents group, then the thousands separators - "81.600,00" and
        // "81,600" and "81 600" all have to read as 81600.
        String cleaned = digits.group()
                .replaceAll("[.,]\\d{2}$", "")
                .replaceAll("[.,\\s]", "");
        long value;
        try {
            value = Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return false;
        }

        String lower = match.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches("(?s).*\\b(lpa|lakhs?|lacs?)\\b.*")) {
            return value >= 1 && value <= 200;
        }
        boolean thousands = lower.matches("(?s).*\\dk\\b.*") || lower.contains(" k");
        if (lower.matches("(?s).*(₹|inr|rs).*")) {
            return thousands ? value >= 100 : value >= 100_000;
        }
        return thousands ? value >= 10 : value >= 10_000;
    }

    private static String firstMatch(String text, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            Matcher m = pattern.matcher(text);
            if (m.find()) {
                return window(text, m.start(), m.end(), 20, 40);
            }
        }
        return null;
    }

    private static String window(String text, int start, int end, int before, int after) {
        int from = Math.max(0, start - before);
        int to = Math.min(text.length(), end + after);
        return text.substring(from, to).replaceAll("\\s+", " ").trim();
    }
}
