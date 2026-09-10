package com.anuragbhandary.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The summary line, and the promise that its numbers add up.
 *
 * <p>"18 fields prepared, 2 need approval, 1 needs manual completion" is only
 * useful if a person can trust it, and the way that kind of line goes wrong is
 * double-counting: a field that is both resolved and blocked appearing under both
 * headings, so the parts exceed the whole and nobody notices until they try to
 * reconcile them.
 */
class ReadinessTest {

    private final List<ApplicationField> fields = new ArrayList<>();

    private ApplicationField add(FieldState state, AutomationState automation,
            boolean required) {
        ApplicationField field = new ApplicationField(1L, "q" + fields.size(), state);
        field.setRequired(required);
        field.setAutomationState(automation);
        fields.add(field);
        return field;
    }

    @Test
    @DisplayName("every field lands in exactly one bucket, so the counts sum to the total")
    void bucketsPartitionTheFields() {
        add(FieldState.RESOLVED, AutomationState.FILLED, true);
        add(FieldState.RESOLVED, AutomationState.FILLED, false);
        add(FieldState.RESOLVED, AutomationState.NOT_ATTEMPTED, true);
        add(FieldState.AWAITING_APPROVAL, AutomationState.FILLED, false);
        add(FieldState.AWAITING_ANSWER, AutomationState.NOT_ATTEMPTED, true);
        add(FieldState.SKIPPED_OPTIONAL, AutomationState.NOT_APPLICABLE, false);
        add(FieldState.DECLINED, AutomationState.NOT_APPLICABLE, false);

        Readiness readiness = Readiness.of(fields);

        int sum = readiness.filled() + readiness.ready() + readiness.automationBlocked()
                + readiness.awaitingApproval() + readiness.awaitingAnswer()
                + readiness.skippedOptional() + readiness.declined();
        assertThat(sum).isEqualTo(readiness.total()).isEqualTo(7);
    }

    @Test
    @DisplayName("known and blocked is counted once, as blocked - not twice")
    void knownAndBlockedIsOneField() {
        ApplicationField blocked = add(FieldState.RESOLVED, AutomationState.NOT_ATTEMPTED, true);
        blocked.setResolvedValue("1 June 2027");
        blocked.blockedBy("the date picker has no text input");

        Readiness readiness = Readiness.of(fields);

        assertThat(readiness.total()).isEqualTo(1);
        assertThat(readiness.automationBlocked()).isEqualTo(1);
        // The answer is still known, and it is not counted as prepared as well.
        assertThat(readiness.prepared()).isZero();
        assertThat(blocked.getResolvedValue()).isEqualTo("1 June 2027");
    }

    @Test
    @DisplayName("a blocked field does not stop the form being opened - opening is the fix")
    void blockedFieldsDoNotStopOpening() {
        ApplicationField blocked = add(FieldState.RESOLVED, AutomationState.NOT_ATTEMPTED, true);
        blocked.blockedBy("unsupported control");

        assertThat(Readiness.of(fields).canOpen(null)).isTrue();
    }

    @Test
    @DisplayName("an unanswered required question stops it, because the gap would go back in")
    void unansweredRequiredQuestionsStopOpening() {
        add(FieldState.AWAITING_ANSWER, AutomationState.NOT_ATTEMPTED, true);

        assertThat(Readiness.of(fields).canOpen(null)).isFalse();
        // Unless the board itself is what needs a person. Then opening is the
        // only move left and the answers ride along with it.
        assertThat(Readiness.of(fields).canOpen(ManualReason.CAPTCHA)).isTrue();
    }

    @Test
    @DisplayName("the blockers are named, not just counted")
    void blockersAreNamed() {
        add(FieldState.AWAITING_ANSWER, AutomationState.NOT_ATTEMPTED, true);
        add(FieldState.AWAITING_APPROVAL, AutomationState.NOT_ATTEMPTED, false);
        ApplicationField blocked = add(FieldState.RESOLVED, AutomationState.NOT_ATTEMPTED, true);
        blocked.blockedBy("stale selector");

        Readiness readiness = Readiness.of(fields, 1);

        assertThat(readiness.blockers()).hasSize(4);
        assertThat(readiness.blockers().getFirst()).contains("1 required question");
        assertThat(readiness.nothingOutstanding()).isFalse();
        assertThat(readiness.summary()).contains("awaiting your answer")
                .contains("needing manual completion");
    }

    @Test
    @DisplayName("singular and plural, because a summary that says '1 questions' is not read")
    void countsAreWordedForOne() {
        add(FieldState.AWAITING_ANSWER, AutomationState.NOT_ATTEMPTED, true);
        assertThat(Readiness.of(fields).blockers().getFirst())
                .isEqualTo("1 required question with no answer");

        add(FieldState.AWAITING_ANSWER, AutomationState.NOT_ATTEMPTED, true);
        assertThat(Readiness.of(fields).blockers().getFirst())
                .isEqualTo("2 required questions with no answer");
    }

    @Test
    @DisplayName("nothing outstanding means nothing waiting on him, blocked controls aside")
    void nothingOutstandingIgnoresTheBrowsersProblems() {
        ApplicationField blocked = add(FieldState.RESOLVED, AutomationState.NOT_ATTEMPTED, true);
        blocked.blockedBy("date picker");
        add(FieldState.RESOLVED, AutomationState.FILLED, true);

        assertThat(Readiness.of(fields).nothingOutstanding()).isTrue();
    }
}
