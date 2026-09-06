package com.anuragbhandary.jobradar.apply.form;

import java.util.List;

/**
 * What happened when the form was filled, field by field.
 *
 * <p>This is the object the review file is printed from, and the reason the fill
 * step returns something instead of just having an effect. A form that is 90%
 * filled looks exactly like one that is 100% filled when you glance at a browser
 * window.
 *
 * @param blockers required fields with no answer. Non-empty means the application
 *                 cannot be submitted, by anyone, until the profile learns
 *                 something - which is the point.
 */
public record FillReport(List<Entry> entries, List<Entry> blockers) {

    /**
     * @param error the exception message when the field could not be filled at
     *              all - it moved, the page re-rendered, the selector went stale
     */
    public record Entry(FormField field, Answer answer, boolean filled, String error) {

        public String describe() {
            String label = field.label().isBlank() ? field.selector() : field.label();
            if (error != null) {
                return "FAILED  " + label + " — " + error;
            }
            if (!filled) {
                return "skipped " + label + " — " + answer.note();
            }
            String value = field.kind() == FieldKind.COVER_LETTER_TEXT
                    ? "(cover letter, " + answer.value().length() + " chars)"
                    : answer.value();
            return "filled  " + label + " = " + value
                    + (answer.needsReview() ? "   [" + answer.origin() + "]" : "");
        }
    }

    public boolean isSubmittable() {
        return blockers.isEmpty() && entries.stream().noneMatch(e -> e.error() != null);
    }

    public List<Entry> needingReview() {
        return entries.stream().filter(e -> e.filled() && e.answer().needsReview()).toList();
    }

    public long filledCount() {
        return entries.stream().filter(Entry::filled).count();
    }
}
