package com.anuragbhandary.jobradar.apply.form;

/**
 * What a form field is asking for, independent of how the board words it.
 *
 * <p>The point of this enum is that {@link FieldClassifier} does the wording, and
 * {@link FieldMapper} does the answering, and neither knows about the other's
 * problem. Greenhouse's "Are you legally authorized to work in the United States?"
 * and Ashby's "Do you have the right to work in Ireland?" are the same question
 * with a different country, and both become {@link #WORK_AUTHORISATION}.
 */
public enum FieldKind {

    FIRST_NAME,
    MIDDLE_NAME,
    LAST_NAME,
    FULL_NAME,
    PREFERRED_NAME,

    EMAIL,
    PHONE,
    PHONE_COUNTRY_CODE,

    ADDRESS_LINE_1,
    ADDRESS_LINE_2,
    CITY,
    STATE,
    POSTAL_CODE,
    COUNTRY,
    NATIONALITY,

    LINKEDIN,
    GITHUB,
    PORTFOLIO,
    OTHER_LINK,

    RESUME_UPLOAD,
    COVER_LETTER_UPLOAD,

    /**
     * A cover letter <em>text box</em>, as opposed to an upload.
     *
     * <p>Kept apart from {@link #COVER_LETTER_UPLOAD} because the instruction is
     * to write a letter only where there is a box to type it into. A form that
     * offers an optional attachment gets no letter.
     */
    COVER_LETTER_TEXT,

    /** "Are you legally authorised to work in X?" - polarity: yes means no permit needed. */
    WORK_AUTHORISATION,
    /** "Will you require sponsorship?" - the inverse of the above, and asked separately. */
    SPONSORSHIP_REQUIRED,
    RELOCATION_WILLING,

    SALARY_EXPECTATION,
    NOTICE_PERIOD,
    START_DATE,

    GENDER,
    RACE,
    HISPANIC_LATINO,
    VETERAN_STATUS,
    DISABILITY_STATUS,
    PRONOUNS,

    /** "How did you hear about us?" and its variants. */
    REFERRAL_SOURCE,

    /**
     * A consent tickbox: privacy policy, data retention, terms.
     *
     * <p>Recognised so it can be reported clearly, and <strong>never ticked</strong>.
     * Agreeing to a company's terms on someone's behalf is not form-filling, and a
     * tool that ticks consent boxes automatically is one that has agreed to things
     * its user has not read. It is left for the human along with the submit button,
     * and the review file says so rather than filing it under "no configured
     * answer" as though the profile were merely incomplete.
     */
    CONSENT,

    /**
     * Recognised as a question, but not one this tool has an answer for.
     *
     * <p>The most important value in the enum. Everything the classifier cannot
     * place lands here, and a required UNKNOWN field stops the application and
     * asks a human - it is never guessed at, never left blank in the hope that it
     * was optional, and never answered with something plausible.
     */
    UNKNOWN
}
