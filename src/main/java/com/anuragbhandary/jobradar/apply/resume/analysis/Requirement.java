package com.anuragbhandary.jobradar.apply.resume.analysis;

import java.util.Locale;

/**
 * One thing a posting asks for, and the words it used to ask.
 *
 * <p>The quote is not decoration. It is a span of the posting's own text, checked
 * by {@link QuoteGrounding} before a requirement is allowed to exist, so every
 * row of a coverage ledger can be traced back to a sentence the employer wrote -
 * whoever proposed it, a regex or a model.
 *
 * @param id            derived from the term, never supplied by a model. The same
 *                      term always gets the same id, which is what makes
 *                      duplicates mergeable and a ledger comparable across runs.
 * @param quote         verbatim from the posting
 * @param alternativeOf when this requirement is one option of several the posting
 *                      offered - "Python / JavaScript / TypeScript" - the compound
 *                      as written. Null for a requirement that stands alone. The
 *                      ledger counts a set of alternatives once, at the strongest
 *                      evidence any option has.
 */
public record Requirement(
        String id,
        String term,
        RequirementCategory category,
        RequirementImportance importance,
        String quote,
        String alternativeOf) {

    public static Requirement of(String term, RequirementCategory category,
            RequirementImportance importance, String quote) {
        return new Requirement(idFor(term), term.strip(), category, importance, quote, null);
    }

    /**
     * "req-" plus a slug of the term. Empty when the term has nothing in it to
     * slug, which callers treat as a malformed requirement.
     *
     * <p>'+', '#' and '.' survive so "c++", "c#" and "node.js" stay distinct from
     * "c" and "node".
     */
    public static String idFor(String term) {
        if (term == null) {
            return "";
        }
        String slug = term.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+#.]+", "-")
                .replaceAll("^[-.]+|[-.]+$", "");
        return slug.isEmpty() ? "" : "req-" + slug;
    }

    public Requirement withImportance(RequirementImportance stronger) {
        return new Requirement(id, term, category, stronger, quote, alternativeOf);
    }
}
