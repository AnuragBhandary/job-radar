package com.anuragbhandary.jobradar.knowledge.experience;

/**
 * One place the applicant's own material names a technology.
 *
 * <p>Kept rather than counted, because the citation is what makes a generated
 * answer checkable. "I've worked with Kafka" is a claim; "I've worked with Kafka"
 * plus {@code RESUME_EXPERIENCE / <the job>} is a claim with a receipt, and
 * {@link ClaimValidator} refuses anything without one.
 *
 * @param display how the resume writes it, so the answer uses his words
 * @param where   the job or project it appeared in, for the sentence to name
 */
public record ExperienceEvidence(String display, Source source, String where, Depth depth) {

    /** Which part of his material this came from. */
    public enum Source {
        RESUME_SKILLS,
        RESUME_EXPERIENCE,
        PROJECT_STACK,
        PROJECT_TAGS,
        PROJECT_BULLET
    }

    /**
     * How much weight a mention carries. Ordinal order is the ranking.
     *
     * <p>Declared strongest first so the natural sort on {@code ordinal()} is the
     * one an interviewer would apply: something used at work outranks something
     * built at home, and both outrank a word in a skills list.
     */
    public enum Depth {
        /** Named in a bullet describing paid work. */
        PROFESSIONAL("professional work"),
        /** Named in a project's stack, tags or bullets. */
        PROJECT("a project"),
        /** In the skills list and nowhere else. The weakest honest evidence. */
        LISTED("the skills list");

        private final String phrase;

        Depth(String phrase) {
            this.phrase = phrase;
        }

        /** How an answer would refer to it. */
        public String phrase() {
            return phrase;
        }
    }

    /** "Kafka - professional work at <the job>". One line, for a report or a prompt. */
    public String describe() {
        return display + " — " + depth.phrase()
                + (where == null || where.isBlank() ? "" : " (" + where + ")");
    }
}
