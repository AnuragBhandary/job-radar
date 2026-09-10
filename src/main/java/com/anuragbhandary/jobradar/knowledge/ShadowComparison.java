package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.apply.form.Answer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One field, answered twice: by the mapper that fills real forms today, and by
 * the resolver that will replace it.
 *
 * <p>Shadow mode exists because the cost of getting this wrong is not a bug
 * report. Most boards accept one application per posting for all time, so a
 * resolver that answers a sponsorship question differently from the mapper -
 * even once, even in the right direction - has to be understood before it is
 * allowed near a form. Running both and diffing them is the only way to find out
 * without spending applications on the experiment.
 *
 * @param verdict what the difference is, not whether it is wrong. A NEW_ONLY on
 *                a question the old mapper could not answer is the resolver
 *                working; a DIFFERENT_VALUE on sponsorship is a finding.
 */
public record ShadowComparison(
        String label,
        String conceptId,
        String oldValue,
        Answer.Origin oldOrigin,
        String newValue,
        Resolution.State newState,
        Confidence newConfidence,
        Verdict verdict,
        Category category,
        String explanation) {

    /**
     * What a disagreement means, which is the only thing worth measuring.
     *
     * <p>An agreement percentage on its own is a bad target: some disagreements
     * are the new resolver being right, and chasing a number would push towards
     * reproducing the old behaviour including its mistakes. The bar is instead
     * <b>zero unexplained regressions</b> - every difference falls in one of
     * these boxes, and only one of them is bad.
     */
    public enum Category {
        /** Both sides said the same thing. */
        AGREEMENT,
        /** The new resolver used context the old mapper did not have. */
        IMPROVEMENT,
        /** A real behavioural difference that is defensible on its own terms. */
        LEGITIMATE,
        /** Same answer, written differently. The salary field, mostly. */
        PRESENTATION,
        /** The new resolver would give a worse answer. The only unacceptable one. */
        REGRESSION,
        /** Cannot be classified automatically and needs reading. */
        UNCLASSIFIED
    }

    public enum Verdict {
        /** Both produced the same value. */
        AGREE,
        /** Both answered, and they disagree. The one worth reading. */
        DIFFERENT_VALUE,
        /** The resolver answered something the mapper could not. */
        NEW_ONLY,
        /** The mapper answered something the resolver will not. */
        OLD_ONLY,
        /** Neither had an answer. Agreement, of a sort. */
        BOTH_BLANK
    }

    public static ShadowComparison of(String label, Answer old, Resolution current,
            Category category) {
        String oldValue = old == null ? null : old.value();
        String newValue = current.value();
        boolean oldHas = oldValue != null && !oldValue.isBlank();
        boolean newHas = newValue != null && !newValue.isBlank();

        Verdict verdict;
        if (!oldHas && !newHas) {
            verdict = Verdict.BOTH_BLANK;
        } else if (oldHas && !newHas) {
            verdict = Verdict.OLD_ONLY;
        } else if (!oldHas) {
            verdict = Verdict.NEW_ONLY;
        } else {
            verdict = oldValue.trim().equalsIgnoreCase(newValue.trim())
                    ? Verdict.AGREE : Verdict.DIFFERENT_VALUE;
        }

        return new ShadowComparison(label,
                current.concept() == null ? null : current.concept().id(),
                oldValue, old == null ? null : old.origin(),
                newValue, current.state(), current.confidence(), verdict,
                verdict == Verdict.AGREE || verdict == Verdict.BOTH_BLANK
                        ? Category.AGREEMENT : category,
                current.explanation());
    }

    /** True when this row is worth a person reading before the resolver is trusted. */
    public boolean isFinding() {
        return verdict == Verdict.DIFFERENT_VALUE || verdict == Verdict.OLD_ONLY;
    }

    /** The only category that blocks the resolver becoming authoritative. */
    public boolean isRegression() {
        return category == Category.REGRESSION || category == Category.UNCLASSIFIED;
    }

    public String describe() {
        return switch (verdict) {
            case AGREE -> "same    " + label + " = " + abbreviate(oldValue);
            case BOTH_BLANK -> "blank   " + label;
            case NEW_ONLY -> "NEW     " + label + " = " + abbreviate(newValue)
                    + "  [" + newState + "/" + newConfidence + "] " + nullSafe(explanation);
            case OLD_ONLY -> "LOST    " + label + " was " + abbreviate(oldValue)
                    + " (" + oldOrigin + "); resolver says " + newState
                    + " - " + nullSafe(explanation);
            case DIFFERENT_VALUE -> "DIFFERS " + label + ": old " + abbreviate(oldValue)
                    + " (" + oldOrigin + ") vs new " + abbreviate(newValue)
                    + " [" + newState + "/" + newConfidence + "] " + nullSafe(explanation);
        };
    }

    /** Counts by verdict, most common first, for a summary line. */
    public static Map<Verdict, Long> summarise(List<ShadowComparison> rows) {
        return rows.stream().collect(Collectors.groupingBy(
                ShadowComparison::verdict, java.util.TreeMap::new, Collectors.counting()));
    }

    /** Agreement as a percentage of fields either side answered. */
    public static double agreementRate(List<ShadowComparison> rows) {
        long comparable = rows.stream().filter(r -> r.verdict() != Verdict.BOTH_BLANK).count();
        if (comparable == 0) {
            return 1.0;
        }
        long agreed = rows.stream().filter(r -> r.verdict() == Verdict.AGREE).count();
        return (double) agreed / comparable;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "(none)";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? "'" + flat + "'" : "'" + flat.substring(0, 57) + "...'";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
