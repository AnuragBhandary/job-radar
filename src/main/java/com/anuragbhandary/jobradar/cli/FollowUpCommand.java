package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.followup.FollowUp;
import com.anuragbhandary.jobradar.followup.FollowUpService;
import com.anuragbhandary.jobradar.sheets.SheetsClient.ExistingApplication;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code follow-up} - applications that have gone quiet.
 *
 * <p>The gap this closes: the tracker has a status column and, until now, nothing
 * ever read it. Every application went in as "Applied" and stayed that way, so the
 * sheet recorded that fifty applications had been sent and nothing about which of
 * them were still alive.
 *
 * <p>{@code --close-abandoned} is the one write. It sets rows silent for six weeks
 * to "No response", which is not an admission of defeat - it is what makes the
 * remaining list short enough to act on.
 */
@Component
public class FollowUpCommand {

    private static final String NO_RESPONSE = "No response";

    private final FollowUpService followUps;

    public FollowUpCommand(FollowUpService followUps) {
        this.followUps = followUps;
    }

    public void run(Map<String, String> options) {
        if (!followUps.isConfigured()) {
            System.out.println("No spreadsheet configured. Set JOB_RADAR_SHEET_ID.");
            return;
        }

        int days = Integer.parseInt(options.getOrDefault(
                "days", String.valueOf(FollowUpService.DEFAULT_DUE_AFTER_DAYS)));

        try {
            List<FollowUp> due = followUps.due(days);
            if (due.isEmpty()) {
                System.out.printf("%nNothing older than %d days is still waiting.%n", days);
            } else {
                System.out.printf("%n%d application(s) silent for %d+ days%n%n",
                        due.size(), days);
                System.out.printf("  %-5s %-6s %-24s %-34s %s%n",
                        "row", "age", "company", "role", "what to do");
                System.out.println("  " + "─".repeat(96));
                for (FollowUp followUp : due) {
                    System.out.printf("  %-5d %-6s %-24s %-34s %s%n",
                            followUp.application().rowNumber(),
                            followUp.age(),
                            truncate(followUp.company(), 24),
                            truncate(followUp.application().role(), 34),
                            followUp.urgency().advice());
                }
            }

            List<ExistingApplication> unknown = followUps.unrecognisedRows();
            if (!unknown.isEmpty()) {
                System.out.printf("%n%d row(s) have a status this does not recognise, so they "
                        + "are in no list at all:%n", unknown.size());
                unknown.forEach(row -> System.out.printf("  row %-4d %-24s status: '%s'%n",
                        row.rowNumber(), truncate(row.company(), 24), row.status()));
            }

            if ("true".equals(options.get("close-abandoned"))) {
                closeAbandoned(due);
            } else {
                long abandoned = due.stream()
                        .filter(f -> f.urgency() == FollowUp.Urgency.ABANDONED).count();
                if (abandoned > 0) {
                    System.out.printf("%n%d have been silent long enough to close. "
                            + "`follow-up --close-abandoned` marks them '%s'.%n",
                            abandoned, NO_RESPONSE);
                }
            }
        } catch (IOException e) {
            System.out.println("Could not read the tracker: " + e.getMessage());
        }
    }

    /**
     * Writes "No response" into the abandoned rows.
     *
     * <p>One row at a time, continuing past a failure, and reporting the count.
     * A batch write that half-succeeds and reports success would leave the sheet
     * in a state nobody can reason about, and the sheet is the only record that
     * cannot be rebuilt.
     */
    private void closeAbandoned(List<FollowUp> due) {
        List<FollowUp> abandoned = due.stream()
                .filter(f -> f.urgency() == FollowUp.Urgency.ABANDONED).toList();
        if (abandoned.isEmpty()) {
            System.out.println("\nNothing old enough to close.");
            return;
        }

        int written = 0;
        for (FollowUp followUp : abandoned) {
            try {
                followUps.setStatus(followUp.application().rowNumber(), NO_RESPONSE);
                written++;
            } catch (IOException | RuntimeException e) {
                System.out.printf("  row %d could not be updated: %s%n",
                        followUp.application().rowNumber(), e.getMessage());
            }
        }
        System.out.printf("%nMarked %d of %d row(s) '%s'.%n",
                written, abandoned.size(), NO_RESPONSE);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
