package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.apply.form.Answer;
import com.anuragbhandary.jobradar.apply.form.FormField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Decisions already made about this application, ready to be typed in again.
 *
 * <p>What makes an application resumable. The browser closes when preparation
 * stops, so answering the question that blocked it has to survive to the next
 * run; this carries the {@link ApplicationField} rows back into the fill step as
 * an override layer that sits <em>above</em>
 * {@link com.anuragbhandary.jobradar.apply.form.FieldMapper}.
 *
 * <h2>Why not the knowledge resolver</h2>
 * Because it is not the resolver's turn. The resolver stays non-authoritative
 * until its own gate; what is carried here is narrower and needs no gate at all -
 * a value this applicant looked at, on this application, and settled. It is
 * scoped to one attempt, it comes from rows he can see, and it expires when the
 * attempt does.
 *
 * <p>Keyed on the question as the board worded it, matched the same way
 * {@code FieldRecorder} records it, because the selector is the one thing that
 * genuinely changes between two readings of the same form.
 */
public record PreparedAnswers(Map<String, String> byQuestion) {

    private static final PreparedAnswers NONE = new PreparedAnswers(Map.of());

    public PreparedAnswers {
        byQuestion = byQuestion == null ? Map.of() : Map.copyOf(byQuestion);
    }

    /** Nothing settled yet. A first preparation. */
    public static PreparedAnswers none() {
        return NONE;
    }

    /**
     * The settled values from an attempt's field rows.
     *
     * <p>Only {@link FieldState#RESOLVED}. A draft still awaiting approval and a
     * question still awaiting an answer are exactly the things that must not be
     * typed into an employer's form on a re-run, and a field that was skipped as
     * optional was skipped on purpose.
     */
    public static PreparedAnswers from(List<ApplicationField> fields) {
        Map<String, String> settled = new LinkedHashMap<>();
        for (ApplicationField field : fields) {
            if (field.getState() == FieldState.RESOLVED
                    && field.getResolvedValue() != null
                    && !field.getResolvedValue().isBlank()) {
                settled.put(key(field.getRawLabel()), field.getResolvedValue());
            }
        }
        return new PreparedAnswers(settled);
    }

    public boolean isEmpty() {
        return byQuestion.isEmpty();
    }

    /**
     * The settled answer for one field of a freshly read form, if there is one.
     *
     * <p>Returned as {@code PROFILE} origin rather than a new one: as far as the
     * fill report is concerned this is a value that was looked up rather than
     * worked out, and inventing a sixth origin would change the meaning of every
     * existing count that reads them.
     */
    public Optional<Answer> forField(FormField field) {
        if (field == null || byQuestion.isEmpty()) {
            return Optional.empty();
        }
        String value = byQuestion.get(key(labelOf(field)));
        return value == null || value.isBlank() ? Optional.empty()
                : Optional.of(new Answer(value, Answer.Origin.PROFILE,
                        "you settled this earlier in this application"));
    }

    /** The same fallback {@code FieldRecorder} uses, so the two sides agree. */
    private static String labelOf(FormField field) {
        String label = field.label();
        return label == null || label.isBlank() ? field.selector() : label;
    }

    private static String key(String question) {
        return question == null ? "" : question.trim().toLowerCase(Locale.ROOT);
    }
}
