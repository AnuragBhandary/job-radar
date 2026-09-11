package com.anuragbhandary.jobradar.apply.resume.rewrite;

import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementCategory;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a rewrite reads like something a person wrote - separately from whether
 * it is true.
 *
 * <p>"More job keywords" is not "better". The first benchmark's most
 * "job-specific" rewrites were sentences with "using python." bolted onto the
 * end. This names those patterns so they can be rejected (tacked-on names) or at
 * least not counted as improvements (names in the wrong case), and gives every
 * rewrite a label a person can then check.
 */
public final class RewriteQuality {

    private RewriteQuality() {
    }

    /**
     * @see #classify
     */
    public enum Label {
        REJECTED,
        MODEL_FAILED,
        UNCHANGED,
        /** Technology names tacked onto the end of the source sentence. */
        STUFFED,
        /** A product name written in lower case - "python", "docker", "rest apis". */
        UNNATURAL,
        /** Changed, and no more relevant to the job than before. */
        COSMETIC,
        /**
         * Changed, names more of what the job asks for, and no mechanical fault
         * found. A candidate only: whether it is genuinely better is a reading, and
         * the benchmark report says so.
         */
        CANDIDATE_IMPROVEMENT
    }

    private static final Set<RequirementCategory> PROPER_NAMES = Set.of(
            RequirementCategory.LANGUAGE, RequirementCategory.FRAMEWORK,
            RequirementCategory.DATABASE, RequirementCategory.CLOUD);

    private static final Pattern CONNECTOR = Pattern.compile(
            "\\b(using|with|in|via|on|through|leveraging|written in|built with|built in|powered by)\\s+",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LOWERCASE_REST = Pattern.compile("\\brest apis?\\b");

    /**
     * "…for reliable backend workflows using python." - technology names added
     * after the last connector of the sentence, which the source did not have.
     *
     * @return the tacked-on phrase, when there is one
     */
    public static Optional<String> appendedSuffix(String source, String rewrite) {
        if (source == null || rewrite == null) {
            return Optional.empty();
        }
        String text = rewrite.strip().replaceAll("[.;,]+$", "");
        Matcher matcher = CONNECTOR.matcher(text);
        int start = -1;
        int tail = -1;
        while (matcher.find()) {
            start = matcher.start();
            tail = matcher.end();
        }
        if (tail < 0) {
            return Optional.empty();
        }
        String names = text.substring(tail);
        if (names.split("\\s+").length > 5) {
            return Optional.empty();
        }
        var found = TechVocabulary.found(names);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        String remainder = names.toLowerCase(Locale.ROOT);
        for (String term : found) {
            remainder = remainder.replace(term, " ");
        }
        if (!remainder.replaceAll("\\b(and|or)\\b|[,&/]", " ").isBlank()) {
            return Optional.empty();
        }
        String lowerSource = source.toLowerCase(Locale.ROOT);
        boolean allNew = found.stream().noneMatch(term -> RewriteMetrics.wholeWord(lowerSource, term));
        return allNew ? Optional.of(text.substring(start).strip()) : Optional.empty();
    }

    /** Product names the rewrite writes in lower case where the source did not. */
    public static List<String> lowercaseNames(String source, String rewrite) {
        List<String> out = new ArrayList<>();
        if (rewrite == null) {
            return out;
        }
        String original = source == null ? "" : source;
        for (String term : TechVocabulary.found(rewrite)) {
            if (PostingRequirements.AMBIGUOUS.contains(term)
                    || !PROPER_NAMES.contains(PostingRequirements.categoryOf(term))) {
                continue;
            }
            Matcher matcher = Pattern.compile("(?<![\\w+#.])" + Pattern.quote(term) + "(?![\\w+#])",
                    Pattern.CASE_INSENSITIVE).matcher(rewrite);
            while (matcher.find()) {
                String written = matcher.group();
                if (written.equals(written.toLowerCase(Locale.ROOT)) && !original.contains(written)) {
                    out.add(written);
                    break;
                }
            }
        }
        Matcher rest = LOWERCASE_REST.matcher(rewrite);
        if (rest.find() && !original.contains(rest.group())) {
            out.add(rest.group());
        }
        return out;
    }

    /**
     * A label for one rewrite, from its outcome and its text.
     *
     * @param outcome ACCEPTED, UNCHANGED, REJECTED or MODEL_FAILED
     */
    public static Label classify(String outcome, String source, String rewrite,
            int targetsBefore, int targetsAfter, int postingWordsBefore, int postingWordsAfter) {
        if ("MODEL_FAILED".equals(outcome)) {
            return Label.MODEL_FAILED;
        }
        if (rewrite == null) {
            return Label.REJECTED;
        }
        if ("UNCHANGED".equals(outcome)) {
            return Label.UNCHANGED;
        }
        if (appendedSuffix(source, rewrite).isPresent()) {
            return Label.STUFFED;
        }
        if ("REJECTED".equals(outcome)) {
            return Label.REJECTED;
        }
        if (!lowercaseNames(source, rewrite).isEmpty()) {
            return Label.UNNATURAL;
        }
        boolean moreRelevant = targetsAfter > targetsBefore
                || postingWordsAfter >= postingWordsBefore + 2;
        return moreRelevant ? Label.CANDIDATE_IMPROVEMENT : Label.COSMETIC;
    }
}
