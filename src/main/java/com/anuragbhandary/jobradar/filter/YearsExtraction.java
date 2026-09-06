package com.anuragbhandary.jobradar.filter;

/**
 * What the description says about required experience.
 *
 * @param minYears     lowest plausible requirement found, or {@link #NONE_STATED}
 * @param evidence     the phrase {@code minYears} came from, for the reject reason
 * @param nonInternship the matched "N years of non-internship" phrase, or null.
 *                      Amazon's wording, and disqualifying on its own regardless
 *                      of what {@code minYears} works out to.
 */
public record YearsExtraction(int minYears, String evidence, String nonInternship) {

    /**
     * No number was stated.
     *
     * <p>Deliberately not zero. A posting that states no requirement is not a
     * posting that states no experience is needed - several were plainly
     * mid-level from phrases like "deep production experience" and "mentor
     * engineers". These go to a human, not to the accept pile.
     */
    public static final int NONE_STATED = -1;

    public static YearsExtraction none() {
        return new YearsExtraction(NONE_STATED, null, null);
    }

    public boolean isNoneStated() {
        return minYears == NONE_STATED;
    }

    public boolean hasNonInternshipRequirement() {
        return nonInternship != null;
    }
}
