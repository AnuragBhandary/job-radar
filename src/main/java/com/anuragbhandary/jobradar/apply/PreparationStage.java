package com.anuragbhandary.jobradar.apply;

/**
 * Which part of preparation is running right now.
 *
 * <p>The second half of a pair. {@link AttemptStatus} says <em>why an attempt
 * stopped</em> and survives forever; this says <em>what it is doing</em> and is
 * only interesting while it does it. Before this phase the two were the same
 * question, which is why a preparation that took forty seconds of browser work
 * showed nothing at all until it was over - the request blocked, the page did not
 * exist yet, and there was no vocabulary for "opening the form".
 *
 * <p>Nothing here duplicates {@link AttemptStatus}. The states the plan lists as
 * AWAITING_APPROVAL, AWAITING_ANSWER, MANUAL_REQUIRED, READY_FOR_REVIEW and
 * FAILED are outcomes, they already exist there, and a preparation that has
 * reached one of them is at {@link #FINISHED} here. {@link AttemptStatus#PREPARING}
 * is what an attempt's status reads while any stage below is running.
 */
public enum PreparationStage {

    /** Accepted and waiting for the worker. Preparation runs one at a time. */
    QUEUED("Queued", "waiting for the browser to be free"),

    /** Choosing and rendering the resume. No browser yet, so a failure is cheap. */
    TAILORING("Tailoring the resume", "picking the summary and rendering the PDF"),

    /** Launching Chromium and navigating to the posting. */
    OPENING("Opening the application", "launching the browser and loading the form"),

    /** Reading the fields off the page, and revealing the form if it is behind a button. */
    READING("Reading the form", "finding out what this board is asking"),

    /** Working out an answer for each field, and drafting the letter if there is a box. */
    RESOLVING("Working out the answers", "matching each question to what is known"),

    /** Typing into the page. Nothing on this path can submit. */
    FILLING("Filling the form", "entering the answers that are settled"),

    /** Screenshot taken, review file written, structured field rows saved. */
    CAPTURED("Recording what happened", "saving the screenshot and the field record"),

    /**
     * Nothing is running. The attempt's {@link AttemptStatus} says how it went.
     *
     * <p>Not called "done": an attempt can finish this stage and still need three
     * things from a person.
     */
    FINISHED("Finished", "nothing is running"),

    /** The run threw. {@code ApplicationAttempt.blockerReason} says what. */
    FAILED("Stopped", "preparation could not continue");

    private final String label;
    private final String detail;

    PreparationStage(String label, String detail) {
        this.label = label;
        this.detail = detail;
    }

    /** Present tense, for the line under a progress bar. */
    public String label() {
        return label;
    }

    public String detail() {
        return detail;
    }

    /** True while work is still happening, so a screen knows whether to keep polling. */
    public boolean isRunning() {
        return this != FINISHED && this != FAILED;
    }

    /**
     * How far along, out of the stages that actually do work.
     *
     * <p>{@link #QUEUED} is step zero because nothing has happened yet, and the
     * two terminal stages are the full count. Used to draw a bar, and deliberately
     * not a percentage of time: {@link #FILLING} takes longer than the other six
     * put together and a linear bar would sit still for most of the run.
     */
    public int step() {
        return switch (this) {
            case QUEUED -> 0;
            case FINISHED, FAILED -> steps();
            default -> ordinal();
        };
    }

    /** The number of working stages, so a bar has a denominator. */
    public static int steps() {
        return CAPTURED.ordinal();
    }
}
