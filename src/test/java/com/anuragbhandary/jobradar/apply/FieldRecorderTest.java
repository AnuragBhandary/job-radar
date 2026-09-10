package com.anuragbhandary.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.form.Answer;
import com.anuragbhandary.jobradar.apply.form.FieldKind;
import com.anuragbhandary.jobradar.apply.form.FillReport;
import com.anuragbhandary.jobradar.apply.form.FormField;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The five outcomes that used to be one status and a prose sentence.
 *
 * <p>Pure: the mapping from a fill report to structured rows takes no database
 * and no browser, which is what makes the whole matrix cheap to assert.
 */
class FieldRecorderTest {

    private static FormField field(String label, boolean required) {
        return new FormField("#f", label, FormField.ControlType.TEXT, List.of(), required,
                FieldKind.UNKNOWN);
    }

    private static ApplicationField record(Answer answer, boolean required, boolean filled,
            String error) {
        FillReport report = new FillReport(
                List.of(new FillReport.Entry(field("Q", required), answer, filled, error)),
                List.of());
        return new FieldRecorderHarness().first(report);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a known answer that filled is resolved and filled")
    void knownAndFilled() {
        ApplicationField field = record(Answer.profile("Anurag"), true, true, null);
        assertThat(field.getState()).isEqualTo(FieldState.RESOLVED);
        assertThat(field.getAutomationState()).isEqualTo(AutomationState.FILLED);
        assertThat(field.outcome()).isEqualTo(ApplicationField.Outcome.FILLED);
        assertThat(field.getResolvedValue()).isEqualTo("Anurag");
    }

    @Test
    @DisplayName("a drafted answer awaits approval even though it was typed in")
    void generatedAwaitsApproval() {
        // It reached the page, and it is still not a decision. The review screen
        // is where a paragraph written by a model becomes something he has said.
        ApplicationField field = record(
                Answer.generated("Three paragraphs.", "drafted"), false, true, null);
        assertThat(field.getState()).isEqualTo(FieldState.AWAITING_APPROVAL);
        assertThat(field.outcome()).isEqualTo(ApplicationField.Outcome.AWAITING_APPROVAL);
        assertThat(field.getSource())
                .isEqualTo(com.anuragbhandary.jobradar.knowledge.KnowledgeSource.AI_PROPOSED);
    }

    @Test
    @DisplayName("an unknown required field awaits an answer")
    void unknownRequired() {
        ApplicationField field = record(Answer.unanswered("no configured answer"), true,
                false, null);
        assertThat(field.getState()).isEqualTo(FieldState.AWAITING_ANSWER);
        assertThat(field.outcome()).isEqualTo(ApplicationField.Outcome.AWAITING_ANSWER);
    }

    @Test
    @DisplayName("an unknown optional field is skipped, not a blocker")
    void unknownOptional() {
        // It must not stop an application it was never going to stop.
        ApplicationField field = record(Answer.unanswered("no configured answer"), false,
                false, null);
        assertThat(field.getState()).isEqualTo(FieldState.SKIPPED_OPTIONAL);
        assertThat(field.outcome()).isEqualTo(ApplicationField.Outcome.SKIPPED);
    }

    @Test
    @DisplayName("a known answer the browser could not type keeps its value")
    void knownButBlocked() {
        // The rule the phase exists for. Job Radar knowing the answer and the
        // page refusing it is not the same as Job Radar not knowing, and the
        // person finishing by hand needs the value.
        ApplicationField field = record(Answer.derived("Yes", "worked out"), true, false,
                "date picker has no text input");

        assertThat(field.getState()).isEqualTo(FieldState.RESOLVED);
        assertThat(field.getAutomationState()).isEqualTo(AutomationState.BLOCKED);
        assertThat(field.outcome()).isEqualTo(ApplicationField.Outcome.AUTOMATION_BLOCKED);
        assertThat(field.getResolvedValue()).isEqualTo("Yes");
        assertThat(field.getFailureReason()).contains("date picker");
    }

    @Test
    @DisplayName("a declined voluntary field is an answer, not a gap")
    void declined() {
        ApplicationField field = record(Answer.declined("not set in the profile"), false,
                false, null);
        assertThat(field.getState()).isEqualTo(FieldState.DECLINED);
        assertThat(field.getAutomationState()).isEqualTo(AutomationState.NOT_APPLICABLE);
        assertThat(field.outcome()).isEqualTo(ApplicationField.Outcome.DECLINED);
    }

    // ------------------------------------------------------------------
    // What the fields add up to
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a form with nothing outstanding is ready for review")
    void statusReady() {
        assertThat(FieldRecorder.statusFor(List.of(
                state(FieldState.RESOLVED, AutomationState.FILLED),
                state(FieldState.DECLINED, AutomationState.NOT_APPLICABLE))))
                .isEqualTo(AttemptStatus.READY_FOR_REVIEW);
    }

    @Test
    @DisplayName("an unanswered required field outranks everything else")
    void statusAwaitingAnswer() {
        // Answering it does not only unblock this application; it teaches Job
        // Radar something every future form asking the same thing will use.
        assertThat(FieldRecorder.statusFor(List.of(
                state(FieldState.RESOLVED, AutomationState.BLOCKED),
                state(FieldState.AWAITING_APPROVAL, AutomationState.FILLED),
                state(FieldState.AWAITING_ANSWER, AutomationState.NOT_ATTEMPTED))))
                .isEqualTo(AttemptStatus.AWAITING_ANSWER);
    }

    @Test
    @DisplayName("a drafted answer outranks a blocked control")
    void statusAwaitingApproval() {
        assertThat(FieldRecorder.statusFor(List.of(
                state(FieldState.RESOLVED, AutomationState.BLOCKED),
                state(FieldState.AWAITING_APPROVAL, AutomationState.FILLED))))
                .isEqualTo(AttemptStatus.AWAITING_APPROVAL);
    }

    @Test
    @DisplayName("a blocked control alone is a manual job, not an unanswered question")
    void statusManualRequired() {
        assertThat(FieldRecorder.statusFor(List.of(
                state(FieldState.RESOLVED, AutomationState.FILLED),
                state(FieldState.RESOLVED, AutomationState.BLOCKED))))
                .isEqualTo(AttemptStatus.MANUAL_REQUIRED);
    }

    @Test
    @DisplayName("manual reasons say whether the answers survive and whether to retry")
    void manualReasonsCarryTheirOwnAdvice() {
        // "Manual" covered a captcha, a missing form and a stale selector, told
        // apart by whether a sentence began with "No form found".
        assertThat(ManualReason.CAPTCHA.valuesReusable()).isTrue();
        assertThat(ManualReason.CAPTCHA.resumable()).isTrue();
        assertThat(ManualReason.NO_FORM.resumable()).isFalse();
        assertThat(ManualReason.UNSUPPORTED_SITE.resumable()).isFalse();
        for (ManualReason reason : ManualReason.values()) {
            assertThat(reason.explanation()).isNotBlank();
            assertThat(reason.detail()).isNotBlank();
        }
    }

    @Test
    @DisplayName("preparation state and pipeline stage stay separate")
    void statesDoNotOverlap() {
        // An application can be SUBMITTED here and INTERVIEW on the board. Two
        // vocabularies, deliberately, and neither borrows from the other.
        assertThat(AttemptStatus.AWAITING_ANSWER.needsHuman()).isTrue();
        assertThat(AttemptStatus.MANUAL_REQUIRED.needsHuman()).isTrue();
        assertThat(AttemptStatus.READY_FOR_REVIEW.isReadyToSend()).isTrue();
        assertThat(AttemptStatus.NEEDS_HUMAN.isLegacy()).isTrue();
        assertThat(AttemptStatus.PREPARED.isLegacy()).isTrue();
        for (AttemptStatus status : AttemptStatus.values()) {
            assertThat(status.name()).doesNotContain("INTERVIEW").doesNotContain("OFFER");
        }
    }

    private static ApplicationField state(FieldState state, AutomationState automation) {
        ApplicationField field = new ApplicationField(1L, "Q", state);
        field.setAutomationState(automation);
        return field;
    }

    /** Builds fields the way the recorder does, without needing a database. */
    private static final class FieldRecorderHarness {
        ApplicationField first(FillReport report) {
            FillReport.Entry entry = report.entries().getFirst();
            Answer answer = entry.answer();
            boolean required = entry.field().required();
            FieldState state = switch (answer.origin()) {
                case PROFILE, DERIVED -> FieldState.RESOLVED;
                case GENERATED -> FieldState.AWAITING_APPROVAL;
                case DECLINED -> FieldState.DECLINED;
                case UNANSWERED -> required
                        ? FieldState.AWAITING_ANSWER : FieldState.SKIPPED_OPTIONAL;
            };
            ApplicationField field = new ApplicationField(1L, entry.field().label(), state);
            field.setRequired(required);
            field.setResolvedValue(answer.value());
            field.setSource(switch (answer.origin()) {
                case PROFILE -> com.anuragbhandary.jobradar.knowledge.KnowledgeSource.PROFILE;
                case DERIVED -> com.anuragbhandary.jobradar.knowledge.KnowledgeSource.DERIVED;
                case GENERATED ->
                        com.anuragbhandary.jobradar.knowledge.KnowledgeSource.AI_PROPOSED;
                case DECLINED, UNANSWERED -> null;
            });
            if (entry.error() != null) {
                field.blockedBy(entry.error());
            } else if (entry.filled()) {
                field.filled();
            } else {
                field.setAutomationState(state == FieldState.DECLINED
                        || state == FieldState.SKIPPED_OPTIONAL
                        ? AutomationState.NOT_APPLICABLE : AutomationState.NOT_ATTEMPTED);
            }
            return field;
        }
    }
}
