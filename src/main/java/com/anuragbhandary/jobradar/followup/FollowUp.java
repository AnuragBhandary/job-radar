package com.anuragbhandary.jobradar.followup;

import com.anuragbhandary.jobradar.sheets.SheetsClient.ExistingApplication;
import java.time.LocalDate;

/**
 * One application that has gone quiet.
 *
 * @param ageDays days since it was sent, or -1 when the sheet has no readable
 *                date. -1 rather than 0: an unknown age is not a new application,
 *                and collapsing the two hides the oldest rows in the sheet -
 *                which are exactly the ones typed by hand before this tool
 *                existed.
 */
public record FollowUp(ExistingApplication application, int ageDays, Urgency urgency) {

    /**
     * How overdue a chase is.
     *
     * <p>Three bands rather than a single cutoff, because the right action differs.
     * A polite nudge at two weeks is normal; at six weeks the honest read is that
     * nobody is coming back and the row should be closed so it stops occupying
     * the list.
     */
    public enum Urgency {
        DUE("chase now"),
        OVERDUE("second chase, or close it"),
        ABANDONED("no reply in six weeks - mark it closed");

        private final String advice;

        Urgency(String advice) {
            this.advice = advice;
        }

        public String advice() {
            return advice;
        }
    }

    public String company() {
        return application.company();
    }

    public String age() {
        return ageDays < 0 ? "date unknown" : ageDays + "d";
    }

    public static FollowUp of(ExistingApplication application, LocalDate today, int dueAfterDays) {
        LocalDate applied = application.dateApplied();
        int age = applied == null
                ? -1
                : (int) java.time.temporal.ChronoUnit.DAYS.between(applied, today);

        Urgency urgency;
        if (age < 0 || age >= dueAfterDays * 4) {
            // An unreadable date is treated as the oldest thing in the sheet.
            // It is, almost always - the hand-typed rows predate the tool.
            urgency = Urgency.ABANDONED;
        } else if (age >= dueAfterDays * 2) {
            urgency = Urgency.OVERDUE;
        } else {
            urgency = Urgency.DUE;
        }
        return new FollowUp(application, age, urgency);
    }
}
