package com.anuragbhandary.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerBankTest {

    private static ApplicationAttempt attemptAsking(OpenQuestion... questions) {
        ApplicationAttempt attempt = new ApplicationAttempt(1L, "Acme", "Backend Engineer");
        attempt.setOpenQuestions(OpenQuestion.serialise(List.of(questions)));
        return attempt;
    }

    private static OpenQuestion question(String label, boolean required, String... options) {
        return new OpenQuestion(label, "TEXT", required, List.of(options));
    }

    @Test
    @DisplayName("the same question worded differently is one suggestion")
    void groupsAcrossBoards() {
        // Grouping on the exact label produces a list as long as the input, which
        // is the report doing no work at all.
        List<AnswerBank.Suggestion> suggestions = AnswerBank.suggest(List.of(
                attemptAsking(question("Are you at least 18 years of age?", true)),
                attemptAsking(question("Are you at least 18 years old?", true)),
                attemptAsking(question("Are you at least 18 years of age? *", true))));

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.getFirst().timesSeen()).isEqualTo(3);
        assertThat(suggestions.getFirst().key()).isEqualTo("are you at least 18 years");
    }

    @Test
    @DisplayName("required questions rank above optional ones, then by frequency")
    void ranksByCost() {
        List<AnswerBank.Suggestion> suggestions = AnswerBank.suggest(List.of(
                attemptAsking(
                        question("What is your favourite database?", false),
                        question("What is your favourite database?", false),
                        question("What is your favourite database?", false)),
                attemptAsking(question("Do you have a valid passport?", true))));

        assertThat(suggestions).extracting(AnswerBank.Suggestion::key)
                .containsExactly("do you have a valid passport", "what is your favourite database");
    }

    @Test
    @DisplayName("a question required on one board and optional on another counts as required")
    void requiredAnywhereIsRequired() {
        List<AnswerBank.Suggestion> suggestions = AnswerBank.suggest(List.of(
                attemptAsking(question("Notice period?", false)),
                attemptAsking(question("Notice period?", true))));

        assertThat(suggestions.getFirst().required()).isTrue();
    }

    @Test
    @DisplayName("only a decline is ever proposed as an answer")
    void neverProposesASubstantiveAnswer() {
        // Proposing anything else would be the tool deciding what the applicant's
        // answer to an unseen question is.
        List<AnswerBank.Suggestion> withDecline = AnswerBank.suggest(List.of(
                attemptAsking(question("Veteran status", true,
                        "Yes", "No", "I prefer not to answer"))));
        assertThat(withDecline.getFirst().proposedAnswer()).contains("I prefer not to answer");

        List<AnswerBank.Suggestion> withoutDecline = AnswerBank.suggest(List.of(
                attemptAsking(question("Do you have a valid passport?", true, "Yes", "No"))));
        assertThat(withoutDecline.getFirst().proposedAnswer()).isEmpty();
    }

    @Test
    void yamlCarriesTheOptionsAndTheCost() {
        String yaml = AnswerBank.toYaml(AnswerBank.suggest(List.of(
                attemptAsking(question("Do you have a valid passport?", true, "Yes", "No")))));

        assertThat(yaml)
                .contains("\"do you have a valid passport\"")
                .contains("REQUIRED - blocks the form")
                .contains("options: Yes | No");
    }

    @Test
    void emptyInputProducesNothing() {
        assertThat(AnswerBank.suggest(List.of())).isEmpty();
        assertThat(AnswerBank.toYaml(List.of())).isEmpty();
    }

    // -----------------------------------------------------------------------
    // The log format.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a tab inside a label does not corrupt the log")
    void serialisationSurvivesAwkwardLabels() {
        List<OpenQuestion> parsed = OpenQuestion.parse(OpenQuestion.serialise(List.of(
                new OpenQuestion("Why\tthis\nrole?", "TEXTAREA", true, List.of()))));

        assertThat(parsed).hasSize(1);
        assertThat(parsed.getFirst().label()).isEqualTo("Why this role?");
    }

    @Test
    @DisplayName("a malformed line is skipped, not thrown")
    void parsingIsLenient() {
        // This is a log of things that already went wrong; a parse failure must
        // not be a second failure on top of the first.
        assertThat(OpenQuestion.parse("broken line with no tabs\n")).isEmpty();
        assertThat(OpenQuestion.parse(null)).isEmpty();
        assertThat(OpenQuestion.parse("")).isEmpty();
    }

    @Test
    void optionsRoundTrip() {
        List<OpenQuestion> parsed = OpenQuestion.parse(OpenQuestion.serialise(List.of(
                new OpenQuestion("Gender", "SELECT", false,
                        List.of("Male", "Female", "Prefer not to say")))));

        assertThat(parsed.getFirst().options())
                .containsExactly("Male", "Female", "Prefer not to say");
    }
}
