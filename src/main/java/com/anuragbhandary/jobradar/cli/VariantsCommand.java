package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.VariantReport;
import com.anuragbhandary.jobradar.sheets.SheetsClient;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code variants} - which resume opening has actually produced replies.
 *
 * <p>Prints its own sample size first and refuses to draw a conclusion below it.
 * The temptation this resists is real: 2 replies from 5 against 1 from 6 looks
 * like a 140% improvement and is three coin flips, and a resume rewritten on that
 * is worse than one left alone.
 */
@Component
public class VariantsCommand {

    private final ApplicationAttemptRepository attempts;
    private final SheetsClient sheets;

    public VariantsCommand(ApplicationAttemptRepository attempts, SheetsClient sheets) {
        this.attempts = attempts;
        this.sheets = sheets;
    }

    public void run(Map<String, String> options) {
        List<SheetsClient.ExistingApplication> rows;
        try {
            rows = sheets.isConfigured() ? sheets.readExistingApplications() : List.of();
        } catch (IOException e) {
            System.out.println("Could not read the tracker: " + e.getMessage());
            return;
        }

        List<VariantReport.Variant> variants =
                VariantReport.compile(attempts.findAll(), rows);

        if (variants.isEmpty()) {
            System.out.println("""

                    Nothing submitted yet, so there is nothing to compare.

                    Each application records which of the resume summaries it opened
                    with. Once there are enough, this reports how each has done.""");
            return;
        }

        System.out.printf("%n%-14s %6s %8s %8s %11s %11s%n",
                "variant", "sent", "replied", "in proc.", "reply rate", "progress");
        System.out.println("─".repeat(64));
        for (VariantReport.Variant variant : variants) {
            System.out.printf("%-14s %6d %8d %8d %10.0f%% %10.0f%%%n",
                    variant.summaryId(), variant.sent(), variant.replied(),
                    variant.progressed(), variant.replyRate() * 100,
                    variant.progressRate() * 100);
        }

        if (VariantReport.isMeaningful(variants)) {
            System.out.printf("%nEvery variant has %d+ applications behind it. "
                    + "The differences are worth acting on.%n",
                    VariantReport.MEANINGFUL_SAMPLE);
        } else {
            int smallest = variants.stream()
                    .mapToInt(VariantReport.Variant::sent).min().orElse(0);
            System.out.printf("""

                    %d application(s) on the smallest variant. Below %d this shows
                    nothing: a 2-from-5 against 1-from-6 reads as a large
                    improvement and is three coin flips. Treat the table as a
                    record of what was sent, not as a result.
                    """, smallest, VariantReport.MEANINGFUL_SAMPLE);
        }

        if (!sheets.isConfigured()) {
            System.out.println("No spreadsheet configured, so no outcomes are known - "
                    + "only the 'sent' column means anything.");
        } else {
            System.out.println("Outcomes are joined on company name, so two applications "
                    + "to one company cannot be told apart.");
        }
    }
}
