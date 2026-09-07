package com.anuragbhandary.jobradar.mail;

/**
 * What a reply from a company means for the tracker.
 *
 * <p>Ordered by how much it changes: {@link #OFFER} and {@link #REJECTION} end the
 * story, {@link #INTERVIEW} and {@link #ASSESSMENT} move it on, and
 * {@link #ACKNOWLEDGEMENT} is the automated "we have received your application"
 * that arrives within a minute and means nothing at all.
 *
 * <p>That last one is the whole reason this enum exists rather than a boolean.
 * Every application produces an acknowledgement, so a classifier that only knows
 * "reply / no reply" reports that every application got a response and clears the
 * follow-up list entirely.
 */
public enum ReplyKind {

    OFFER("Offer", 5),
    REJECTION("Rejected", 4),
    INTERVIEW("Interview", 3),
    ASSESSMENT("OA sent", 2),
    ACKNOWLEDGEMENT(null, 1),
    UNRELATED(null, 0);

    private final String trackerStatus;
    private final int weight;

    ReplyKind(String trackerStatus, int weight) {
        this.trackerStatus = trackerStatus;
        this.weight = weight;
    }

    /** The status to write into the sheet, or null when the reply changes nothing. */
    public String trackerStatus() {
        return trackerStatus;
    }

    /**
     * Which of two replies about the same application wins.
     *
     * <p>A thread routinely holds an acknowledgement, an interview invitation and
     * a rejection. The last message is not reliably the most significant - a
     * "thanks for coming in" can arrive after the rejection - so the strongest
     * outcome wins rather than the newest.
     */
    public int weight() {
        return weight;
    }

    public boolean changesStatus() {
        return trackerStatus != null;
    }
}
