package com.anuragbhandary.jobradar.strategy;

/**
 * How strongly the user wants to move to a country.
 *
 * <p>Strategy, not fact. These are preferences about where to live and they are
 * expected to change - a language learned, a partner, a market turning - so they
 * are bound from configuration rather than written into Java. Nothing here is an
 * immigration claim; see {@link CountryPolicy} for the fields that are, and for
 * the dates that say when they were last checked.
 *
 * <p><strong>A tier is not eligibility.</strong> {@link #EXCLUDED} means "do not
 * recommend moving there", not "this job cannot be applied for" and certainly not
 * "delete this posting". A US relocation role is a perfectly real job the
 * applicant could take; the strategy simply says he would rather not. That
 * distinction is why {@link StrategyOutcome} exists as a separate concept and why
 * screening no longer throws these postings away.
 */
public enum RelocationTier {

    /** Move here. */
    PRIMARY(0),

    /** Worth a real application. */
    SECONDARY(1),

    /** Worth it for the right role. */
    OPPORTUNISTIC(2),

    /** Only if something unusual is on offer. */
    LOW(3),

    /**
     * Not part of the relocation strategy.
     *
     * <p>Says nothing about working for an employer in that country remotely -
     * that is {@link CountryPolicy#allowsRemote()}, and for the United States it
     * is deliberately true while this is EXCLUDED.
     */
    EXCLUDED(4),

    /** No policy has been written for this country yet. */
    UNKNOWN(5);

    private final int rank;

    RelocationTier(int rank) {
        this.rank = rank;
    }

    /** Lower sorts first. Used to pick the best country named in a multi-location posting. */
    public int rank() {
        return rank;
    }

    public boolean isRecommendedForRelocation() {
        return this == PRIMARY || this == SECONDARY;
    }
}
