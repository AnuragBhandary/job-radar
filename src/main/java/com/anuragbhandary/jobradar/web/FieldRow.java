package com.anuragbhandary.jobradar.web;

import java.util.ArrayList;
import java.util.List;

/**
 * One line of a stored field log, parsed back for the review table.
 *
 * <p>The log is written by {@code FillReport.Entry.describe()} as a line of prose
 * so it reads in a terminal and in the markdown review file. Reading it back for
 * a table means parsing our own format, which is the trade: one format that is
 * legible everywhere, at the cost of this class.
 *
 * <p>Parsing is total. Every line yields a row, and a line that matches nothing
 * becomes a row with the whole line as its label. A field log is the audit trail
 * of what was typed into somebody's application, so dropping a line because it
 * did not fit a pattern is the one thing this must not do.
 *
 * @param origin PROFILE, DERIVED or GENERATED where the log recorded one
 * @param why    the reasoning behind a derived answer, where there is one
 */
public record FieldRow(String status, String label, String value, String origin, String why) {

    private static final String FILLED = "filled  ";
    private static final String SKIPPED = "skipped ";
    private static final String FAILED = "FAILED  ";

    public static List<FieldRow> parse(String log) {
        List<FieldRow> rows = new ArrayList<>();
        if (log == null || log.isBlank()) {
            return rows;
        }
        for (String line : log.split("\n")) {
            if (!line.isBlank()) {
                rows.add(parseLine(line));
            }
        }
        return rows;
    }

    private static FieldRow parseLine(String line) {
        if (line.startsWith(FILLED)) {
            String rest = line.substring(FILLED.length());
            String origin = null;
            String why = null;
            // describe() appends "   [DERIVED: the reasoning]" for the answers
            // worth reviewing.
            int marker = rest.lastIndexOf("   [");
            if (marker > 0 && rest.endsWith("]")) {
                String tail = rest.substring(marker + 4, rest.length() - 1);
                int colon = tail.indexOf(':');
                origin = colon < 0 ? tail : tail.substring(0, colon);
                why = colon < 0 ? null : tail.substring(colon + 1).trim();
                rest = rest.substring(0, marker);
            }
            int split = rest.indexOf(" = ");
            return split < 0
                    ? new FieldRow("filled", rest, "", origin, why)
                    : new FieldRow("filled", rest.substring(0, split),
                            rest.substring(split + 3), origin, why);
        }
        if (line.startsWith(SKIPPED)) {
            return withReason("skipped", line.substring(SKIPPED.length()));
        }
        if (line.startsWith(FAILED)) {
            return withReason("FAILED", line.substring(FAILED.length()));
        }
        return new FieldRow("", line, "", null, null);
    }

    /** Skipped and failed lines are "label — reason", with an em dash. */
    private static FieldRow withReason(String status, String rest) {
        int split = rest.indexOf(" — ");
        return split < 0
                ? new FieldRow(status, rest, "", null, null)
                : new FieldRow(status, rest.substring(0, split),
                        rest.substring(split + 3), null, null);
    }

    /** The CSS modifier for this row's status colour. */
    public String statusClass() {
        return switch (status) {
            case "filled" -> "st-filled";
            case "FAILED" -> "st-failed";
            default -> "st-skipped";
        };
    }

    /**
     * A long absolute path shown as its file name.
     *
     * <p>The resume value is the full path it was written to, which is right for
     * an audit trail and useless in a table cell: forty characters of directory
     * before the part that identifies the document.
     */
    public String displayValue() {
        if ("filled".equals(status) && value.startsWith("/") && value.contains("/")) {
            return value.substring(value.lastIndexOf('/') + 1);
        }
        return value;
    }

    /** A skipped or failed row's value is a reason, and is set in italics. */
    public boolean valueIsReason() {
        return !"filled".equals(status);
    }
}
