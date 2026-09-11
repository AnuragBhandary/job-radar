package com.anuragbhandary.jobradar.evidence;

import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementCategory;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Text rules the bank shares: comparison, word stems, and what counts as a product name. */
final class EvidenceText {

    private EvidenceText() {
    }

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "of", "to",
            "in", "on", "an", "by", "from", "into", "via", "per", "its", "their", "that", "this",
            "as", "at", "or", "over", "through");

    /**
     * Stems that say nothing about which piece of work is meant. A requirement made
     * of only these ("systems", "software development") matches no concept by being
     * contained in it.
     */
    static final Set<String> GENERIC = Set.of("system", "servic", "backen", "applic",
            "softwa", "engine", "platfo", "develo", "experi", "techno", "soluti", "build",
            "built", "work", "strong", "knowle", "abilit", "year", "skill", "using", "team");

    /**
     * Six-letter stems of the words, plurals folded, two letters or more.
     *
     * <p>Two letters rather than four so "API" and "TTS" are words here - a concept
     * called "TTS" has to be comparable with something.
     */
    static Set<String> stems(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9+#]+")) {
            if (raw.length() < 2 || STOP.contains(raw)) {
                continue;
            }
            String word = raw;
            if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
                word = word.substring(0, word.length() - 1);
            }
            out.add(word.length() > 6 ? word.substring(0, 6) : word);
        }
        return out;
    }

    /** Lower case, one space, typographic quotes and dashes folded. For equality of sentences. */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace('’', '\'').replace('‘', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-')
                .replaceAll("\\s+", " ")
                .strip();
    }

    /** The phrase appears in the text as whole words, case and spacing aside. */
    static boolean containsPhrase(String text, String phrase) {
        String needle = normalise(phrase);
        if (needle.isEmpty()) {
            return false;
        }
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(needle) + "(?![a-z0-9])")
                .matcher(normalise(text)).find();
    }

    /** "12" is in "12 streams" and "10" in "10x", but "1" is not in "12". */
    static boolean containsFigure(String text, String figure) {
        return Pattern.compile("(?<![0-9,.])" + Pattern.quote(figure) + "(?![0-9]|[,.][0-9])")
                .matcher(text == null ? "" : text).find();
    }

    /** Whole-word, case-insensitive; '+', '#' and a joined '.' count as part of a name. */
    static boolean wholeWord(String text, String word) {
        if (text == null || word == null || word.isBlank()) {
            return false;
        }
        return Pattern.compile("(?<![\\w+#.])" + Pattern.quote(word.strip()) + "(?![\\w+#])",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    /** Technology keys the text names, aliases folded. */
    static Set<String> technologiesIn(String text) {
        Set<String> keys = new LinkedHashSet<>();
        TechVocabulary.found(text).forEach(term ->
                keys.add(PostingRequirements.ALIASES.getOrDefault(term, term)));
        return keys;
    }

    /**
     * A product, language, framework, database or cloud service - something a
     * person has or has not used. Architecture words ("event-driven",
     * "microservices") are ideas, and are not.
     */
    static boolean isProductName(String term) {
        String k = term == null ? "" : term.strip().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) {
            return false;
        }
        String canonical = PostingRequirements.ALIASES.getOrDefault(k, k);
        boolean known = TechVocabulary.found(k).contains(k)
                || TechVocabulary.found(canonical).contains(canonical);
        return known && PostingRequirements.categoryOf(canonical) != RequirementCategory.ARCHITECTURE;
    }
}
