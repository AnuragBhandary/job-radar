package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.filter.ScreenSummary;
import com.anuragbhandary.jobradar.filter.ScreeningService;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code screen} - classify every posting, then apply the filters.
 *
 * <p>Also the migration for the country/work-mode columns: they are derived from
 * each posting's own location text, so a run of this fills them in for everything
 * fetched before they existed.
 *
 * <p>The distributions it prints are the corpus regression. Screening rewrites
 * nine thousand verdicts every time it runs, so the way to review a rules change
 * is to keep the output of the run before it and diff the two.
 */
@Component
public class ScreenCommand {

    private final ScreeningService screening;

    public ScreenCommand(ScreeningService screening) {
        this.screening = screening;
    }

    public void run(Map<String, String> options) {
        ScreenSummary summary = screening.screenAll();

        System.out.printf("%n%d screened | %d eligible | %d recommended | %d rejected%n",
                summary.screened(), summary.candidates(),
                summary.recommended(), summary.rejected());
        System.out.printf("  of the eligible: %d need human review (no years stated), "
                        + "%d carry a graduate signal%n",
                summary.needsHumanReview(), summary.graduateSignals());

        distribution("Eligible by strategic lane", summary.byStrategicClass());
        distribution("Eligible by strategy outcome", summary.byOutcome());
        distribution("Eligible by work mode", summary.byWorkMode());
        distribution("Eligible by country", summary.byCountry());
        distribution("Rejected by reason", summary.rejectionsByReason());
    }

    private static void distribution(String heading, Map<String, Long> counts) {
        if (counts == null || counts.isEmpty()) {
            return;
        }
        System.out.printf("%n%s:%n", heading);
        counts.forEach((key, count) -> System.out.printf("  %6d  %s%n", count, key));
    }
}
