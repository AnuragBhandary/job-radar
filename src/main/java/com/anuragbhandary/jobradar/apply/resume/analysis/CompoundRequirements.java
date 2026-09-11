package com.anuragbhandary.jobradar.apply.resume.analysis;

import com.anuragbhandary.jobradar.knowledge.experience.SkillGraph;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits "Python / JavaScript / TypeScript" into three requirements, and leaves
 * "CI/CD" alone.
 *
 * <p>Found in the first rewrite benchmark: a model extracted
 * "Python/JavaScript/TypeScript" as one term, the positioner found nothing by that
 * name, and the ledger told the rewriter <em>not to claim</em> it - on an AI
 * posting, to a candidate whose main language is Python. A ledger must never call
 * a technology unsupported when one component of a compound is his.
 *
 * <h2>When a term is split</h2>
 * Only when it contains a separator and <em>every</em> part is a name the system
 * recognises - a vocabulary term, an alias, something {@link SkillGraph} knows, a
 * curated phrase, or one of a few language names too short for the vocabulary.
 * "TCP/IP", "research and development" and "AWS (EC2, S3)" have a part that is
 * not a name, so they stay whole; "CI/CD" is itself a name, so it stays whole.
 *
 * <h2>Alternatives and conjunctions</h2>
 * A slash or "or" offers options: "C, C++, or Rust" is satisfied by any of them,
 * so the parts carry {@link Requirement#alternativeOf()} and the ledger counts the
 * set once. Commas and "and" list things wanted together, so the parts stand
 * alone.
 */
public final class CompoundRequirements {

    private CompoundRequirements() {
    }

    private static final Pattern SEPARATOR =
            Pattern.compile("\\s*(?:/|,|&|\\||\\bor\\b|\\band\\b)\\s*", Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTERNATIVE =
            Pattern.compile("/|\\||\\bor\\b", Pattern.CASE_INSENSITIVE);

    /** Language names the vocabulary leaves out because they are too short to match safely. */
    private static final Set<String> SHORT_LANGUAGES = Set.of(
            "c", "r", "sql", "bash", "shell", "perl", "matlab", "html", "css", "nosql");

    /** The requirement's parts, or the requirement itself with a normalised id. */
    public static List<Requirement> expand(Requirement requirement) {
        String term = requirement.term();
        if (term == null || term.isBlank()) {
            return List.of();
        }
        if (recognised(PostingRequirements.canonicalSubject(term))
                || !SEPARATOR.matcher(term).find()) {
            return List.of(normalised(requirement));
        }
        List<String> parts = Arrays.stream(SEPARATOR.split(term))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList();
        if (parts.size() < 2 || !parts.stream()
                .allMatch(part -> recognised(PostingRequirements.canonicalSubject(part)))) {
            return List.of(normalised(requirement));
        }
        boolean alternative = ALTERNATIVE.matcher(term).find();
        List<Requirement> out = new ArrayList<>();
        for (String part : parts) {
            String canonical = PostingRequirements.canonicalSubject(part);
            out.add(new Requirement(Requirement.idFor(canonical), part, categoryOf(canonical,
                    requirement.category()), requirement.importance(), requirement.quote(),
                    alternative ? term.strip() : null));
        }
        return out;
    }

    /**
     * The same requirement, keyed on its canonical name.
     *
     * <p>So "K8s" and "Kubernetes", or "Postgres" and "PostgreSQL", are one
     * requirement, not two rows saying the same thing with different verdicts.
     */
    static Requirement normalised(Requirement requirement) {
        String id = Requirement.idFor(PostingRequirements.canonicalSubject(requirement.term()));
        if (id.isEmpty() || id.equals(requirement.id())) {
            return requirement;
        }
        return new Requirement(id, requirement.term(), requirement.category(),
                requirement.importance(), requirement.quote(), requirement.alternativeOf());
    }

    static boolean recognised(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String key = name.strip().toLowerCase(Locale.ROOT);
        return PostingRequirements.ALIASES.containsKey(key)
                || TechVocabulary.found(key).contains(key)
                || SkillGraph.knows(key)
                || SHORT_LANGUAGES.contains(key)
                || PostingRequirements.isPhrase(name);
    }

    private static RequirementCategory categoryOf(String canonical, RequirementCategory fallback) {
        String key = canonical.toLowerCase(Locale.ROOT);
        if (SHORT_LANGUAGES.contains(key)) {
            return RequirementCategory.LANGUAGE;
        }
        if (PostingRequirements.isPhrase(canonical)) {
            return PostingRequirements.phraseFor(canonical)
                    .map(PostingRequirements.Phrase::category).orElse(fallback);
        }
        RequirementCategory category = PostingRequirements.categoryOf(key);
        return category == RequirementCategory.TECHNOLOGY && fallback != null ? fallback : category;
    }
}
