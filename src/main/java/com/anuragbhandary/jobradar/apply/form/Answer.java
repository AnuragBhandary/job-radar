package com.anuragbhandary.jobradar.apply.form;

/**
 * What to put in one field, and where it came from.
 *
 * <p>The provenance is not decoration. It decides three things: whether the
 * application may be submitted without a human, what the review file highlights,
 * and what is safe to reuse next time. An answer copied out of the profile and an
 * answer a language model wrote are both strings, and treating them the same is
 * how a fabricated claim reaches an employer.
 *
 * @param value the text to enter, or the option to select. Never null for an
 *              answered field; always null for {@link Origin#UNANSWERED}.
 */
public record Answer(String value, Origin origin, String note) {

    public enum Origin {
        /** Copied from the profile. Verbatim, and correct by construction. */
        PROFILE,
        /**
         * Computed from the profile and the posting - the sponsorship and
         * authorisation answers, and the salary band for the posting's country.
         * Correct only if the derivation is, so these are shown in the review.
         */
        DERIVED,
        /**
         * Written by a language model: the cover letter, and nothing else.
         * Always shown in full before submission, and never trusted to make a
         * factual claim the profile does not already contain.
         */
        GENERATED,
        /**
         * Deliberately declining to answer an optional question, which is a real
         * answer and not a gap. Used for the voluntary EEO fields that are left
         * unset in the profile.
         */
        DECLINED,
        /**
         * No answer available. If the field is required this stops the
         * application; if it is optional the field is left empty.
         *
         * <p>There is intentionally no fallback that produces something plausible.
         */
        UNANSWERED
    }

    /**
     * A value copied from the profile.
     *
     * <p>A blank one becomes UNANSWERED with a reason rather than a PROFILE answer
     * carrying an empty string. Both leave the field empty, but only one of them
     * says why: the blocked-field list printed "Post Code — null" for an empty
     * postal code, which is the report failing at the one job it has.
     */
    public static Answer profile(String value) {
        return value == null || value.isBlank()
                ? new Answer(null, Origin.UNANSWERED, "not set in applicant.yml")
                : new Answer(value, Origin.PROFILE, null);
    }

    public static Answer derived(String value, String why) {
        return new Answer(value, Origin.DERIVED, why);
    }

    public static Answer generated(String value, String why) {
        return new Answer(value, Origin.GENERATED, why);
    }

    public static Answer declined(String why) {
        return new Answer(null, Origin.DECLINED, why);
    }

    public static Answer unanswered(String why) {
        return new Answer(null, Origin.UNANSWERED, why);
    }

    public boolean hasValue() {
        return value != null && !value.isBlank();
    }

    /** True when a human has to look at this before anything is submitted. */
    public boolean needsReview() {
        return origin == Origin.DERIVED || origin == Origin.GENERATED;
    }
}
