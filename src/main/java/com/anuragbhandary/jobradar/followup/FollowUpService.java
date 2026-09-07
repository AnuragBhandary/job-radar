package com.anuragbhandary.jobradar.followup;

import com.anuragbhandary.jobradar.sheets.SheetsClient;
import com.anuragbhandary.jobradar.sheets.SheetsClient.ExistingApplication;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * Finds the applications that have gone quiet.
 *
 * <p>Reads the sheet rather than the local database, deliberately. The sheet is
 * the record of applications actually sent, it includes the ones made by hand
 * before this tool existed, and its status column is the one a human updates. An
 * attempt row in SQLite only knows what the tool did; it has no idea a recruiter
 * replied.
 *
 * <p>The selection itself is a pure function of rows plus today, which is what
 * makes "does a row typed as 'OA sent' count as still waiting?" answerable in a
 * test instead of by re-reading a spreadsheet.
 */
@Service
public class FollowUpService {

    /**
     * A fortnight. Chasing sooner reads as impatient on a process that routinely
     * takes two weeks to reach a human; later and the requisition has usually been
     * filled.
     */
    public static final int DEFAULT_DUE_AFTER_DAYS = 14;

    private final SheetsClient sheets;

    public FollowUpService(SheetsClient sheets) {
        this.sheets = sheets;
    }

    public boolean isConfigured() {
        return sheets.isConfigured();
    }

    public List<FollowUp> due(int dueAfterDays) throws java.io.IOException {
        return select(sheets.readExistingApplications(), LocalDate.now(), dueAfterDays);
    }

    /**
     * The rows worth chasing, oldest first.
     *
     * <p>Only {@link ApplicationStage#APPLIED} qualifies. An application in
     * process is a live conversation and chasing it is a different act; a closed
     * one is over. A row whose status word is unrecognised is left out too - it is
     * reported separately by {@link #unrecognised}, because the right response is
     * to fix the word rather than to send an email.
     */
    static List<FollowUp> select(
            List<ExistingApplication> rows, LocalDate today, int dueAfterDays) {

        List<FollowUp> due = new ArrayList<>();
        for (ExistingApplication row : rows) {
            if (ApplicationStage.of(row.status()) != ApplicationStage.APPLIED) {
                continue;
            }
            FollowUp candidate = FollowUp.of(row, today, dueAfterDays);
            // A negative age means the date could not be read, and those are
            // always old enough to matter. A positive age below the threshold is
            // simply too soon.
            if (candidate.ageDays() >= 0 && candidate.ageDays() < dueAfterDays) {
                continue;
            }
            due.add(candidate);
        }
        // Oldest first, unknown dates at the top - they are the ones that have
        // been ignored longest.
        due.sort(Comparator.comparingInt((FollowUp f) ->
                f.ageDays() < 0 ? Integer.MAX_VALUE : f.ageDays()).reversed());
        return List.copyOf(due);
    }

    /** Rows whose status word this does not understand. A config problem, not a task. */
    static List<ExistingApplication> unrecognised(List<ExistingApplication> rows) {
        return rows.stream()
                .filter(row -> ApplicationStage.of(row.status()) == ApplicationStage.UNKNOWN)
                .toList();
    }

    public List<ExistingApplication> unrecognisedRows() throws java.io.IOException {
        return unrecognised(sheets.readExistingApplications());
    }

    /** Writes a new status into one row. The only write this service makes. */
    public void setStatus(int rowNumber, String status) throws java.io.IOException {
        sheets.updateStatus(rowNumber, status);
    }
}
