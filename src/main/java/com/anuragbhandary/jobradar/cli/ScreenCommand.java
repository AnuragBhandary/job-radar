package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.filter.ScreenSummary;
import com.anuragbhandary.jobradar.filter.ScreeningService;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code screen} - apply the filters and record verdicts. */
@Component
public class ScreenCommand {

    private final ScreeningService screening;

    public ScreenCommand(ScreeningService screening) {
        this.screening = screening;
    }

    public void run(Map<String, String> options) {
        ScreenSummary summary = screening.screenAll();

        System.out.printf("%n%d screened | %d candidates | %d rejected%n",
                summary.screened(), summary.candidates(), summary.rejected());
        System.out.printf("  of the candidates: %d need human review (no years stated), "
                        + "%d carry a graduate signal%n",
                summary.needsHumanReview(), summary.graduateSignals());

        if (!summary.rejectionsByReason().isEmpty()) {
            System.out.println("\nRejected by reason:");
            summary.rejectionsByReason().forEach((reason, count) ->
                    System.out.printf("  %6d  %s%n", count, reason));
        }
    }
}
