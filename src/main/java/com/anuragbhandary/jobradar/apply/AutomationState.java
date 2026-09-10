package com.anuragbhandary.jobradar.apply;

/**
 * What the browser managed to do with a field, independent of whether the answer
 * was known.
 *
 * <p>The second axis. A field can be {@link FieldState#RESOLVED} and
 * {@link #BLOCKED} at the same time, and that combination is the one the old
 * model could not express: Job Radar knew the answer, the page would not take it,
 * and the attempt was recorded as though the profile were incomplete. The fix for
 * those two situations is completely different, and so is who has to do it.
 */
public enum AutomationState {

    /** No browser has touched this field. The state after a dry resolution. */
    NOT_ATTEMPTED,

    /** Typed in successfully. */
    FILLED,

    /**
     * The answer was known and the page would not take it.
     *
     * <p>A date picker with no text input, an options list that changed, a
     * selector that went stale between reading the form and filling it. The
     * resolved value is <strong>kept</strong> - it is still the right answer, and
     * a person finishing the form by hand needs it.
     */
    BLOCKED,

    /** Nothing to fill: declined, skipped, or a consent box left alone. */
    NOT_APPLICABLE;

    public boolean isBlocked() {
        return this == BLOCKED;
    }
}
