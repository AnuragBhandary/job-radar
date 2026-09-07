package com.anuragbhandary.jobradar.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PipelineStageTest {

    @ParameterizedTest
    @CsvSource({
            "Applied,               APPLIED",
            "applied,               APPLIED",
            "OA sent,               SCREENING",
            "Online assessment,     SCREENING",
            "Phone screen,          SCREENING",
            "Round 2,               INTERVIEW",
            "Final interview,       INTERVIEW",
            "Onsite,                INTERVIEW",
            "Rejected,              REJECTED",
            "Ghosted,               REJECTED",
            "No response,           REJECTED",
            "Offer,                 OFFER",
            "Accepted,              OFFER",
            "Withdrew,              DROPPED"
    })
    void readsTheHandTypedTrackerColumn(String status, PipelineStage expected) {
        assertThat(PipelineStage.fromTrackerStatus(status)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a blank or unknown status is APPLIED, because the row exists at all")
    void unknownMeansApplied() {
        // A row in the tracker is by definition an application that was sent. The
        // status column says what happened next, and not knowing that is not a
        // reason to drop it off the board.
        assertThat(PipelineStage.fromTrackerStatus("")).isEqualTo(PipelineStage.APPLIED);
        assertThat(PipelineStage.fromTrackerStatus(null)).isEqualTo(PipelineStage.APPLIED);
        assertThat(PipelineStage.fromTrackerStatus("waiting on referral"))
                .isEqualTo(PipelineStage.APPLIED);
    }

    @Test
    @DisplayName("an ending wins over the beginning it also mentions")
    void terminalWordsWinTheRow() {
        assertThat(PipelineStage.fromTrackerStatus("Applied - rejected"))
                .isEqualTo(PipelineStage.REJECTED);
        assertThat(PipelineStage.fromTrackerStatus("Interviewed, then rejected"))
                .isEqualTo(PipelineStage.REJECTED);
    }

    @Test
    void theBoardShowsLiveStagesAndClosesTheRest() {
        assertThat(PipelineStage.live())
                .containsExactly(PipelineStage.SAVED, PipelineStage.PREPARED,
                        PipelineStage.APPLIED, PipelineStage.SCREENING, PipelineStage.INTERVIEW)
                .allSatisfy(stage -> assertThat(stage.isTerminal()).isFalse());

        assertThat(PipelineStage.closed())
                .allSatisfy(stage -> assertThat(stage.isTerminal()).isTrue());
    }
}
