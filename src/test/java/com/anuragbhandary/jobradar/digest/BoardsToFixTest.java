package com.anuragbhandary.jobradar.digest;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Source;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardsToFixTest {

    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

    @Test
    void biggestBrokenBoardFirstWithDaysFailingAndNewOnesMarked() {
        BoardToken big = new BoardToken(Source.WORKDAY, "accenture/wd103/AccentureCareers/@India", "Accenture");
        big.recordSuccess(2000, NOW.minus(Duration.ofDays(4)));
        big.recordFailure("Gave up after 3 attempts", NOW.minus(Duration.ofDays(3)));
        big.recordFailure("Gave up after 3 attempts", NOW);
        BoardToken small = new BoardToken(Source.GREENHOUSE, "tinyco", "Tiny");
        small.recordSuccess(3, NOW.minus(Duration.ofDays(2)));
        small.recordFailure("HTTP 404", NOW.minus(Duration.ofHours(5)));
        BoardToken fine = new BoardToken(Source.LEVER, "fine", "Fine");
        fine.recordSuccess(40, NOW);

        StringBuilder out = new StringBuilder();
        DigestWriter.boardsToFix(out, List.of(small, fine, big), NOW);

        assertThat(out.toString())
                .startsWith("## Boards to fix (2)\n")
                .contains("- WORKDAY/accenture/wd103/AccentureCareers/@India — failing 3 day(s), was 2000 postings")
                .contains("- **new** GREENHOUSE/tinyco, was 3 postings: HTTP 404")
                .doesNotContain("fine");
        assertThat(out.indexOf("accenture")).isLessThan(out.indexOf("tinyco"));
    }

    @Test
    void aBoardThatJustWentEmptyIsNamedAndAHealthyListAddsNothing() {
        BoardToken emptied = new BoardToken(Source.ASHBY, "movedco", "Moved");
        emptied.recordSuccess(0, NOW.minus(Duration.ofHours(20)));
        BoardToken longEmpty = new BoardToken(Source.ASHBY, "quietco", "Quiet");
        longEmpty.recordSuccess(0, NOW.minus(Duration.ofDays(30)));

        StringBuilder out = new StringBuilder();
        DigestWriter.boardsToFix(out, List.of(emptied, longEmpty), NOW);
        assertThat(out.toString()).contains("ASHBY/movedco — answered with nothing since").doesNotContain("quietco");

        StringBuilder none = new StringBuilder();
        DigestWriter.boardsToFix(none, List.of(longEmpty), NOW);
        assertThat(none.toString()).isEmpty();
    }

    @Test
    void successClearsTheFailingClock() {
        BoardToken b = new BoardToken(Source.LEVER, "x", "X");
        b.recordFailure("HTTP 500", NOW.minus(Duration.ofDays(2)));
        b.recordFailure("HTTP 500", NOW);
        assertThat(b.daysFailing(NOW)).isEqualTo(2);
        b.recordSuccess(10, NOW);
        assertThat(b.getFailingSince()).isNull();
    }
}
