package com.anuragbhandary.jobradar.apply;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the questions that stopped applications into profile entries.
 *
 * <p>Pure. Attempts in, YAML out. The value is entirely in the grouping: one
 * application blocked by "Do you have a valid passport?" is a nuisance, and the
 * same question having blocked nine applications across six boards is the single
 * highest-value line to add to {@code applicant.yml}. Frequency is the ranking,
 * and it is only visible across attempts.
 */
public final class AnswerBank {

    private AnswerBank() {
    }

    /**
     * @param key       the substring an {@code extra-answers} entry would use
     * @param timesSeen how many applications this stopped
     * @param required  true if it was ever a required field, which is the
     *                  difference between an application that stopped and one that
     *                  merely left a box empty
     */
    public record Suggestion(
            String key,
            String exampleLabel,
            int timesSeen,
            boolean required,
            List<String> options,
            Optional<String> proposedAnswer) {
    }

    /**
     * The unanswered questions, most frequent first.
     *
     * <p>Grouped by {@link OpenQuestion#suggestedKey()} rather than by the exact
     * label, because the same question is worded differently on every board and
     * grouping by full text produces a list as long as the input - which is the
     * report doing no work at all.
     */
    public static List<Suggestion> suggest(List<ApplicationAttempt> attempts) {
        Map<String, List<OpenQuestion>> grouped = new LinkedHashMap<>();
        for (ApplicationAttempt attempt : attempts) {
            for (OpenQuestion question : OpenQuestion.parse(attempt.getOpenQuestions())) {
                grouped.computeIfAbsent(question.suggestedKey(), k -> new ArrayList<>())
                        .add(question);
            }
        }

        List<Suggestion> suggestions = new ArrayList<>();
        grouped.forEach((key, questions) -> {
            OpenQuestion first = questions.getFirst();
            List<String> options = questions.stream()
                    .map(OpenQuestion::options)
                    .filter(o -> !o.isEmpty())
                    .findFirst()
                    .orElse(List.of());
            suggestions.add(new Suggestion(
                    key,
                    first.label(),
                    questions.size(),
                    questions.stream().anyMatch(OpenQuestion::required),
                    options,
                    propose(options)));
        });

        // Required first, then by how often it has cost an application.
        suggestions.sort(Comparator
                .comparing(Suggestion::required).reversed()
                .thenComparing(Comparator.comparingInt(Suggestion::timesSeen).reversed())
                .thenComparing(Suggestion::key));
        return List.copyOf(suggestions);
    }

    /**
     * A starting answer where the options make one obvious, and nothing otherwise.
     *
     * <p>Only ever proposes a decline. Anything else would be this tool deciding
     * what the applicant's answer to an unseen question is, which is exactly what
     * the whole design refuses to do - the suggestion is a line to fill in, not a
     * line to accept.
     */
    private static Optional<String> propose(List<String> options) {
        return com.anuragbhandary.jobradar.apply.form.OptionMatcher.declineOption(options);
    }

    /**
     * Ready to paste under {@code job-radar.applicant.extra-answers}.
     *
     * <p>Emitted in the list form the profile actually binds. It used to emit
     * {@code "key": "value"} pairs, which was the shape before extra-answers
     * became a list - so every suggestion this produced was silently ignored by
     * the thing it was written for.
     */
    public static String toYaml(List<Suggestion> suggestions) {
        if (suggestions.isEmpty()) {
            return "";
        }
        StringBuilder yaml = new StringBuilder();
        for (Suggestion suggestion : suggestions) {
            yaml.append("      # ").append(suggestion.exampleLabel())
                    .append("  (").append(suggestion.timesSeen()).append("x")
                    .append(suggestion.required() ? ", REQUIRED - blocks the form" : "")
                    .append(")\n");
            if (!suggestion.options().isEmpty()) {
                // The options are half the answer: they say what the entry has to
                // say to match.
                yaml.append("      #   pick one: ")
                        .append(String.join(" | ", suggestion.options())).append('\n');
            }
            yaml.append("      - match: ").append(quote(suggestion.key())).append('\n')
                    .append("        answer: ")
                    .append(quote(suggestion.proposedAnswer().orElse(""))).append('\n');
        }
        return yaml.toString();
    }

    /** Double-quoted, with the two characters that would break the string escaped. */
    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
