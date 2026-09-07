package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.apply.form.FieldClassifier;
import com.anuragbhandary.jobradar.apply.form.FillReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A question a form asked that the profile could not answer.
 *
 * <p>Recorded so the profile can learn. Every blocked application names a question,
 * and the same questions recur across boards - "Are you at least 18?", "Do you have
 * a valid passport?", "Notice period?" - so the tenth application should stop for
 * fewer reasons than the first.
 *
 * <p>Persisted as tab-separated lines on the attempt rather than as its own table.
 * It is write-once, read-by-one-command, never queried by field, and a table would
 * be a join and a migration for something that is a log.
 *
 * @param options the choices, where it was a dropdown. Kept because they are half
 *                the answer: a question offering "Yes / No / Prefer not to say"
 *                tells you what the entry in the profile has to say to match.
 */
public record OpenQuestion(String label, String control, boolean required, List<String> options) {

    private static final String FIELD = "\t";
    private static final String OPTION = " | ";

    /** Every unanswered field on a filled form, blockers first. */
    public static List<OpenQuestion> from(FillReport report) {
        List<OpenQuestion> questions = new ArrayList<>();
        for (FillReport.Entry entry : report.entries()) {
            if (entry.filled() || entry.answer().origin() != com.anuragbhandary.jobradar
                    .apply.form.Answer.Origin.UNANSWERED) {
                continue;
            }
            if (entry.field().label() == null || entry.field().label().isBlank()) {
                continue;
            }
            questions.add(new OpenQuestion(
                    entry.field().label().trim(),
                    entry.field().control().name(),
                    entry.field().required(),
                    entry.field().options()));
        }
        return questions;
    }

    public static String serialise(List<OpenQuestion> questions) {
        if (questions == null || questions.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (OpenQuestion question : questions) {
            out.append(clean(question.label())).append(FIELD)
                    .append(question.control()).append(FIELD)
                    .append(question.required()).append(FIELD)
                    .append(String.join(OPTION,
                            question.options().stream().map(OpenQuestion::clean).toList()))
                    .append('\n');
        }
        return out.toString();
    }

    /**
     * Reads the log back, skipping anything malformed.
     *
     * <p>Lenient on purpose: this is a log of things that already went wrong, and a
     * parse failure here must not be a second failure on top of the first. A line
     * that cannot be read is one lost suggestion, not an exception during a report.
     */
    public static List<OpenQuestion> parse(String serialised) {
        if (serialised == null || serialised.isBlank()) {
            return List.of();
        }
        List<OpenQuestion> questions = new ArrayList<>();
        for (String line : serialised.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(FIELD, -1);
            if (parts.length < 3) {
                continue;
            }
            List<String> options = parts.length > 3 && !parts[3].isBlank()
                    ? List.of(parts[3].split(java.util.regex.Pattern.quote(OPTION)))
                    : List.of();
            questions.add(new OpenQuestion(
                    parts[0], parts[1], Boolean.parseBoolean(parts[2]), options));
        }
        return questions;
    }

    /**
     * The key an {@code extra-answers} entry would use: lowercased, punctuation
     * flattened, and shortened to the distinctive part.
     *
     * <p>Short because matching is substring containment, and the whole question is
     * the worst possible key - "Are you at least 18 years of age?" and "Are you at
     * least 18 years old?" are the same question and neither key matches the other.
     * The first six words are almost always enough and almost always shared.
     */
    public String suggestedKey() {
        String[] words = FieldClassifier.normalise(label).split(" ");
        int take = Math.min(words.length, 6);
        return String.join(" ", java.util.Arrays.copyOfRange(words, 0, take))
                .toLowerCase(Locale.ROOT);
    }

    /** A tab or newline inside a label would corrupt the log's own format. */
    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').trim();
    }
}
