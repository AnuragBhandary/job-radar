package com.anuragbhandary.jobradar.apply.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.ApplicationDocuments;
import com.anuragbhandary.jobradar.apply.TestProfiles;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FieldMapperTest {

    private final FieldMapper mapper = new FieldMapper(TestProfiles.indianApplicant());

    private static final ApplicationDocuments DOCS =
            new ApplicationDocuments(Path.of("/tmp/resume.pdf"), "A letter.", "note");
    private static final ApplicationDocuments NO_LETTER =
            new ApplicationDocuments(Path.of("/tmp/resume.pdf"), null, "note");

    private static Posting postingIn(Country country) {
        Posting posting = new Posting(Source.GREENHOUSE, "acme", "1", "Backend Engineer");
        posting.setCountry(country);
        posting.setLocation("Somewhere");
        return posting;
    }

    private static FormField field(FieldKind kind, String label) {
        return new FormField("#x", label, FormField.ControlType.TEXT, List.of(), false, kind);
    }

    private static FormField choice(FieldKind kind, String label, List<String> options) {
        return new FormField("#x", label, FormField.ControlType.SELECT, options, true, kind);
    }

    // -----------------------------------------------------------------------
    // The two answers this class exists to get right.
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "INDIA,        Yes, No",
            "REMOTE,       Yes, No",
            "GERMANY,      No,  Yes",
            "IRELAND,      No,  Yes",
            "NETHERLANDS,  No,  Yes"
    })
    @DisplayName("authorisation and sponsorship invert with the posting's country")
    void authorisationFollowsTheCountry(
            Country country, String expectedAuthorised, String expectedSponsorship) {

        Posting posting = postingIn(country);

        assertThat(mapper.answer(
                field(FieldKind.WORK_AUTHORISATION, "Authorised to work?"), posting, DOCS)
                .value()).isEqualTo(expectedAuthorised);

        assertThat(mapper.answer(
                field(FieldKind.SPONSORSHIP_REQUIRED, "Need sponsorship?"), posting, DOCS)
                .value()).isEqualTo(expectedSponsorship);
    }

    @Test
    @DisplayName("both derived answers are flagged for review, never filled silently")
    void derivedAnswersAreMarked() {
        Answer answer = mapper.answer(
                field(FieldKind.SPONSORSHIP_REQUIRED, "Need sponsorship?"),
                postingIn(Country.GERMANY), DOCS);

        assertThat(answer.origin()).isEqualTo(Answer.Origin.DERIVED);
        assertThat(answer.needsReview()).isTrue();
        assertThat(answer.note()).contains("GERMANY");
    }

    @Test
    @DisplayName("the long-form dropdown wording is matched, not typed")
    void resolvesAgainstRealOptions() {
        Answer answer = mapper.answer(
                choice(FieldKind.SPONSORSHIP_REQUIRED, "Sponsorship?",
                        List.of("Yes, I will now or in the future require sponsorship",
                                "No, I do not require sponsorship")),
                postingIn(Country.INDIA), DOCS);

        assertThat(answer.value()).isEqualTo("No, I do not require sponsorship");
    }

    // -----------------------------------------------------------------------
    // Cover letters.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a letter goes in a text box and never in an upload slot")
    void coverLetterOnlyForTextBoxes() {
        Posting posting = postingIn(Country.INDIA);

        assertThat(mapper.answer(
                field(FieldKind.COVER_LETTER_TEXT, "Cover letter"), posting, DOCS).value())
                .isEqualTo("A letter.");

        Answer upload = mapper.answer(
                field(FieldKind.COVER_LETTER_UPLOAD, "Cover letter"), posting, DOCS);
        assertThat(upload.origin()).isEqualTo(Answer.Origin.UNANSWERED);
        assertThat(upload.note()).contains("upload");
    }

    @Test
    void textBoxWithNoDraftedLetterIsLeftEmpty() {
        assertThat(mapper.answer(
                field(FieldKind.COVER_LETTER_TEXT, "Cover letter"),
                postingIn(Country.INDIA), NO_LETTER).origin())
                .isEqualTo(Answer.Origin.UNANSWERED);
    }

    // -----------------------------------------------------------------------
    // Salary.
    // -----------------------------------------------------------------------

    /** A required text field, which is what forces a figure to be stated. */
    private static FormField requiredText(FieldKind kind, String label) {
        return new FormField("#x", label, FormField.ControlType.TEXT, List.of(), true, kind);
    }

    @Test
    @DisplayName("a required salary field is answered in the posting country's currency")
    void salaryFollowsTheCountry() {
        assertThat(mapper.answer(
                requiredText(FieldKind.SALARY_EXPECTATION, "Expected salary"),
                postingIn(Country.GERMANY), DOCS).value())
                .contains("EUR").contains("55,000").contains("65,000");

        assertThat(mapper.answer(
                requiredText(FieldKind.SALARY_EXPECTATION, "Expected salary"),
                postingIn(Country.INDIA), DOCS).value())
                .contains("INR").contains("1,200,000").doesNotContain("EUR");
    }

    @Test
    @DisplayName("an optional free-text salary box gets the sentence, not a number")
    void optionalSalaryFieldDoesNotNameAFigure() {
        // Naming a number before the employer does is a negotiating loss, and an
        // optional box does not force it.
        Answer answer = mapper.answer(
                field(FieldKind.SALARY_EXPECTATION, "Expected salary"),
                postingIn(Country.INDIA), DOCS);

        assertThat(answer.value()).isEqualTo("Open on compensation.");
    }

    @Test
    @DisplayName("a country with no band configured falls back to the India band")
    void missingBandFallsBackRatherThanFailing() {
        assertThat(mapper.answer(
                requiredText(FieldKind.SALARY_EXPECTATION, "Expected salary"),
                postingIn(Country.IRELAND), DOCS).value())
                .contains("INR");
    }

    @Test
    @DisplayName("a numeric-only salary box gets digits, not a sentence")
    void numericSalaryFieldGetsANumber() {
        assertThat(mapper.answer(
                field(FieldKind.SALARY_EXPECTATION, "Expected CTC amount"),
                postingIn(Country.INDIA), DOCS).value())
                .isEqualTo("1800000");
    }

    // -----------------------------------------------------------------------
    // Voluntary questions, and the unanswerable ones.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("an unset voluntary field declines rather than being skipped")
    void unsetDemographicsDecline() {
        Answer answer = mapper.answer(
                choice(FieldKind.DISABILITY_STATUS, "Disability status",
                        List.of("Yes", "No", "I do not wish to answer")),
                postingIn(Country.INDIA), DOCS);

        assertThat(answer.origin()).isEqualTo(Answer.Origin.DECLINED);
        assertThat(answer.value()).isEqualTo("I do not wish to answer");
    }

    @Test
    void configuredExtraAnswersAreUsed() {
        assertThat(mapper.answer(
                field(FieldKind.REFERRAL_SOURCE, "How did you hear about us?"),
                postingIn(Country.INDIA), DOCS).value())
                .isEqualTo("Company careers page");

        assertThat(mapper.answer(
                field(FieldKind.UNKNOWN, "Are you at least 18 years old?"),
                postingIn(Country.INDIA), DOCS).value())
                .isEqualTo("Yes");
    }

    @Test
    @DisplayName("an unknown question is left unanswered and names itself")
    void unknownQuestionsAreNotInvented() {
        Answer answer = mapper.answer(
                field(FieldKind.UNKNOWN, "Describe your proudest technical achievement"),
                postingIn(Country.INDIA), DOCS);

        assertThat(answer.origin()).isEqualTo(Answer.Origin.UNANSWERED);
        assertThat(answer.note()).contains("proudest technical achievement");
    }

    @Test
    @DisplayName("a profile field left blank says so, rather than reporting null")
    void blankProfileValuesExplainThemselves() {
        // The blocked list printed "Post Code — null" for an empty postal code,
        // which is the report failing at the one job it has.
        Answer answer = mapper.answer(
                field(FieldKind.PRONOUNS, "Pronouns"), postingIn(Country.INDIA), DOCS);

        assertThat(answer.origin()).isEqualTo(Answer.Origin.DECLINED);
        assertThat(answer.note()).isNotNull();

        Answer missing = Answer.profile("");
        assertThat(missing.origin()).isEqualTo(Answer.Origin.UNANSWERED);
        assertThat(missing.note()).contains("applicant.yml");
    }

    @Test
    @DisplayName("a consent box is never ticked automatically")
    void consentIsLeftForTheHuman() {
        // Agreeing to a company's terms on someone's behalf is not form-filling.
        Answer answer = mapper.answer(
                new FormField("#x", "I agree to the privacy policy",
                        FormField.ControlType.CHECKBOX, List.of(), true, FieldKind.CONSENT),
                postingIn(Country.INDIA), DOCS);

        assertThat(answer.origin()).isEqualTo(Answer.Origin.UNANSWERED);
        assertThat(answer.note()).contains("tick it yourself");
    }

    @Test
    @DisplayName("a configured extra answer resolves a classified field whose options do not fit")
    void extraAnswersAlsoRescueClassifiedFields() {
        // A question this classifies correctly can still offer bespoke options no
        // derived yes/no fits. Without the fallback the only way to answer it
        // would be to stop having it classified at all.
        Answer answer = mapper.answer(
                choice(FieldKind.WORK_AUTHORISATION,
                        "If you are eligible, please select the status that allows you "
                                + "to work and live in that Country",
                        List.of("I am a citizen / permanent resident",
                                "I have a work visa",
                                "I require sponsorship")),
                postingIn(Country.REMOTE), DOCS);

        assertThat(answer.value()).isEqualTo("I am a citizen / permanent resident");
    }

    @Test
    @DisplayName("an answer matching no option is refused rather than approximated")
    void unmatchedChoiceIsRefused() {
        Answer answer = mapper.answer(
                choice(FieldKind.GENDER, "Gender", List.of("Woman", "Non-binary")),
                postingIn(Country.INDIA), DOCS);

        assertThat(answer.origin()).isEqualTo(Answer.Origin.UNANSWERED);
        assertThat(answer.note()).contains("matched none of");
    }
}
