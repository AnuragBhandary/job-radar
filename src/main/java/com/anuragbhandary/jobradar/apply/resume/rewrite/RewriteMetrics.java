package com.anuragbhandary.jobradar.apply.resume.rewrite;

import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The concrete measurements a rewrite is judged on. Counts, not scores.
 */
public final class RewriteMetrics {

    private RewriteMetrics() {
    }

    /** Words carrying no meaning of their own for this comparison. */
    private static final Set<String> STOP = Set.of(
            "built", "build", "building", "developed", "develop", "designed", "design",
            "implemented", "implement", "created", "delivered", "engineered", "structured",
            "integrated", "produced", "used", "using", "with", "that", "this", "from", "into",
            "through", "their", "which", "while", "where", "when", "based", "across", "over",
            "under", "including", "approximately", "roughly", "about", "around", "more",
            "most", "than", "then", "also", "such", "each", "other", "both", "after",
            "before", "within", "without", "enabling", "enable", "ensuring", "ensure",
            "providing", "provide", "supporting", "support", "handling", "leveraging",
            "leverage", "utilising", "utilizing", "robust", "efficient", "effective",
            "seamless", "reliable", "various", "multiple");

    /** Six-letter stems of the content words, plurals folded. */
    public static Set<String> stems(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (word.length() < 4 || STOP.contains(word)) {
                continue;
            }
            String w = word;
            if (w.length() > 4 && w.endsWith("s") && !w.endsWith("ss")) {
                w = w.substring(0, w.length() - 1);
            }
            out.add(w.length() > 6 ? w.substring(0, 6) : w);
        }
        return out;
    }

    /** Share of the source's content words the rewrite still carries. 1.0 for an empty source. */
    public static double retention(String source, String rewrite) {
        Set<String> from = stems(source);
        if (from.isEmpty()) {
            return 1.0;
        }
        Set<String> to = stems(rewrite);
        long kept = from.stream().filter(to::contains).count();
        return (double) kept / from.size();
    }

    /**
     * Whether this text names this requirement, in any of the ways it could.
     *
     * <p>Phrase requirements match by their pattern; technologies by name, alias,
     * or - for a compound like "Hibernate/JPA" - every technology inside it.
     */
    public static boolean mentions(String text, String term) {
        if (text == null || text.isBlank() || term == null || term.isBlank()) {
            return false;
        }
        String subject = PostingRequirements.canonicalSubject(term);
        Optional<PostingRequirements.Phrase> phrase = PostingRequirements.isPhrase(subject)
                ? PostingRequirements.phraseFor(subject) : Optional.empty();
        if (phrase.isPresent()) {
            return phrase.get().foundIn(text);
        }
        if (wholeWord(text, subject) || wholeWord(text, term.strip())) {
            return true;
        }
        Set<String> inText = new LinkedHashSet<>();
        TechVocabulary.found(text).forEach(t -> inText.add(PostingRequirements.ALIASES.getOrDefault(t, t)));
        String key = subject.toLowerCase(Locale.ROOT);
        if (inText.contains(PostingRequirements.ALIASES.getOrDefault(key, key))) {
            return true;
        }
        Set<String> inTerm = TechVocabulary.found(term);
        return inTerm.size() > 1 && inTerm.stream()
                .map(t -> PostingRequirements.ALIASES.getOrDefault(t, t))
                .allMatch(inText::contains);
    }

    public static int targetsCovered(String text, List<RewriteRequest.Target> targets) {
        return (int) targets.stream().filter(t -> mentions(text, t.term())).count();
    }

    /** Content words shared with the posting's own words for this item's targets. */
    public static int postingOverlap(String text, List<RewriteRequest.Target> targets) {
        Set<String> posting = new LinkedHashSet<>();
        targets.forEach(t -> posting.addAll(stems(t.quote())));
        targets.forEach(t -> posting.addAll(stems(t.term())));
        Set<String> mine = stems(text);
        return (int) posting.stream().filter(mine::contains).count();
    }

    static boolean wholeWord(String text, String word) {
        if (text == null || word == null || word.isBlank()) {
            return false;
        }
        return Pattern.compile("(?<![\\w+#.])" + Pattern.quote(word.strip()) + "(?![\\w+#])",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }
}
