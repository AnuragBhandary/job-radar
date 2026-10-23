package com.anuragbhandary.jobradar.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrowserWatchTest {

    /** A Thursday that is not the 1st: only daily pages are due. */
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 22);
    /** A Monday that is not the 1st. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);
    /** The 1st, a Sunday. */
    private static final LocalDate FIRST = LocalDate.of(2026, 11, 1);

    private final BrowserWatch watch = new BrowserWatch(List.of(
            new BrowserWatch.Page("SAP", "Bengaluru", "https://sap.example/jobs", null, "daily"),
            new BrowserWatch.Page("Upstox", "Mumbai", "https://upstox.example/jobs", null, "weekly"),
            new BrowserWatch.Page("Nykaa", null, "https://nykaa.example/jobs", "none posted", "monthly"),
            new BrowserWatch.Page("Old", null, "https://old.example/jobs", null, null)));

    @Test
    void rendersOneLinePerPage() {
        var plain = new BrowserWatch(List.of(
                new BrowserWatch.Page("Upstox", "Mumbai", "https://upstox.example/jobs", null, null),
                new BrowserWatch.Page("smallcase", null, "https://smallcase.example/jobs", "0 open", null)));
        assertThat(plain.render(THURSDAY))
                .contains("## Check in the browser (2 of 2)")
                .contains("- Upstox (Mumbai): https://upstox.example/jobs\n")
                .contains("- smallcase: https://smallcase.example/jobs · 0 open\n")
                .doesNotContain("Not due today");
    }

    @Test
    void onAnOrdinaryDayOnlyDailyPagesAreListed() {
        assertThat(watch.render(THURSDAY))
                .contains("## Check in the browser (2 of 4)")
                .contains("- SAP (Bengaluru)")
                .contains("- Old: ")
                .doesNotContain("Upstox")
                .doesNotContain("Nykaa")
                .contains("Not due today: 1 weekly (Mondays), 1 monthly (the 1st).");
    }

    @Test
    void mondaysAddTheWeeklyPages() {
        assertThat(watch.render(MONDAY))
                .contains("(3 of 4)")
                .contains("### Weekly (Mondays) (1)")
                .contains("- Upstox (Mumbai)")
                .doesNotContain("Nykaa")
                .contains("Not due today: 1 monthly (the 1st).");
    }

    @Test
    void theFirstAddsTheMonthlyPages() {
        assertThat(watch.render(FIRST))
                .contains("### Monthly (the 1st) (1)")
                .contains("- Nykaa: https://nykaa.example/jobs · none posted")
                .doesNotContain("Upstox");
    }

    @Test
    void nothingWatchedAddsNothing() {
        assertThat(new BrowserWatch(null).render(THURSDAY)).isEmpty();
    }
}
