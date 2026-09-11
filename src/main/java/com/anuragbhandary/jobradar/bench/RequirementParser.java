package com.anuragbhandary.jobradar.bench;

import com.anuragbhandary.jobradar.apply.resume.analysis.QuoteGrounding;
import com.anuragbhandary.jobradar.apply.resume.analysis.Requirement;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementCategory;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a model's reply into requirements Java is willing to believe.
 *
 * <p>The model proposes; this decides. Three things can go wrong with a proposed
 * requirement and each is recorded rather than repaired:
 * <ul>
 *   <li><b>Malformed</b> - no term, or a category or importance outside the
 *       enum. The reply broke the schema.</li>
 *   <li><b>Missing quote</b> - the requirement cannot be traced to the posting
 *       at all. Also a schema break, because the quote is a required field.</li>
 *   <li><b>Ungrounded</b> - a quote was given and the posting does not contain
 *       it. The schema was followed and the content was invented, which is the
 *       failure that matters most and the one the benchmark counts.</li>
 * </ul>
 * Only requirements that survive all three reach {@link Result#accepted()}, and
 * only those may ever reach a coverage ledger.
 */
public final class RequirementParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RequirementParser() {
    }

    public enum Reason {
        MALFORMED_ITEM,
        MISSING_QUOTE,
        UNGROUNDED
    }

    public record Rejection(String term, String quote, Reason reason, String detail) {
    }

    /**
     * @param schemaValid the reply was JSON with a requirements array and every
     *                    item had all four fields with legal values. Ungrounded
     *                    quotes do not make a reply schema-invalid; they are
     *                    counted on their own.
     * @param proposed    items in the reply, before any rejection or merging
     * @param withQuote   items carrying a non-blank quote
     * @param grounded    of those, how many were found in the posting
     * @param duplicates  grounded items merged into an earlier one with the same id
     */
    public record Result(
            boolean schemaValid,
            String error,
            int proposed,
            int withQuote,
            int grounded,
            List<Requirement> accepted,
            List<Rejection> rejected,
            int duplicates) {

        public long count(Reason reason) {
            return rejected.stream().filter(r -> r.reason() == reason).count();
        }

        static Result invalid(String error) {
            return new Result(false, error, 0, 0, 0, List.of(), List.of(), 0);
        }
    }

    public static Result parse(String reply, String postingText) {
        if (reply == null || reply.isBlank()) {
            return Result.invalid("empty reply");
        }
        JsonNode root;
        try {
            root = JSON.readTree(strip(reply));
        } catch (Exception e) {
            return Result.invalid("not JSON: " + firstLine(e.getMessage()));
        }
        if (root == null || !root.isObject() || !root.path("requirements").isArray()) {
            return Result.invalid("no \"requirements\" array");
        }

        QuoteGrounding grounding = QuoteGrounding.of(postingText);
        boolean valid = true;
        int proposed = 0;
        int withQuote = 0;
        int grounded = 0;
        int duplicates = 0;
        Map<String, Requirement> accepted = new LinkedHashMap<>();
        List<Rejection> rejected = new ArrayList<>();

        for (JsonNode item : root.path("requirements")) {
            proposed++;
            if (!item.isObject()) {
                valid = false;
                rejected.add(new Rejection(null, null, Reason.MALFORMED_ITEM, "not an object"));
                continue;
            }
            String term = text(item, "term");
            RequirementCategory category = enumOf(RequirementCategory.class, text(item, "category"));
            RequirementImportance importance =
                    enumOf(RequirementImportance.class, text(item, "importance"));
            String quote = text(item, "quote");

            if (term == null || term.isBlank() || Requirement.idFor(term).isEmpty()) {
                valid = false;
                rejected.add(new Rejection(term, quote, Reason.MALFORMED_ITEM, "no usable term"));
                continue;
            }
            if (category == null || importance == null) {
                valid = false;
                rejected.add(new Rejection(term, quote, Reason.MALFORMED_ITEM,
                        "category '" + text(item, "category") + "' or importance '"
                                + text(item, "importance") + "' is not allowed"));
                continue;
            }
            if (quote == null || quote.isBlank()) {
                valid = false;
                rejected.add(new Rejection(term, quote, Reason.MISSING_QUOTE, "no quote"));
                continue;
            }
            withQuote++;
            if (!grounding.contains(quote)) {
                rejected.add(new Rejection(term, quote, Reason.UNGROUNDED,
                        "quote not found in the posting"));
                continue;
            }
            grounded++;

            Requirement requirement = Requirement.of(term, category, importance, quote.strip());
            Requirement existing = accepted.get(requirement.id());
            if (existing == null) {
                accepted.put(requirement.id(), requirement);
            } else {
                duplicates++;
                accepted.put(requirement.id(),
                        existing.withImportance(existing.importance().strongerOf(importance)));
            }
        }
        return new Result(valid, null, proposed, withQuote, grounded,
                List.copyOf(accepted.values()), List.copyOf(rejected), duplicates);
    }

    /** Only textual values count: a number where a term should be is malformed. */
    private static String text(JsonNode item, String field) {
        JsonNode value = item.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Code fences and prose around the object, which unconstrained replies add. */
    static String strip(String reply) {
        String cleaned = reply.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "");
        }
        int open = cleaned.indexOf('{');
        int close = cleaned.lastIndexOf('}');
        return open >= 0 && close > open ? cleaned.substring(open, close + 1) : cleaned;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
