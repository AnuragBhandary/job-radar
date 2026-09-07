package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.mail.InboxScanner;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code inbox} - read replies and update the tracker's status column.
 *
 * <p>Prints what it would change and changes nothing. {@code --apply} writes.
 * The split matters more here than anywhere else in the tool: the classifier is
 * reading prose written by strangers, and a wrong "Rejected" makes a live
 * application look dead and stops it being followed up.
 */
@Component
public class InboxCommand {

    private final InboxScanner scanner;

    public InboxCommand(InboxScanner scanner) {
        this.scanner = scanner;
    }

    public void run(Map<String, String> options) {
        if (!scanner.isConfigured()) {
            System.out.println("""
                    Gmail or Sheets is not configured.

                      JOB_RADAR_SHEET_ID       the tracker spreadsheet
                      JOB_RADAR_GMAIL_KEY      an OAuth client secret (Desktop app),
                                               downloaded from the Cloud Console

                    The Sheets service account cannot be used here - a service
                    account has no mailbox. The first run opens a browser once.
                    """);
            return;
        }

        int days = Integer.parseInt(options.getOrDefault("days", "60"));
        boolean write = "true".equals(options.get("apply"));

        try {
            List<InboxScanner.Proposal> proposals = scanner.scan(days);
            if (proposals.isEmpty()) {
                System.out.printf("%nNothing in the last %d days changes a tracker row.%n", days);
                return;
            }

            System.out.printf("%n%d row(s) would change:%n%n", proposals.size());
            for (InboxScanner.Proposal proposal : proposals) {
                System.out.printf("  row %-4d %-24s %-14s → %s%n",
                        proposal.row().rowNumber(),
                        truncate(proposal.row().company(), 24),
                        "'" + proposal.row().status() + "'",
                        proposal.newStatus());
                System.out.printf("        %s%n", truncate(proposal.evidence().subject(), 88));
                System.out.printf("        from %s, %s%n",
                        truncate(proposal.evidence().senderName(), 40),
                        proposal.evidence().received());
            }

            if (!write) {
                System.out.println("\n`inbox --apply` writes these. Read them first - a wrong "
                        + "'Rejected' makes a live application look dead.");
                return;
            }

            int written = 0;
            for (InboxScanner.Proposal proposal : proposals) {
                try {
                    scanner.apply(proposal);
                    written++;
                } catch (IOException | RuntimeException e) {
                    System.out.printf("  row %d could not be updated: %s%n",
                            proposal.row().rowNumber(), e.getMessage());
                }
            }
            System.out.printf("%nUpdated %d of %d row(s).%n", written, proposals.size());

        } catch (IOException e) {
            System.out.println("Could not scan: " + e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
