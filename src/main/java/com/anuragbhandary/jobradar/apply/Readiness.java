package com.anuragbhandary.jobradar.apply;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What an application adds up to, counted one way.
 *
 * <h2>The rule</h2>
 * <strong>Every field falls in exactly one bucket, and the bucket is
 * {@link ApplicationField#outcome()}.</strong> The seven counts therefore sum to
 * {@link #total()}, always, and a summary line can be read as a partition rather
 * than as seven overlapping filters that might double-count a field which is both
 * resolved and blocked.
 *
 * <p>That case is the reason the rule is written down. A field can be
 * {@code RESOLVED} and {@code BLOCKED} at once - Job Radar knows the answer, the
 * page would not take it - and counting it under both "prepared" and "needs you"
 * produces a summary whose numbers exceed the number of fields. It is counted
 * once, as {@link #automationBlocked()}, because that is the thing a person has
 * to act on.
 *
 * <p>No percentages and no estimates. "18 fields prepared" is a fact; "94% ready"
 * is a number nobody can check.
 */
public record Readiness(
        int total,
        int filled,
        int ready,
        int automationBlocked,
        int awaitingApproval,
        int awaitingAnswer,
        int skippedOptional,
        int declined,
        int conflicts) {

    public static Readiness of(List<ApplicationField> fields) {
        return of(fields, 0);
    }

    /**
     * @param conflicts questions where two stored answers disagree. Counted apart
     *                  from the seven outcomes because a conflict is a property of
     *                  the knowledge rather than of the field, and the field
     *                  itself is already counted under whatever was filled.
     */
    public static Readiness of(List<ApplicationField> fields, int conflicts) {
        Map<ApplicationField.Outcome, Integer> counts =
                new EnumMap<>(ApplicationField.Outcome.class);
        for (ApplicationField.Outcome outcome : ApplicationField.Outcome.values()) {
            counts.put(outcome, 0);
        }
        for (ApplicationField field : fields) {
            counts.merge(field.outcome(), 1, Integer::sum);
        }
        return new Readiness(
                fields.size(),
                counts.get(ApplicationField.Outcome.FILLED),
                counts.get(ApplicationField.Outcome.READY),
                counts.get(ApplicationField.Outcome.AUTOMATION_BLOCKED),
                counts.get(ApplicationField.Outcome.AWAITING_APPROVAL),
                counts.get(ApplicationField.Outcome.AWAITING_ANSWER),
                counts.get(ApplicationField.Outcome.SKIPPED),
                counts.get(ApplicationField.Outcome.DECLINED),
                conflicts);
    }

    /** Filled or settled and waiting to be typed. The number that means "done". */
    public int prepared() {
        return filled + ready;
    }

    /** True when nothing is waiting on a decision from the applicant. */
    public boolean nothingOutstanding() {
        return awaitingAnswer == 0 && awaitingApproval == 0 && conflicts == 0;
    }

    /**
     * Whether the browser can usefully be pointed at this form again.
     *
     * <p>Opening is not submitting, so a blocked control does not stop it - the
     * whole point of opening is to finish that control by hand. An unanswered
     * required question does stop it, because the form would be re-filled with a
     * gap in it and the person would be back where they started. The exception is
     * the one the manual path exists for: when the board itself needs a person -
     * a captcha, a sign-in - opening is the only move left and the answers ride
     * along with it.
     */
    public boolean canOpen(ManualReason manualReason) {
        return manualReason != null || awaitingAnswer == 0;
    }

    /** Everything that would stop a submission, named, for a screen to list. */
    public List<String> blockers() {
        List<String> blockers = new ArrayList<>();
        if (awaitingAnswer > 0) {
            blockers.add(awaitingAnswer + " required question"
                    + (awaitingAnswer == 1 ? "" : "s") + " with no answer");
        }
        if (awaitingApproval > 0) {
            blockers.add(awaitingApproval + " draft"
                    + (awaitingApproval == 1 ? "" : "s") + " you have not approved");
        }
        if (conflicts > 0) {
            blockers.add(conflicts + " question" + (conflicts == 1 ? "" : "s")
                    + " where saved answers disagree");
        }
        if (automationBlocked > 0) {
            blockers.add(automationBlocked + " field"
                    + (automationBlocked == 1 ? "" : "s")
                    + " the website would not let the browser fill");
        }
        return List.copyOf(blockers);
    }

    /** The one-line version. Only the counts that are not zero. */
    public String summary() {
        List<String> parts = new ArrayList<>();
        parts.add(prepared() + " of " + total + " fields prepared");
        if (awaitingApproval > 0) {
            parts.add(awaitingApproval + " awaiting your approval");
        }
        if (awaitingAnswer > 0) {
            parts.add(awaitingAnswer + " awaiting your answer");
        }
        if (automationBlocked > 0) {
            parts.add(automationBlocked + " needing manual completion");
        }
        if (conflicts > 0) {
            parts.add(conflicts + " in conflict");
        }
        if (skippedOptional > 0) {
            parts.add(skippedOptional + " optional left blank");
        }
        return String.join(" · ", parts);
    }
}
