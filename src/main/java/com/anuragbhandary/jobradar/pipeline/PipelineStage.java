package com.anuragbhandary.jobradar.pipeline;

import java.util.List;

/**
 * Where a job has got to, as the applicant sees it.
 *
 * <p>Distinct from {@link com.anuragbhandary.jobradar.apply.AttemptStatus}, which
 * records what the automation did, and from the tracker's free-text status column,
 * which is what a human typed into a spreadsheet. This is the one ordered pipeline
 * the board renders, and the other two feed it.
 */
public enum PipelineStage {

    /** Bookmarked, nothing done. The step that did not exist before. */
    SAVED("Saved", false),
    /** A form has been filled and is waiting to be read. */
    PREPARED("Prepared", false),
    /** Sent. From here on the company is moving it, not you. */
    APPLIED("Applied", false),
    SCREENING("Screening", false),
    INTERVIEW("Interview", false),
    OFFER("Offer", true),
    REJECTED("Rejected", true),
    /** Withdrawn, or decided against. Ends the story without a rejection. */
    DROPPED("Dropped", true);

    private final String label;
    private final boolean terminal;

    PipelineStage(String label, boolean terminal) {
        this.label = label;
        this.terminal = terminal;
    }

    public String label() {
        return label;
    }

    /** Nothing further will happen. Terminal stages leave the board's live columns. */
    public boolean isTerminal() {
        return terminal;
    }

    /** The columns the board shows, left to right. */
    public static List<PipelineStage> live() {
        return List.of(SAVED, PREPARED, APPLIED, SCREENING, INTERVIEW);
    }

    public static List<PipelineStage> closed() {
        return List.of(OFFER, REJECTED, DROPPED);
    }

    /**
     * The stage a tracker status word means.
     *
     * <p>The spreadsheet predates this enum and its status column is hand-typed,
     * so it holds "Applied", "OA sent", "Round 2" and "Ghosted". Reusing
     * {@link com.anuragbhandary.jobradar.followup.ApplicationStage}'s vocabulary
     * would flatten all of those to three values; this keeps the distinctions the
     * board needs while still refusing to guess at a word it does not know.
     */
    public static PipelineStage fromTrackerStatus(String status) {
        if (status == null || status.isBlank()) {
            return APPLIED;
        }
        String text = status.toLowerCase(java.util.Locale.ROOT);
        if (contains(text, "offer", "accepted", "hired", "joined")) {
            return OFFER;
        }
        if (contains(text, "reject", "declined", "unsuccessful", "ghosted", "no response")) {
            return REJECTED;
        }
        // "withdr", not "withdraw": withdrew is not a superstring of withdraw,
        // and both are what a person actually types.
        if (contains(text, "withdr", "dropped", "not pursuing")) {
            return DROPPED;
        }
        if (contains(text, "interview", "onsite", "round", "final", "hr round")) {
            return INTERVIEW;
        }
        if (contains(text, "screen", "oa", "assessment", "test", "phone", "recruiter")) {
            return SCREENING;
        }
        return APPLIED;
    }

    private static boolean contains(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
