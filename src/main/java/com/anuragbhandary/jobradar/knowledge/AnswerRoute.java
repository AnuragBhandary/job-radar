package com.anuragbhandary.jobradar.knowledge;

/**
 * How a field should be answered, decided once and read by everything downstream.
 *
 * <p>Exists so the browser phase can ask one question - "can this be filled
 * without him?" - and get an answer that does not depend on re-deriving the
 * reasoning. It is also the summary of the rule this phase is built on: a field
 * reaches {@link #USER_REQUIRED} because nothing could answer it, never because a
 * keyword was missing from a resume.
 */
public enum AnswerRoute {

    /**
     * Settled, confident, and safe to type in without being read first.
     *
     * <p>A profile fact, or a derivation at high confidence on a concept that
     * permits it. Nothing prose-shaped is ever here: an answer written for an
     * employer is read by him before it goes, whatever the confidence.
     */
    AUTO_RESOLVE("Fills itself"),

    /**
     * A model may draft it, and he approves it before it is used.
     *
     * <p>Open-ended questions, and technology questions positioned from adjacent
     * evidence. The approval barrier stays where it is: this phase made the
     * system able to answer more, not able to send more without being read.
     */
    AI_PROPOSE("Drafted for your approval"),

    /**
     * Only he can answer it.
     *
     * <p>A missing fact, a legal declaration, a personal preference, or a
     * conflict between two things he has said. The list is deliberately short -
     * before this phase it also contained every technology his resume did not
     * name by the exact word the form used.
     */
    USER_REQUIRED("Needs your answer"),

    /** Nothing to answer: a consent box, a voluntary field left blank on purpose. */
    NOT_APPLICABLE("Nothing to answer");

    private final String label;

    AnswerRoute(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean needsHuman() {
        return this == USER_REQUIRED || this == AI_PROPOSE;
    }
}
