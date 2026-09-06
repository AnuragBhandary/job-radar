package com.anuragbhandary.jobradar.sheets;

import com.anuragbhandary.jobradar.domain.Posting;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One row of the application tracker.
 *
 * <p>The column order is the spreadsheet's, not this program's. The sheet is a
 * document the user maintains by hand and has been using since before this tool
 * existed; the tool fits the sheet.
 *
 * @param experienceWording the requirement quoted verbatim from the posting.
 *                          Written down at application time because it is the
 *                          thing most often misremembered afterwards, and it
 *                          decides whether a rejection was predictable.
 */
public record ApplicationRow(
        String company,
        String role,
        String countryCity,
        LocalDate dateApplied,
        String status,
        LocalDate postingDate,
        String experienceWording,
        String officialLink,
        String notes) {

    /** Column headings as they appear in row 6. */
    public static final List<String> COLUMNS = List.of(
            "Company", "Role", "Country/City", "Date Applied", "Status",
            "Posting Date", "Experience Wording on Req", "Official Link", "Notes");

    /** The only status a newly appended row may carry. */
    public static final String STATUS_APPLIED = "Applied";

    public static ApplicationRow from(Posting posting, String company, LocalDate appliedOn) {
        return new ApplicationRow(
                company,
                posting.getTitle(),
                posting.getLocation(),
                appliedOn,
                STATUS_APPLIED,
                posting.getPostedDate(),
                experienceWording(posting),
                posting.getUrl(),
                "");
    }

    /**
     * The years requirement in the posting's own words, or an explicit statement
     * that it gave none.
     *
     * <p>"none stated" is written out rather than left blank, because a blank
     * cell reads as "not filled in yet" and this is a finding.
     */
    private static String experienceWording(Posting posting) {
        Integer minYears = posting.getMinYears();
        if (minYears == null || minYears < 0) {
            return "none stated";
        }
        return minYears == 0 ? "0 years / entry level" : minYears + "+ years";
    }

    public ApplicationRow withNotes(String newNotes) {
        return new ApplicationRow(company, role, countryCity, dateApplied, status,
                postingDate, experienceWording, officialLink, newNotes);
    }

    /** In sheet column order, with nulls as empty cells rather than the text "null". */
    public List<Object> toCells() {
        List<Object> cells = new ArrayList<>(COLUMNS.size());
        for (Object value : new Object[]{
                company, role, countryCity, dateApplied, status,
                postingDate, experienceWording, officialLink, notes}) {
            cells.add(value == null ? "" : value.toString());
        }
        return cells;
    }
}
