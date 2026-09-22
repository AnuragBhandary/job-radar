package com.anuragbhandary.jobradar.filter;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a posting that only takes people who already live in the job's country.
 *
 * <p>The location text cannot say this. Bjak's German postings are filed as
 * "Germany", which reads as an onsite job worth relocating for, while the
 * description says "applicants should already be based in Germany". Five of them
 * sat in the 2026-09-22 handoff file.
 *
 * <p>Deliberately narrow. "Must be based in Munich" on an onsite job only names
 * the office, and relocating satisfies it, so a match needs "already",
 * "currently" or a residency word. A clause that also allows India, Asia,
 * anywhere or relocation is not a lock.
 */
final class ResidencyRequirement {

    private static final Pattern REQUIRED = Pattern.compile(
            "\\b(?:must|should|need\\s+to|required\\s+to|have\\s+to)\\s+"
                    + "(?:already|currently)\\s+(?:be\\s+)?"
                    + "(?:based|located|living|residing|reside|live|resident)\\s+(?:in|of)\\s+"
                    + "|\\b(?:must|should|need\\s+to|required\\s+to)\\s+(?:be\\s+)?"
                    + "(?:a\\s+)?(?:resident|residing|reside)\\s+(?:in|of)\\s+",
            Pattern.CASE_INSENSITIVE);

    /** Something in the rest of the clause that India would satisfy. */
    private static final Pattern OPEN = Pattern.compile(
            "\\b(?:india|asia|apac|anywhere|worldwide|relocat\\w*)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLAUSE_END = Pattern.compile("[.;\\n]");

    private ResidencyRequirement() {
    }

    /** The requirement as the posting states it, or empty. */
    static Optional<String> find(String description) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        Matcher m = REQUIRED.matcher(description);
        while (m.find()) {
            String rest = description.substring(m.end(), Math.min(description.length(), m.end() + 120));
            Matcher end = CLAUSE_END.matcher(rest);
            String clause = end.find() ? rest.substring(0, end.start()) : rest;
            if (clause.isBlank() || OPEN.matcher(clause).find()) {
                continue;
            }
            String phrase = description.substring(Math.max(0, m.start() - 10),
                    Math.min(description.length(), m.end() + clause.length()))
                    .replaceAll("\\s+", " ").strip();
            return Optional.of("residency required: \"" + phrase + "\"");
        }
        return Optional.empty();
    }
}
