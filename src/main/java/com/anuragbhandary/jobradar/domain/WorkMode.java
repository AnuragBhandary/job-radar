package com.anuragbhandary.jobradar.domain;

/**
 * Where the work is actually done, as distinct from where the employer is.
 *
 * <p>The reason this is not a boolean, and not the single {@link Country#REMOTE}
 * value it replaces: "remote" on a job board means at least four different
 * things, and three of them are unreachable from Mumbai. Collapsing them cost
 * real applications before this tool existed, which is what
 * {@link com.anuragbhandary.jobradar.filter.GeoFilter}'s country-locked check has
 * been guarding against ever since.
 *
 * <pre>
 *   "Remote - United States only"   REMOTE_COUNTRY_LOCKED   [US]   unreachable
 *   "Remote - India"                REMOTE_COUNTRY_LOCKED   [IN]   reachable
 *   "Remote - Europe"               REMOTE_REGIONAL         EU     unreachable
 *   "Remote - worldwide"            REMOTE_GLOBAL           []     reachable
 *   "Remote"                        REMOTE_UNSPECIFIED      []     unknown
 * </pre>
 */
public enum WorkMode {

    /** Physical presence required. Relocation, if the country is not India. */
    ONSITE,

    /** Presence required some of the time, which for this purpose is the same as ONSITE. */
    HYBRID,

    /**
     * Remote, but only from named countries.
     *
     * <p>The country list is a hiring restriction, not a perk. Whether this is
     * usable depends entirely on whether India is in
     * {@code Posting#getRemoteEligibleFrom()}.
     */
    REMOTE_COUNTRY_LOCKED,

    /** Remote within a named region - EMEA, LATAM, APAC, the Nordics. */
    REMOTE_REGIONAL,

    /**
     * Remote from anywhere, or from somewhere explicitly said to include India.
     *
     * <p>Set only on positive evidence: a global marker ("worldwide", "work from
     * anywhere") or an explicit allowance naming a country. Never inferred from
     * the bare word "remote" - that is {@link #REMOTE_UNSPECIFIED}.
     */
    REMOTE_GLOBAL,

    /**
     * The posting says "Remote" and says nothing about where from.
     *
     * <p>Its own value rather than being folded into {@link #REMOTE_GLOBAL},
     * because the difference is exactly the guess this model exists to stop
     * making. A German company writing "Remote" almost certainly means remote
     * within Germany; a developer-tools company writing the same word often means
     * anywhere. The honest record is that the scope was not stated, and a later
     * sponsorship resolver must be able to see that it was not.
     */
    REMOTE_UNSPECIFIED,

    /** No location evidence at all. Requires classification, never assumed. */
    UNKNOWN;

    public boolean isRemote() {
        return this == REMOTE_COUNTRY_LOCKED || this == REMOTE_REGIONAL
                || this == REMOTE_GLOBAL || this == REMOTE_UNSPECIFIED;
    }

    /** True when the job needs a body in a particular place. */
    public boolean requiresPresence() {
        return this == ONSITE || this == HYBRID;
    }
}
