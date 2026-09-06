package com.anuragbhandary.jobradar.apply.form;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Matches an answer to one of a dropdown's actual options.
 *
 * <p>A select cannot be typed into: the answer has to be one of the strings the
 * board chose, and those strings are long. "Yes" has to reach "Yes, I am legally
 * authorized to work in this country"; "Asian" has to reach "Asian (Not Hispanic
 * or Latino)"; "Male" must not reach "Female" by being a substring of nothing in
 * particular.
 *
 * <p>The matching is deliberately staged from exact to loose, and it
 * <strong>returns empty rather than a best guess</strong>. An unmatched option on
 * a required field stops the application. That is the whole design: on a form
 * where "Yes" and "No" are both present, a near-miss is not a small error.
 */
public final class OptionMatcher {

    private OptionMatcher() {
    }

    /**
     * Words that flip a sentence's meaning, so a candidate option containing one
     * cannot match an answer that does not.
     *
     * <p>Without this, "No" matches "No, I do not require sponsorship" and also
     * "Yes, I will now or in the future require sponsorship" - the second because
     * "no" appears inside "now". Word-boundary matching alone is not enough:
     * "Yes" genuinely is a prefix of both "Yes, I am authorized" and "Yes, I
     * require sponsorship", which is why polarity is decided by the caller and
     * only ever looked up here.
     */
    private static final List<String> NEGATIONS = List.of("not", "no", "never", "decline", "n/a");

    /**
     * The best option for {@code answer}, or empty if none is clearly right.
     *
     * @param answer  what the profile says
     * @param options the strings the form actually offers
     */
    public static Optional<String> match(String answer, List<String> options) {
        if (answer == null || answer.isBlank() || options == null || options.isEmpty()) {
            return Optional.empty();
        }
        String wanted = norm(answer);

        // 1. Exact, ignoring case and punctuation. The common case.
        for (String option : options) {
            if (norm(option).equals(wanted)) {
                return Optional.of(option);
            }
        }

        // 2. The option begins with the answer: "Yes" -> "Yes, I am authorized...".
        //    Anchored at the start so that "No" cannot reach "...I do not require",
        //    which is the same sentence with the opposite meaning.
        for (String option : options) {
            String candidate = norm(option);
            if (candidate.startsWith(wanted + " ") || candidate.startsWith(wanted + ",")) {
                return Optional.of(option);
            }
        }

        // 3. Whole-word containment, with a polarity guard.
        //    "Asian" -> "Asian (Not Hispanic or Latino)" has to work, and that
        //    option contains "Not" - so the guard only fires when the answer
        //    itself is a yes/no, where negation changes everything.
        boolean polar = isPolar(wanted);
        String onlyMatch = null;
        for (String option : options) {
            String candidate = norm(option);
            if (!containsWord(candidate, wanted)) {
                continue;
            }
            if (polar && negates(candidate) != negates(wanted)) {
                continue;
            }
            if (onlyMatch != null) {
                // Ambiguous: two options both contain the answer. Refuse.
                return Optional.empty();
            }
            onlyMatch = option;
        }
        return Optional.ofNullable(onlyMatch);
    }

    /**
     * The option meaning "prefer not to say", if the form offers one.
     *
     * <p>Every EEO dropdown has one and every board words it differently. Used
     * for questions the profile deliberately leaves unset - a decline is an
     * answer, and choosing it explicitly is better than leaving a required
     * dropdown untouched.
     */
    public static Optional<String> declineOption(List<String> options) {
        if (options == null) {
            return Optional.empty();
        }
        for (String option : options) {
            String candidate = norm(option);
            if (candidate.contains("decline") || candidate.contains("prefer not")
                    || candidate.contains("do not wish") || candidate.contains("don t wish")
                    || candidate.contains("choose not") || candidate.contains("rather not")
                    || candidate.contains("not disclose") || candidate.contains("no answer")) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    private static boolean isPolar(String value) {
        return value.equals("yes") || value.equals("no")
                || value.startsWith("yes ") || value.startsWith("no ");
    }

    private static boolean negates(String value) {
        for (String negation : NEGATIONS) {
            if (containsWord(value, negation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean startOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int end = at + needle.length();
            boolean endOk = end == haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static String norm(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[*:_/\\\\()\\[\\].?!\"']", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
