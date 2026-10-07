package com.anuragbhandary.jobradar.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.cli.CalibrateCommand.Decision;
import com.anuragbhandary.jobradar.cli.CalibrateCommand.Outcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalibrateCommandTest {

    @Test
    void bands() {
        assertThat(CalibrateCommand.band(43)).isEqualTo("40-49");
        assertThat(CalibrateCommand.band(7)).isEqualTo("00-09");
        assertThat(CalibrateCommand.band(100)).isEqualTo("90-100");
    }

    @Test
    void reportsPickRateOverWhatWasRead() {
        String report = CalibrateCommand.report(List.of(
                new Decision(72, "WORKDAY", "INDIA_OTHER", Outcome.PICKED),
                new Decision(75, "WORKDAY", "INDIA_OTHER", Outcome.JUDGED),
                new Decision(44, "ORACLE_HCM", "INDIA_HOME", Outcome.PICKED),
                new Decision(46, "ORACLE_HCM", "INDIA_HOME", Outcome.BULK),
                new Decision(41, "ORACLE_HCM", "INDIA_HOME", Outcome.BULK)), 40);

        assertThat(report).contains("5 decisions: 2 picked, 1 dropped after reading, 2 dropped unread");
        // One of two read in the 70s was picked; unread skips do not count.
        assertThat(report).containsPattern("70-79\\s+1\\s+1\\s+0\\s+50%");
        assertThat(report).containsPattern("40-49\\s+1\\s+0\\s+2\\s+100%");
        assertThat(report).contains("Lowest-scoring pick: 44. 1 unread skips scored at least that.");
    }
}
