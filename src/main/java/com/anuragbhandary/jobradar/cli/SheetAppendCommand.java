package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.anuragbhandary.jobradar.sheets.ApplicationRow;
import com.anuragbhandary.jobradar.sheets.SheetsClient;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code sheet-append --posting-id=123} - record an application in the tracker.
 *
 * <p>Prints the row and asks for confirmation before writing. The tracker is the
 * only record of where the user has applied, and this is the one command in the
 * tool that changes something outside the local database - a wrong row there is
 * not undone by re-running anything.
 *
 * <p>{@code --yes} skips the prompt, for use from a script.
 */
@Component
public class SheetAppendCommand {

    private final PostingRepository postings;
    private final BoardTokenRepository boards;
    private final SheetsClient sheets;

    public SheetAppendCommand(
            PostingRepository postings, BoardTokenRepository boards, SheetsClient sheets) {
        this.postings = postings;
        this.boards = boards;
        this.sheets = sheets;
    }

    public void run(Map<String, String> options) {
        if (!sheets.isConfigured()) {
            System.out.println("No spreadsheet configured. Set JOB_RADAR_SHEET_ID.");
            return;
        }

        String id = options.get("posting-id");
        if (id == null) {
            System.out.println("Usage: sheet-append --posting-id=123 [--notes=...] [--yes]");
            return;
        }

        Optional<Posting> found = postings.findById(Long.valueOf(id));
        if (found.isEmpty()) {
            System.out.println("No posting with id " + id);
            return;
        }
        Posting posting = found.get();

        String company = com.anuragbhandary.jobradar.domain.Employer.company(
                boards.findBySourceAndToken(posting.getSource(), posting.getBoardToken())
                .map(b -> b.getLabel() == null ? b.getToken() : b.getLabel())
                .orElse(posting.getBoardToken()),
                posting.getSource(), posting.getTitle());

        ApplicationRow row = ApplicationRow.from(posting, company, LocalDate.now())
                .withNotes(options.getOrDefault("notes", ""));

        System.out.println("\nAbout to append this row to the tracker:\n");
        List<Object> cells = row.toCells();
        for (int i = 0; i < ApplicationRow.COLUMNS.size(); i++) {
            System.out.printf("  %-26s %s%n", ApplicationRow.COLUMNS.get(i) + ":", cells.get(i));
        }

        if (!"true".equals(options.get("yes")) && !confirmed()) {
            System.out.println("\nNot written.");
            return;
        }

        try {
            int written = sheets.appendApplication(row);
            System.out.println("\nAppended at row " + written + ".");
        } catch (IOException e) {
            System.out.println("\nCould not write to the tracker: " + e.getMessage());
        }
    }

    private static boolean confirmed() {
        System.out.print("\nAppend it? [y/N] ");
        try (java.util.Scanner scanner = new java.util.Scanner(System.in)) {
            return scanner.hasNextLine() && scanner.nextLine().trim().equalsIgnoreCase("y");
        } catch (RuntimeException e) {
            // No console, e.g. running from a scheduler. Refusing is the safe default.
            return false;
        }
    }

    /**
     * {@code sheet-set --row=N [--company=..] [--role=..] [--link=..]} - correct
     * cells in one tracker row. Nothing else in the row is touched.
     */
    public void set(Map<String, String> options) {
        String row = options.get("row");
        if (row == null) {
            System.out.println("Usage: sheet-set --row=N [--company=...] [--role=...] [--link=...]");
            return;
        }
        Map<String, String> cells = new java.util.LinkedHashMap<>();
        if (options.containsKey("company")) {
            cells.put("A", options.get("company"));
        }
        if (options.containsKey("role")) {
            cells.put("B", options.get("role"));
        }
        if (options.containsKey("link")) {
            cells.put("H", options.get("link"));
        }
        if (cells.isEmpty()) {
            System.out.println("Nothing to set.");
            return;
        }
        try {
            sheets.updateCells(Integer.parseInt(row), cells);
            System.out.println("Row " + row + " updated: " + cells.keySet());
        } catch (IOException | RuntimeException e) {
            System.out.println("Could not update row " + row + ": " + e.getMessage());
        }
    }

    /**
     * {@code sheet-add --company=.. --role=.. --city=.. --link=.. [--date=YYYY-MM-DD]
     * [--notes=..]} - append a row for an application job-radar never saw, such
     * as a role found on a company's own site.
     */
    public void add(Map<String, String> options) {
        String company = options.get("company");
        String role = options.get("role");
        if (company == null || role == null) {
            System.out.println("Usage: sheet-add --company=... --role=... [--city=...] "
                    + "[--link=...] [--date=YYYY-MM-DD] [--notes=...]");
            return;
        }
        LocalDate applied = options.containsKey("date")
                ? LocalDate.parse(options.get("date")) : LocalDate.now();
        ApplicationRow row = new ApplicationRow(company, role, options.get("city"), applied,
                ApplicationRow.STATUS_APPLIED, null, "not recorded", options.get("link"),
                options.getOrDefault("notes", "added by hand"));
        try {
            int written = sheets.appendApplication(row);
            System.out.println("Appended " + company + " · " + role + " as tracker row " + written + ".");
        } catch (IOException | RuntimeException e) {
            System.out.println("Could not append: " + e.getMessage());
        }
    }
}
