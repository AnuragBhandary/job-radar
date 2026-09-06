package com.anuragbhandary.jobradar.domain;

/**
 * Outcome of screening a posting.
 *
 * <p>Note there is no ACCEPTED state. A CANDIDATE is something worth a human
 * reading; the tool never decides that a job is right, only that it is not
 * provably wrong.
 */
public enum Verdict {
    /** Fetched but not yet run through the filters. */
    UNSCREENED,
    /** Survived geography, title and years screening. */
    CANDIDATE,
    /** Failed a filter. {@code Posting#rejectReason} always says which. */
    REJECTED
}
