package com.anuragbhandary.jobradar.followup;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.sheets.SheetsClient.ExistingApplication;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FollowUpServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

    private static ExistingApplication row(int number, String status, LocalDate applied) {
        return new ExistingApplication(number, "Acme", "Backend Engineer", applied,
                status, "https://example.com");
    }

    // -----------------------------------------------------------------------
    // Reading the hand-typed status column.
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "Applied,              APPLIED",
            "applied,              APPLIED",
            "Submitted,            APPLIED",
            "In progress,          APPLIED",
            "OA sent,              IN_PROCESS",
            "Round 2,              IN_PROCESS",
            "Phone screen,         IN_PROCESS",
            "Technical interview,  IN_PROCESS",
            "Rejected,             CLOSED",
            "Ghosted,              CLOSED",
            "Offer,                CLOSED",
            "No response,          CLOSED",
            "'',                   UNKNOWN",
            "Waiting on referral,  UNKNOWN"
    })
    void readsTheStatusColumn(String status, ApplicationStage expected) {
        assertThat(ApplicationStage.of(status)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a row carrying both words is closed - the later word won")
    void closedBeatsApplied() {
        assertThat(ApplicationStage.of("Applied - rejected")).isEqualTo(ApplicationStage.CLOSED);
    }

    @Test
    @DisplayName("'no' does not close 'Phone screen' or 'Notion'")
    void negationMarkersMatchWholeWordsOnly() {
        // "no" is a CLOSED marker and sits inside both. Substring matching here
        // removes live applications from the list, invisibly.
        assertThat(ApplicationStage.of("Phone screen booked"))
                .isEqualTo(ApplicationStage.IN_PROCESS);
        assertThat(ApplicationStage.of("Not yet heard")).isEqualTo(ApplicationStage.UNKNOWN);
    }

    // -----------------------------------------------------------------------
    // Selection.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("only APPLIED rows are chased")
    void onlyAppliedRowsAreChased() {
        List<FollowUp> due = FollowUpService.select(List.of(
                row(7, "Applied", TODAY.minusDays(20)),
                row(8, "Round 2", TODAY.minusDays(60)),
                row(9, "Rejected", TODAY.minusDays(90)),
                row(10, "Waiting on referral", TODAY.minusDays(90))), TODAY, 14);

        assertThat(due).hasSize(1);
        assertThat(due.getFirst().application().rowNumber()).isEqualTo(7);
    }

    @Test
    void tooRecentIsNotChased() {
        assertThat(FollowUpService.select(
                List.of(row(7, "Applied", TODAY.minusDays(3))), TODAY, 14)).isEmpty();
        assertThat(FollowUpService.select(
                List.of(row(7, "Applied", TODAY.minusDays(13))), TODAY, 14)).isEmpty();
        assertThat(FollowUpService.select(
                List.of(row(7, "Applied", TODAY.minusDays(14))), TODAY, 14)).hasSize(1);
    }

    @Test
    @DisplayName("an unreadable date is old, not new")
    void missingDateIsTreatedAsTheOldest() {
        // The rows with no parseable date are the ones typed by hand before this
        // tool existed. Treating a missing date as "today" hides them forever.
        List<FollowUp> due = FollowUpService.select(List.of(
                row(7, "Applied", null),
                row(8, "Applied", TODAY.minusDays(30))), TODAY, 14);

        assertThat(due).hasSize(2);
        assertThat(due.getFirst().application().rowNumber()).isEqualTo(7);
        assertThat(due.getFirst().age()).isEqualTo("date unknown");
        assertThat(due.getFirst().urgency()).isEqualTo(FollowUp.Urgency.ABANDONED);
    }

    @Test
    void oldestFirst() {
        List<FollowUp> due = FollowUpService.select(List.of(
                row(7, "Applied", TODAY.minusDays(20)),
                row(8, "Applied", TODAY.minusDays(70)),
                row(9, "Applied", TODAY.minusDays(40))), TODAY, 14);

        assertThat(due).extracting(f -> f.application().rowNumber())
                .containsExactly(8, 9, 7);
    }

    @ParameterizedTest
    @CsvSource({"14, DUE", "20, DUE", "28, OVERDUE", "40, OVERDUE", "56, ABANDONED"})
    void urgencyBands(int ageDays, FollowUp.Urgency expected) {
        assertThat(FollowUp.of(row(7, "Applied", TODAY.minusDays(ageDays)), TODAY, 14).urgency())
                .isEqualTo(expected);
    }

    @Test
    void unrecognisedRowsAreReportedSeparately() {
        assertThat(FollowUpService.unrecognised(List.of(
                row(7, "Applied", TODAY),
                row(8, "Waiting on referral", TODAY),
                row(9, "", TODAY))))
                .extracting(ExistingApplication::rowNumber)
                .containsExactly(8, 9);
    }
}
