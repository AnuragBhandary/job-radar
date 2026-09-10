package com.anuragbhandary.jobradar.apply;

/**
 * What is known about one field's answer.
 *
 * <p>Deliberately says nothing about browsers. Whether a value could be typed
 * into a page is {@link AutomationState}, and keeping the two apart is the whole
 * point of this phase: before it, a required question with no answer, a captcha,
 * a page with no form and a selector that went stale were all
 * {@code AttemptStatus.NEEDS_HUMAN}, told apart by a {@code startsWith} on a
 * prose string.
 *
 * <p>They are different problems with different fixes. One is answered by
 * telling Job Radar something; the other is answered by opening a browser.
 */
public enum FieldState {

    /** A value is settled and may be used. */
    RESOLVED,

    /**
     * There is a proposed value that a human has to accept first.
     *
     * <p>Two things land here: an AI draft, and a conflict between two equally
     * applicable stored answers. Both have a value; neither has a decision.
     */
    AWAITING_APPROVAL,

    /** Nothing trustworthy applies, and the field is required. */
    AWAITING_ANSWER,

    /**
     * Nothing applies, and the field is optional.
     *
     * <p>Its own state so an unanswerable optional question does not block an
     * application it was never going to block. Still recorded as a sighting -
     * frequency across boards is what makes it worth answering later.
     */
    SKIPPED_OPTIONAL,

    /**
     * Deliberately left blank, which is a real answer.
     *
     * <p>Voluntary self-identification the profile leaves unset, and consent
     * boxes, which are recognised precisely so they can be left alone.
     */
    DECLINED;

    /** True when a human has to do something before this application can go. */
    public boolean needsHuman() {
        return this == AWAITING_APPROVAL || this == AWAITING_ANSWER;
    }

    public boolean hasSettledValue() {
        return this == RESOLVED;
    }
}
