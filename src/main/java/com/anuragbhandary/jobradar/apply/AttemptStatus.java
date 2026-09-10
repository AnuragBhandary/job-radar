package com.anuragbhandary.jobradar.apply;

/**
 * How far one application got, and what is standing in its way.
 *
 * <p>{@link #NEEDS_HUMAN} used to cover five unrelated situations - a required
 * question with no answer, a captcha, a page with no form, a stale selector, and
 * a consent box - told apart by testing whether a prose sentence began with "No
 * form found". They are different problems, they are fixed by different people
 * doing different things, and they now have different states.
 *
 * <p>This is <strong>preparation</strong> state. Where a job has got to with the
 * employer is {@link com.anuragbhandary.jobradar.pipeline.PipelineStage}, and the
 * two stay separate: an application can be SUBMITTED here and INTERVIEW there.
 */
public enum AttemptStatus {

    /** A browser is open and working. The state during a prepare run. */
    PREPARING,

    /**
     * Filled, nothing outstanding, waiting to be read and sent.
     *
     * <p>The state {@link #PREPARED} meant when nothing had gone wrong. Kept
     * apart from it so "prepared" can retire without rewriting history.
     */
    READY_FOR_REVIEW,

    /** At least one field has a proposed answer, or a conflict, needing a decision. */
    AWAITING_APPROVAL,

    /**
     * At least one required field has no trustworthy answer.
     *
     * <p>The valuable one: answering it does not only unblock this application,
     * it teaches Job Radar something every future form asking the same thing will
     * use.
     */
    AWAITING_ANSWER,

    /**
     * The website needs a person, not a browser.
     *
     * <p>The answers are still good - see {@code ApplicationAttempt.manualReason}
     * for which of the six situations this is, and whether it is worth retrying.
     */
    MANUAL_REQUIRED,

    /**
     * Documents rendered, form filled, nothing sent.
     *
     * <p>Legacy: what every clean preparation was called before the states above
     * existed. Still written by nothing, still read everywhere, and still on
     * rows in the database - which is why it stays.
     */
    PREPARED,

    /**
     * Legacy catch-all, replaced by the four states above.
     *
     * <p>Kept because five rows in the real database carry it and rewriting an
     * attempt's history to fit a newer model is exactly the kind of quiet
     * revision this project does not do.
     */
    NEEDS_HUMAN,

    /** Sent. The only status that is irreversible, and the only one that writes to the tracker. */
    SUBMITTED,

    /** The attempt threw before it finished. The reason is on the record. */
    FAILED,

    /** Deliberately passed over, with a reason - e.g. already applied to this company. */
    SKIPPED;

    /** True while something is waiting on the applicant. */
    public boolean needsHuman() {
        return this == AWAITING_APPROVAL || this == AWAITING_ANSWER
                || this == MANUAL_REQUIRED || this == NEEDS_HUMAN;
    }

    /** True when the work is done and only sending is left. */
    public boolean isReadyToSend() {
        return this == READY_FOR_REVIEW || this == PREPARED;
    }

    /** True for the states that predate the split and are never written now. */
    public boolean isLegacy() {
        return this == NEEDS_HUMAN || this == PREPARED;
    }
}
