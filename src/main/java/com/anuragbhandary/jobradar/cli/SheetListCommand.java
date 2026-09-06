package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.sheets.SheetsClient;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code sheet-list} - print the tracker as this program sees it.
 *
 * <p>Read-only, and not in the original command set. It exists because
 * {@code readExistingApplications} degrades quietly by design - a tracker it
 * cannot read costs the digest its suppression rather than its existence - and
 * quiet degradation is exactly the thing that needs a way to be checked
 * deliberately.
 */
@Component
public class SheetListCommand {

    private final SheetsClient sheets;

    public SheetListCommand(SheetsClient sheets) {
        this.sheets = sheets;
    }

    public void run(Map<String, String> options) {
        if (!sheets.isConfigured()) {
            System.out.println("No spreadsheet configured. Set JOB_RADAR_SHEET_ID.");
            return;
        }
        try {
            List<SheetsClient.ExistingApplication> applications =
                    sheets.readExistingApplications();
            System.out.printf("%n%d applications in the tracker%n%n", applications.size());
            System.out.printf("  %-5s %-26s %-42s %s%n", "row", "company", "role", "status");
            applications.forEach(a -> System.out.printf("  %-5d %-26s %-42s %s%n",
                    a.rowNumber(), truncate(a.company(), 26),
                    truncate(a.role(), 42), a.status()));
        } catch (IOException | RuntimeException e) {
            System.out.println("Could not read the tracker: " + e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
