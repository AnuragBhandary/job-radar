package com.anuragbhandary.jobradar.domain;

/**
 * What the most recent fetch found out about a posting.
 *
 * <p>Distinct from {@link Verdict}, which is about whether the posting is worth
 * applying to. A posting can be a CANDIDATE and SEEN - eligible, but reported
 * yesterday and not worth reporting again.
 */
public enum PostingStatus {
    /** First time this posting has been seen on this board. */
    NEW,
    /** Seen before, but the description has changed since. */
    UPDATED,
    /** Seen before, unchanged. Excluded from the digest. */
    SEEN,
    /**
     * Absent from its board for long enough to be considered taken down.
     *
     * <p>Only ever set for boards that fetched successfully. A board returning
     * 404 makes all of its postings look absent, and closing them would turn a
     * broken token into "the company stopped hiring".
     */
    CLOSED
}
