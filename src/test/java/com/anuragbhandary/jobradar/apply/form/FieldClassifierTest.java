package com.anuragbhandary.jobradar.apply.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The wording seen on real Greenhouse, Lever, Ashby and Workday forms. */
class FieldClassifierTest {

    private final FieldClassifier classifier = new FieldClassifier();

    private FieldKind classify(String label) {
        return classify(label, FormField.ControlType.TEXT);
    }

    private FieldKind classify(String label, FormField.ControlType control) {
        return classifier.classify(
                new FormField("#x", label, control, List.of(), false, FieldKind.UNKNOWN));
    }

    @ParameterizedTest
    @CsvSource({
            "First Name *,                            FIRST_NAME",
            "Given name,                              FIRST_NAME",
            "Last Name,                               LAST_NAME",
            "Surname,                                 LAST_NAME",
            "Full name,                               FULL_NAME",
            "Email,                                   EMAIL",
            "Email address *,                         EMAIL",
            "Phone,                                   PHONE",
            "Mobile number,                           PHONE",
            "LinkedIn Profile,                        LINKEDIN",
            "LinkedIn URL,                            LINKEDIN",
            "GitHub,                                  GITHUB",
            "Website,                                 OTHER_LINK",
            "Personal website,                        PORTFOLIO",
            "City,                                    CITY",
            "Current location,                        CITY",
            "Postal Code,                             POSTAL_CODE",
            "PIN code,                                POSTAL_CODE",
            "Nationality,                             NATIONALITY",
            "Expected Salary,                         SALARY_EXPECTATION",
            "Expected CTC,                            SALARY_EXPECTATION",
            "Notice period,                           NOTICE_PERIOD",
            "How did you hear about us?,              REFERRAL_SOURCE",
            "Gender,                                  GENDER",
            "Veteran Status,                          VETERAN_STATUS",
            "Disability Status,                       DISABILITY_STATUS",
            "Are you willing to relocate?,            RELOCATION_WILLING"
    })
    void classifiesCommonLabels(String label, FieldKind expected) {
        assertThat(classify(label)).isEqualTo(expected);
    }

    // -----------------------------------------------------------------------
    // The two questions that must never be confused. Both are auto-reject
    // triggers, they are asked in opposite polarity, and they share vocabulary.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("authorisation and sponsorship are told apart")
    void separatesTheTwoAuthorisationQuestions() {
        assertThat(classify("Are you legally authorized to work in the United States?"))
                .isEqualTo(FieldKind.WORK_AUTHORISATION);
        assertThat(classify("Do you have the right to work in Ireland?"))
                .isEqualTo(FieldKind.WORK_AUTHORISATION);

        assertThat(classify("Will you now or in the future require visa sponsorship?"))
                .isEqualTo(FieldKind.SPONSORSHIP_REQUIRED);
        assertThat(classify("Do you require a work permit for the Netherlands?"))
                .isEqualTo(FieldKind.SPONSORSHIP_REQUIRED);
    }

    @Test
    @DisplayName("'authorised WITHOUT sponsorship' follows authorisation, not sponsorship")
    void combinedQuestionFollowsAuthorisationPolarity() {
        // The trap: it contains "sponsorship", but yes means "I need nothing from
        // you". Reading it as the sponsorship question inverts the answer.
        assertThat(classify(
                "Are you legally authorized to work in the US without sponsorship?"))
                .isEqualTo(FieldKind.WORK_AUTHORISATION);
        assertThat(classify(
                "Can you work in the UK without requiring sponsorship now or in future?"))
                .isEqualTo(FieldKind.WORK_AUTHORISATION);
    }

    // -----------------------------------------------------------------------
    // Cover letters: the widget decides, because the instruction differs.
    // -----------------------------------------------------------------------

    @Test
    void coverLetterTextBoxAndUploadAreDifferentKinds() {
        assertThat(classify("Cover Letter", FormField.ControlType.TEXTAREA))
                .isEqualTo(FieldKind.COVER_LETTER_TEXT);
        assertThat(classify("Cover Letter", FormField.ControlType.FILE))
                .isEqualTo(FieldKind.COVER_LETTER_UPLOAD);
    }

    @Test
    @DisplayName("Lever's 'paste your resume' textarea is left alone")
    void resumeTextareaIsNotTheUpload() {
        // Filling both it and the file input submits the document twice.
        assertThat(classify("Resume", FormField.ControlType.FILE))
                .isEqualTo(FieldKind.RESUME_UPLOAD);
        assertThat(classify("Resume", FormField.ControlType.TEXTAREA))
                .isEqualTo(FieldKind.UNKNOWN);
    }

    // -----------------------------------------------------------------------
    // Ordering traps.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("'Email address' is an email, not a street address")
    void emailBeatsAddress() {
        assertThat(classify("Email Address")).isEqualTo(FieldKind.EMAIL);
        assertThat(classify("Street Address")).isEqualTo(FieldKind.ADDRESS_LINE_1);
    }

    @Test
    @DisplayName("the US EEO form's Hispanic question is not the race question")
    void hispanicBeatsRace() {
        assertThat(classify("Are you Hispanic or Latino?"))
                .isEqualTo(FieldKind.HISPANIC_LATINO);
        assertThat(classify("Race / Ethnicity")).isEqualTo(FieldKind.RACE);
    }

    @Test
    @DisplayName("an unrecognised question is UNKNOWN, never a near miss")
    void unknownRatherThanGuessing() {
        assertThat(classify("What is your favourite database, and why?"))
                .isEqualTo(FieldKind.UNKNOWN);
        assertThat(classify("")).isEqualTo(FieldKind.UNKNOWN);
        assertThat(classify("Describe a system you have designed"))
                .isEqualTo(FieldKind.UNKNOWN);
    }
}
