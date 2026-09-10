package com.anuragbhandary.jobradar.domain;

/**
 * Which lane of the job search an opportunity belongs to.
 *
 * <p>Derived from structured fields - country code, work mode, remote
 * eligibility, employer country - and never entered by hand. The derivation lives
 * in {@link com.anuragbhandary.jobradar.filter.StrategicClassifier} so there is
 * one definition of what makes something international remote rather than a
 * relocation.
 *
 * <p>The distinction that matters most is the last two. A US employer hiring
 * remotely from India is <em>not</em> a US relocation: no visa is involved, no
 * rent is involved, and the strategy that excludes US relocation has nothing to
 * say about it.
 */
public enum StrategicClass {

    /**
     * India, in the home metro. The cheapest job to hold: no rent, so nearly all
     * of take-home is free.
     */
    INDIA_HOME,

    /** India, elsewhere. Needs a premium to cover rent, food and the move. */
    INDIA_OTHER,

    /**
     * Outside India, and it needs a body there. Visa, relocation, cost of living.
     * The long-term objective, gated by country strategy.
     */
    INTERNATIONAL_RELOCATION,

    /**
     * An employer outside India, worked from India.
     *
     * <p>International pay and experience with no immigration and no rent. The
     * lane that had nowhere to live in the old model, because
     * {@link Country#REMOTE} could not say who the employer was.
     */
    INTERNATIONAL_REMOTE,

    /**
     * Not enough evidence to place it.
     *
     * <p>Kept as a value rather than defaulting to something plausible. A posting
     * whose country could not be resolved is a posting to classify later, not a
     * posting to guess about.
     */
    UNCLASSIFIED;

    public boolean isIndia() {
        return this == INDIA_HOME || this == INDIA_OTHER;
    }

    /** True when taking the job means moving country. */
    public boolean isRelocation() {
        return this == INTERNATIONAL_RELOCATION;
    }
}
