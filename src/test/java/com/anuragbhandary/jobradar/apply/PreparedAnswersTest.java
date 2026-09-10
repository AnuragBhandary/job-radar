package com.anuragbhandary.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.form.Answer;
import com.anuragbhandary.jobradar.apply.form.FormField;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What survives an application stopping.
 *
 * <p>Preparation stops whenever it needs a person, and the browser closes with
 * it. The value of answering the question that blocked it is entirely in the
 * answer being there on the next run - and the danger is carrying back something
 * that was never settled.
 */
class PreparedAnswersTest {

    private static ApplicationField field(String label, FieldState state, String value) {
        ApplicationField field = new ApplicationField(1L, label, state);
        field.setResolvedValue(value);
        return field;
    }

    private static FormField question(String label) {
        return new FormField("#q", label, FormField.ControlType.TEXT, List.of(), true, null);
    }

    @Test
    @DisplayName("a settled answer goes back in on the next run")
    void settledAnswersAreCarried() {
        PreparedAnswers settled = PreparedAnswers.from(List.of(
                field("Do you have a valid passport?", FieldState.RESOLVED, "Yes")));

        assertThat(settled.forField(question("Do you have a valid passport?")))
                .get().extracting(Answer::value).isEqualTo("Yes");
    }

    @Test
    @DisplayName("a draft awaiting approval does not, and neither does an open question")
    void unsettledAnswersAreNotCarried() {
        PreparedAnswers settled = PreparedAnswers.from(List.of(
                field("Why do you want to work here?", FieldState.AWAITING_APPROVAL,
                        "Because I admire the work."),
                field("Kubernetes experience?", FieldState.AWAITING_ANSWER, null),
                field("Gender", FieldState.DECLINED, null),
                field("Referral", FieldState.SKIPPED_OPTIONAL, null)));

        assertThat(settled.isEmpty()).isTrue();
        assertThat(settled.forField(question("Why do you want to work here?"))).isEmpty();
    }

    @Test
    @DisplayName("the question is matched however the board capitalised it")
    void matchingIgnoresCaseAndPadding() {
        PreparedAnswers settled = PreparedAnswers.from(List.of(
                field("  Notice period  ", FieldState.RESOLVED, "30 days")));

        assertThat(settled.forField(question("NOTICE PERIOD"))).isPresent();
    }

    @Test
    @DisplayName("a field with no label falls back to the selector, like the recorder does")
    void unlabelledFieldsUseTheSelector() {
        PreparedAnswers settled = PreparedAnswers.from(List.of(
                field("#custom-7", FieldState.RESOLVED, "Yes")));

        FormField unlabelled = new FormField("#custom-7", "",
                FormField.ControlType.TEXT, List.of(), true, null);
        assertThat(settled.forField(unlabelled)).isPresent();
    }

    @Test
    @DisplayName("nothing settled is not the same as an empty answer")
    void noneIsEmpty() {
        assertThat(PreparedAnswers.none().isEmpty()).isTrue();
        assertThat(PreparedAnswers.none().forField(question("anything"))).isEmpty();
    }
}
