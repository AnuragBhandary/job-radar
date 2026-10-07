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
                new Decision(41, "ORACLE_HCM", "INDIA_HOME", Outcome.BULK)), 40, false);

        assertThat(report).contains("5 decisions: 2 picked, 1 dropped after reading, 2 dropped unread");
        // One of two read in the 70s was picked; unread skips do not count.
        assertThat(report).containsPattern("70-79\\s+1\\s+1\\s+0\\s+50%");
        assertThat(report).containsPattern("40-49\\s+1\\s+0\\s+2\\s+100%");
        assertThat(report).contains("Lowest-scoring pick: 44. 1 unread skips scored at least that.");
    }

    @Test
    void suggestsWordsOnlyFromReadSkipsAndNeverFromPicks() {
        List<Decision> decisions = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            decisions.add(new Decision(50, 50, new double[0], 0, "WORKDAY", "INDIA_OTHER",
                    Outcome.JUDGED, null, "Treasury Operations Analyst " + i,
                    "reviewed 2026-10-07: operations role, not data work"));
        }
        decisions.add(new Decision(50, 50, new double[0], 0, "WORKDAY", "INDIA_OTHER",
                Outcome.PICKED, null, "Operations Data Engineer", "Apply now."));

        String out = CalibrateCommand.suggestions(decisions, java.util.Set.of("treasury"));

        // "treasury" is already excluded, "operations" is in a pick.
        assertThat(out).doesNotContain("treasury").doesNotContain("operations   ");
        assertThat(out).contains("6  operations role");
    }

    @Test
    void reportsWhatHappenedAfterApplying() {
        assertThat(CalibrateCommand.outcomes(List.of(
                new Decision(60, "AMAZON", "INDIA_OTHER", Outcome.PICKED)))).isEmpty();

        String out = CalibrateCommand.outcomes(List.of(
                new Decision(60, 60, new double[0], 0, "AMAZON", "INDIA_OTHER", Outcome.PICKED,
                        com.anuragbhandary.jobradar.pipeline.PipelineStage.INTERVIEW, "SDE I",
                        "Apply now. Software resume: Amazon SDE I"),
                new Decision(55, 55, new double[0], 0, "AMAZON", "INDIA_OTHER", Outcome.PICKED,
                        com.anuragbhandary.jobradar.pipeline.PipelineStage.APPLIED, "Data Engineer I",
                        "Apply now. Data resume: Amazon DE")));
        assertThat(out).contains("After applying (2 applications)");
        assertThat(out).containsPattern("AMAZON\\s+2\\s+1\\s+0\\s+1");
        assertThat(out).containsPattern("software resume\\s+1\\s+1");
    }
}
