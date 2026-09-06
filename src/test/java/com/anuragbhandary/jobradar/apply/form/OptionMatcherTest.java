package com.anuragbhandary.jobradar.apply.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OptionMatcherTest {

    private static final List<String> SPONSORSHIP = List.of(
            "Yes, I will now or in the future require sponsorship",
            "No, I do not require sponsorship");

    private static final List<String> EEO_RACE = List.of(
            "Asian (Not Hispanic or Latino)",
            "White (Not Hispanic or Latino)",
            "Black or African American (Not Hispanic or Latino)",
            "I do not wish to answer");

    @Test
    @DisplayName("Yes and No reach the right long-form option")
    void polarityIsRespected() {
        assertThat(OptionMatcher.match("Yes", SPONSORSHIP))
                .contains("Yes, I will now or in the future require sponsorship");
        assertThat(OptionMatcher.match("No", SPONSORSHIP))
                .contains("No, I do not require sponsorship");
    }

    @Test
    @DisplayName("'No' does not match the 'now' inside the Yes option")
    void noDoesNotMatchNow() {
        // Substring matching picks "Yes, I will NOw..." for the answer "No", which
        // submits the opposite of the truth on an auto-reject question.
        assertThat(OptionMatcher.match("No", SPONSORSHIP).orElseThrow())
                .startsWith("No,");
    }

    @Test
    @DisplayName("a race answer matches an option that contains 'Not'")
    void negationGuardDoesNotBreakNonPolarAnswers() {
        // The polarity guard must not fire here: "Asian (Not Hispanic or Latino)"
        // contains a negation and is still the right answer for "Asian".
        assertThat(OptionMatcher.match("Asian", EEO_RACE))
                .contains("Asian (Not Hispanic or Latino)");
    }

    @Test
    void findsTheDeclineOption() {
        assertThat(OptionMatcher.declineOption(EEO_RACE)).contains("I do not wish to answer");
        assertThat(OptionMatcher.declineOption(List.of("Male", "Female")))
                .isEmpty();
        assertThat(OptionMatcher.declineOption(
                List.of("Male", "Female", "Prefer not to disclose")))
                .contains("Prefer not to disclose");
    }

    @Test
    @DisplayName("no match is empty, never the closest option")
    void refusesRatherThanApproximating() {
        assertThat(OptionMatcher.match("Maybe", SPONSORSHIP)).isEmpty();
        assertThat(OptionMatcher.match("Non-binary", List.of("Male", "Female"))).isEmpty();
        assertThat(OptionMatcher.match("Yes", List.of())).isEmpty();
    }

    @Test
    @DisplayName("two options containing the answer is a refusal, not a coin toss")
    void ambiguityIsRefused() {
        assertThat(OptionMatcher.match("Engineer",
                List.of("Software Engineer", "Hardware Engineer"))).isEmpty();
    }

    @Test
    void exactMatchWinsRegardlessOfCaseAndPunctuation() {
        assertThat(OptionMatcher.match("male", List.of("Male", "Female"))).contains("Male");
        assertThat(OptionMatcher.match("Yes", List.of("yes.", "no."))).contains("yes.");
    }
}
