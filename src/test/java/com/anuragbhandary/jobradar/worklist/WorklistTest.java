package com.anuragbhandary.jobradar.worklist;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The two judgements the home page makes, both of which were wrong first time. */
class WorklistTest {

    @Test
    @DisplayName("a board retired on purpose is not a board that is failing")
    void retiredIsNotBroken() {
        // All eight flagged boards were of this kind, so counting the field as
        // failures put "8 boards are failing, jobs are being missed" on the home
        // page while nothing at all was wrong.
        BoardToken retired = new BoardToken(Source.SMARTRECRUITERS, "N26", "N26");
        retired.setLastError("retired: already covered on Greenhouse as 'n26'");

        assertThat(retired.isRetired()).isTrue();
        assertThat(retired.isBroken()).isFalse();
        assertThat(retired.retirementReason()).isEqualTo("already covered on Greenhouse as 'n26'");
    }

    @Test
    @DisplayName("a board that actually stopped answering is broken")
    void brokenIsBroken() {
        BoardToken broken = new BoardToken(Source.GREENHOUSE, "someco", "Some Co");
        broken.setLastError("404 from the board API");

        assertThat(broken.isBroken()).isTrue();
        assertThat(broken.isRetired()).isFalse();
    }

    @Test
    @DisplayName("a board with no error is neither")
    void healthy() {
        BoardToken fine = new BoardToken(Source.ASHBY, "camunda", "Camunda");

        assertThat(fine.isBroken()).isFalse();
        assertThat(fine.isRetired()).isFalse();
    }

    @Test
    @DisplayName("the diagnosis names the narrowest point, not the flattering one")
    void diagnosisIsHonest() {
        Worklist.Funnel starved = new Worklist.Funnel(9032, 56, 2, 17, 2, 0);
        assertThat(starved.diagnosis()).contains("top of the funnel");

        Worklist.Funnel notLanding = new Worklist.Funnel(9032, 56, 20, 17, 3, 0);
        assertThat(notLanding.diagnosis()).contains("points at the application");

        Worklist.Funnel early = new Worklist.Funnel(9032, 56, 20, 4, 0, 0);
        assertThat(early.diagnosis()).contains("Too early");
    }

    @Test
    @DisplayName("a reply rate needs replies to divide")
    void replyRate() {
        assertThat(new Worklist.Funnel(9032, 56, 9, 0, 0, 0).replyRate()).isEmpty();
        assertThat(new Worklist.Funnel(9032, 56, 9, 20, 5, 1).replyRate()).hasValue(0.25);
    }
}
