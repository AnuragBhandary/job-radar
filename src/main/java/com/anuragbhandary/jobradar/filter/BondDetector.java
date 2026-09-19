package com.anuragbhandary.jobradar.filter;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a service bond or an equivalent lock-in stated in a posting.
 *
 * <p>Common in Indian fresher hiring: a one- to three-year bond with a penalty,
 * a training-cost recovery clause, or original certificates held by the employer.
 * Any of them can stop a move abroad before the bond runs out, so a posting that
 * states one is rejected. Most bonds only appear in the offer letter, so this
 * catches the ones a posting admits to and nothing more.
 *
 * <p>The word "bond" alone is never enough. In the real corpus it is an investor
 * ("funding from ... BOND"), a financial product ("ETFs, bonds, FX"), and team
 * culture ("we bond over events"). Every pattern here needs employment wording
 * around it.
 */
final class BondDetector {

    private static final Pattern BOND = Pattern.compile(
            "\\b(?:service|employment|training|joining|surety)[- ]bond\\b"
                    + "|\\bbond\\s+(?:period|amount|agreement|clause|policy|duration|tenure)\\b"
                    + "|\\bbond\\s+of\\s+(?:\\d+|one|two|three|four|five)\\s*(?:\\.\\d+\\s*)?[- ]?(?:years?|yrs?|months?)\\b"
                    + "|\\b(?:\\d+|one|two|three|four|five)(?:\\.\\d+)?\\s*[- ]?(?:years?|yrs?|months?)\\s+bond\\b"
                    + "|\\b(?:sign|signing|signed|execute|executing)\\s+(?:a|an|the)?\\s*(?:\\w+\\s+){0,2}bond\\b"
                    + "|\\btraining\\s+cost\\s+(?:recovery|reimbursement)\\b"
                    + "|\\boriginal\\s+(?:certificates?|documents?|marksheets?)\\s+(?:\\w+\\s+){0,3}"
                    + "(?:retained|submitted|deposited|kept|held)\\b",
            Pattern.CASE_INSENSITIVE);

    private BondDetector() {
    }

    /** The phrase stating a bond, with a little context, or empty. */
    static Optional<String> find(String description) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        Matcher m = BOND.matcher(description);
        if (!m.find()) {
            return Optional.empty();
        }
        int from = Math.max(0, m.start() - 30);
        int to = Math.min(description.length(), m.end() + 30);
        return Optional.of(description.substring(from, to).replaceAll("\\s+", " ").strip());
    }
}
