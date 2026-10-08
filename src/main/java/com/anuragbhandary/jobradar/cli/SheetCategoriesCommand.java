package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.domain.RoleCategory;
import com.anuragbhandary.jobradar.sheets.SheetsClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code sheet-categories [--dry-run]} - fills the tracker's Category column and
 * keeps a count of applications by category at the top of the sheet.
 *
 * <p>Asked for on 2026-10-08. Column J holds each row's category, read from the
 * role in column B; a cell already filled is left alone, so a category corrected
 * by hand stays corrected. The count sits in L1:M9, to the right of the HOW TO
 * USE block rather than above the header: {@code mark ... applied} appends below
 * the last used row of A:J, and anything placed inside those columns above the
 * header would be read as part of the table. The counts are COUNTIF formulas, so
 * a row added by hand is counted as soon as its category is filled in.
 */
@Component
public class SheetCategoriesCommand {

    /** Where the count table starts; it runs down one row per category plus a total. */
    static final String SUMMARY_COLUMN = "L";
    static final String COUNT_COLUMN = "M";

    private final SheetsClient sheets;

    public SheetCategoriesCommand(SheetsClient sheets) {
        this.sheets = sheets;
    }

    public void run(Map<String, String> options) {
        if (!sheets.isConfigured()) {
            System.out.println("Sheets is not configured; see ~/.config/job-radar/secrets.yml.");
            return;
        }
        boolean dryRun = options.containsKey("dry-run");
        try {
            List<List<Object>> table = sheets.read("A:J");
            int header = SheetsClient.headerRow(table);

            // Column J, header first.
            List<List<Object>> column = new ArrayList<>();
            int filled = 0;
            int lastRow = header;
            for (int row = header; row <= table.size(); row++) {
                List<Object> cells = table.get(row - 1);
                String existing = cell(cells, 9);
                if (row == header) {
                    column.add(List.of(existing.isBlank() ? "Category" : existing));
                    continue;
                }
                String company = cell(cells, 0);
                if (company.isBlank()) {
                    column.add(List.of(existing));
                    continue;
                }
                lastRow = row;
                if (existing.isBlank()) {
                    existing = RoleCategory.of(cell(cells, 1)).label();
                    filled++;
                    if (dryRun) {
                        System.out.println("  row " + row + ": " + existing + "  <=  " + cell(cells, 1));
                    }
                }
                column.add(List.of(existing));
            }
            column = column.subList(0, lastRow - header + 1);
            String columnRange = "J" + header + ":J" + lastRow;

            // The count table, only where nothing is written yet.
            List<RoleCategory> order = RoleCategory.trackerOrder();
            int summaryRows = order.size() + 2;
            String summaryRange = SUMMARY_COLUMN + "1:" + COUNT_COLUMN + summaryRows;
            List<List<Object>> current = sheets.read(summaryRange);
            boolean ours = !current.isEmpty() && "Category".equals(cell(current.getFirst(), 0))
                    && "Applications".equals(cell(current.getFirst(), 1));
            boolean empty = current.stream().allMatch(r -> r.stream().allMatch(c -> c.toString().isBlank()));
            List<List<Object>> summary = summary(order, header, lastRow);

            System.out.println("Header on row " + header + "; " + (lastRow - header)
                    + " application rows; " + filled + " categories to fill in " + columnRange + ".");
            for (List<Object> r : summary) {
                System.out.println("  " + r.get(0) + " | " + r.get(1));
            }
            if (dryRun) {
                System.out.println("Dry run: nothing written.");
                return;
            }
            sheets.write(columnRange, column, false);
            if (empty || ours) {
                sheets.write(summaryRange, summary, true);
                System.out.println("Wrote the categories and the count table at " + summaryRange + ".");
            } else {
                System.out.println("Wrote the categories. " + summaryRange
                        + " already holds something else, so the count table was not written.");
            }
        } catch (IOException e) {
            System.out.println("Could not update the tracker: " + e.getMessage());
        }
    }

    /** Header, one COUNTIF per category over the data rows, and a total. */
    static List<List<Object>> summary(List<RoleCategory> order, int header, int lastRow) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Category", "Applications"));
        // Open-ended below the header, so rows appended later are counted too.
        String range = "$J$" + (header + 1) + ":$J";
        for (int i = 0; i < order.size(); i++) {
            int sheetRow = i + 2;
            rows.add(List.of(order.get(i).label(),
                    "=COUNTIF(" + range + "," + SUMMARY_COLUMN + sheetRow + ")"));
        }
        rows.add(List.of("Total", "=SUM(" + COUNT_COLUMN + "2:" + COUNT_COLUMN + (order.size() + 1) + ")"));
        return rows;
    }

    private static String cell(List<Object> row, int index) {
        return row == null || row.size() <= index || row.get(index) == null
                ? "" : row.get(index).toString().trim();
    }
}
