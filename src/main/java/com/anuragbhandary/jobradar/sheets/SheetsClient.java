package com.anuragbhandary.jobradar.sheets;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.config.SheetsConfig.SheetsClientFactory;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads and appends rows in the application tracker.
 *
 * <p>The sheet predates this tool and is edited by hand, so the contract here is
 * deliberately narrow: <strong>append only.</strong> Nothing is ever deleted or
 * overwritten. Changing an existing row happens through
 * {@link #updateStatus(int, String)}, which is a separate, explicit call that a
 * command has to mean to make.
 *
 * <p>That is not caution for its own sake. The tracker is the only record of
 * where the user has applied; a bug that overwrites it destroys information that
 * exists nowhere else, and no amount of re-fetching brings it back.
 */
@Component
public class SheetsClient {

    private static final Logger log = LoggerFactory.getLogger(SheetsClient.class);

    /**
     * Where the data starts if the header cannot be found at all.
     *
     * <p>Not a constant to rely on. The header was documented as row 6 and was
     * found at row 7 on the live sheet - somebody had added a line to the
     * instructions above it. Anything that assumes a fixed offset here reads the
     * header as an application, and would eventually write over a real row.
     */
    private static final int FALLBACK_FIRST_DATA_ROW = 7;

    /** First column of the header row, used to locate it. */
    private static final String HEADER_FIRST_CELL = "company";

    /** Columns A to I - Company through Notes. */
    private static final String COLUMN_RANGE = "A:I";

    private final SheetsClientFactory factory;
    private final AppProperties.Google config;

    public SheetsClient(SheetsClientFactory factory, AppProperties properties) {
        this.factory = factory;
        this.config = properties.google();
    }

    public boolean isConfigured() {
        return config.isConfigured();
    }

    /**
     * Every application already recorded, in sheet order.
     *
     * @return one entry per data row; the row number is the real spreadsheet row,
     *         so it can be passed to {@link #updateStatus(int, String)}
     */
    public List<ExistingApplication> readExistingApplications() throws IOException {
        ValueRange response = factory.sheets().spreadsheets().values()
                .get(config.spreadsheetId(), range(COLUMN_RANGE))
                .execute();

        List<List<Object>> values = response.getValues();
        if (values == null) {
            return List.of();
        }

        int firstDataRow = findFirstDataRow(values);

        List<ExistingApplication> applications = new java.util.ArrayList<>();
        for (int i = firstDataRow - 1; i < values.size(); i++) {
            List<Object> row = values.get(i);
            if (row == null || row.isEmpty()) {
                continue;
            }
            String company = cell(row, 0);
            if (company.isBlank()) {
                continue;
            }
            applications.add(new ExistingApplication(
                    i + 1, company, cell(row, 1), parseDate(cell(row, 3)),
                    cell(row, 4), cell(row, 7)));
        }
        return applications;
    }

    /**
     * Company names already applied to, lowercased for comparison.
     *
     * <p>Returns an empty set rather than failing when Sheets is unconfigured or
     * unreachable: not being able to read the tracker should cost the digest its
     * suppression, not its existence.
     */
    public Set<String> appliedCompanies() {
        if (!isConfigured()) {
            return Set.of();
        }
        try {
            return readExistingApplications().stream()
                    .map(a -> a.company().toLowerCase(Locale.ROOT).trim())
                    .collect(Collectors.toSet());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the tracker, so the digest will not suppress "
                    + "companies already applied to: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * Appends one application below the last used row.
     *
     * <p>{@code RAW} matters: it stops Sheets reinterpreting what is written -
     * turning a date into a serial number, or a role like "Engineer I" into
     * something it thinks is a formula. Writing through the API also sidesteps
     * the autocomplete corruption that affects typing into the grid, where Sheets
     * offers a longer previous entry and Tab accepts it silently.
     *
     * @return the row number written
     */
    public int appendApplication(ApplicationRow application) throws IOException {
        AppendValuesResponse response = factory.sheets().spreadsheets().values()
                .append(config.spreadsheetId(), range(COLUMN_RANGE),
                        new ValueRange().setValues(List.of(application.toCells())))
                .setValueInputOption("RAW")
                // Insert rows rather than overwrite whatever follows the data.
                .setInsertDataOption("INSERT_ROWS")
                .setIncludeValuesInResponse(false)
                .execute();

        String updatedRange = response.getUpdates() == null
                ? null : response.getUpdates().getUpdatedRange();
        int row = parseRow(updatedRange);
        log.info("Appended {} — {} at row {}",
                application.company(), application.role(), row);
        return row;
    }

    /**
     * Locates the row after the header, by looking for it rather than assuming.
     *
     * @param values all rows, zero-indexed
     * @return the first data row as a one-indexed spreadsheet row number
     */
    static int findFirstDataRow(List<List<Object>> values) {
        for (int i = 0; i < values.size(); i++) {
            List<Object> row = values.get(i);
            if (row != null && !row.isEmpty()
                    && HEADER_FIRST_CELL.equalsIgnoreCase(cell(row, 0).trim())) {
                return i + 2;
            }
        }
        return FALLBACK_FIRST_DATA_ROW;
    }

    /**
     * Changes the status of one existing row.
     *
     * <p>Separate from appending on purpose. This is the only method that writes
     * over an existing cell, it touches exactly one, and a caller has to name the
     * row to reach it.
     */
    public void updateStatus(int rowNumber, String status) throws IOException {
        if (rowNumber < FALLBACK_FIRST_DATA_ROW) {
            throw new IllegalArgumentException(
                    "Row " + rowNumber + " is in the header or the HOW TO USE block; "
                            + "data starts at row " + FALLBACK_FIRST_DATA_ROW);
        }
        String cell = "E" + rowNumber;
        factory.sheets().spreadsheets().values()
                .update(config.spreadsheetId(), range(cell),
                        new ValueRange().setValues(List.of(List.of(status))))
                .setValueInputOption("RAW")
                .execute();
        log.info("Set status of row {} to {}", rowNumber, status);
    }

    private String range(String cells) {
        String sheetName = config.sheetName();
        return sheetName == null || sheetName.isBlank()
                ? cells
                : "'" + sheetName.replace("'", "''") + "'!" + cells;
    }

    private static String cell(List<Object> row, int index) {
        return index < row.size() && row.get(index) != null ? row.get(index).toString() : "";
    }

    /** Pulls the row number out of a range like {@code 'Untitled'!A42:I42}. */
    static int parseRow(String updatedRange) {
        if (updatedRange == null) {
            return -1;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("![A-Z]+(\\d+)").matcher(updatedRange);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = java.util.regex.Pattern.compile("^[A-Z]+(\\d+)").matcher(updatedRange);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * The date in column D, or null if it cannot be read.
     *
     * <p>Null rather than today: the follow-up logic measures age from this, and
     * a missing date substituted with today makes an application that has been
     * silent for two months look like it was sent this morning. It is written by
     * this tool in ISO form, but the sheet predates the tool and older rows were
     * typed by hand in whatever format was convenient.
     */
    static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, format);
            } catch (DateTimeParseException ignored) {
                // Try the next one. A date this cannot read is reported as
                // unknown, which is visible, rather than guessed at.
            }
        }
        return null;
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("d MMM uuuu"),
            DateTimeFormatter.ofPattern("d MMMM uuuu"),
            DateTimeFormatter.ofPattern("MMM d, uuuu"));

    /** An application already in the sheet. */
    public record ExistingApplication(
            int rowNumber, String company, String role, LocalDate dateApplied,
            String status, String link) {
    }
}
