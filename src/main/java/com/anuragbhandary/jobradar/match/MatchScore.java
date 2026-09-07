package com.anuragbhandary.jobradar.match;

import java.util.List;

/**
 * How well one posting fits, and why.
 *
 * <p>The "why" is not decoration. A number on its own is a thing to argue with;
 * a number with "you have 7 of the 9 technologies it names, it wants 5+ years"
 * beside it is a thing to act on. Every factor carries its own points, its
 * maximum, and a sentence naming the evidence.
 *
 * <p>Deliberately arithmetic rather than a model. Scoring 9,032 postings through
 * an LLM would cost real money and take minutes; more importantly it would
 * produce a number nobody could reproduce or contest. This runs in a millisecond
 * and every point is traceable to a rule.
 */
public record MatchScore(int score, List<Factor> factors) {

    /**
     * @param points scored, out of {@code max}
     * @param detail the evidence, in words. Shown next to the bar in the UI.
     */
    public record Factor(String label, int points, int max, String detail) {

        public int percent() {
            return max == 0 ? 0 : Math.round(points * 100f / max);
        }
    }

    /**
     * A coarse band, for colour and for sorting into groups.
     *
     * <p>Four bands rather than a raw number in the list, because the difference
     * between 71 and 68 is noise and treating it as signal is how a ranked feed
     * becomes superstition.
     */
    public enum Band {
        STRONG("strong"), GOOD("good"), FAIR("fair"), WEAK("weak");

        private final String label;

        Band(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public Band band() {
        if (score >= 75) {
            return Band.STRONG;
        }
        if (score >= 55) {
            return Band.GOOD;
        }
        return score >= 35 ? Band.FAIR : Band.WEAK;
    }

    /** The single most useful sentence about this match, for a list row. */
    public String headline() {
        return factors.stream()
                .filter(factor -> factor.detail() != null && !factor.detail().isBlank())
                .max((a, b) -> Integer.compare(
                        b.max() - b.points(), a.max() - a.points()))
                .map(Factor::detail)
                .orElse("");
    }
}
