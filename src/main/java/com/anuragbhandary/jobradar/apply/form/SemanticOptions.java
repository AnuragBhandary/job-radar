package com.anuragbhandary.jobradar.apply.form;

import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.Concepts;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns a semantic yes or no into the sentence a particular form offers.
 *
 * <p>{@link OptionMatcher} matches on words: "Yes" reaches "Yes, I am
 * authorized...". That covers most forms and leaves a gap the shadow measured at
 * eighty-one comparisons - the ones where a board never writes "yes" at all:
 *
 * <pre>
 *   Do you require sponsorship?
 *     ( ) I will require sponsorship
 *     ( ) I do not require sponsorship
 * </pre>
 *
 * <p>The answer is "Yes" and no option contains it, so the word matcher gave up
 * and a correctly derived answer reached a required field as a blank. What is
 * needed is not a looser match - a looser match picks the wrong one of those two -
 * but a different question: <em>which option asserts the thing the concept is
 * about?</em>
 *
 * <h2>How polarity is decided</h2>
 * The proposition belongs to the concept. {@code sponsorship.required} is the
 * claim "I require sponsorship"; an option affirms it if it mentions sponsorship
 * without negating it, and denies it if it mentions sponsorship and negates. The
 * semantic answer then picks the affirming or the denying one.
 *
 * <p>Never by looking for the word "yes", and never by nearest match. If two
 * options affirm, or none does, or the wording says nothing the concept
 * recognises, this returns unmatched - and an unmatched required field becomes a
 * task for a person rather than a coin toss. On a form where the two options are
 * opposites, a near miss is not a small error.
 */
public final class SemanticOptions {

    private SemanticOptions() {
    }

    /**
     * What each boolean concept is actually asserting.
     *
     * <p>Hand-written and short, like the technology vocabulary and for the same
     * reason: the boards phrase these a dozen ways and the underlying claim is
     * one of about ten. A term here has to be the <em>subject</em> of the
     * question - "sponsor" for sponsorship, "authoris" for work authorisation -
     * because that is what tells an option about this question apart from an
     * option about something else on the same form.
     *
     * <p>A concept absent from this map is never matched semantically. That is
     * the conservative default: the word matcher still runs, and anything it
     * cannot place stops and asks.
     */
    private static final Map<String, List<String>> ASSERTS = Map.ofEntries(
            Map.entry(Concepts.SPONSORSHIP_REQUIRED.id(),
                    List.of("sponsor", "sponsorship", "visa support", "work visa")),
            Map.entry(Concepts.WORK_AUTHORISATION.id(),
                    List.of("authoris", "authoriz", "eligible to work", "right to work",
                            "legally able to work", "legally permitted", "permit",
                            "citizen", "permanent resident", "legally entitled",
                            "visa")),
            Map.entry(Concepts.RELOCATION_WILLING.id(),
                    List.of("relocat", "willing to move", "prepared to move")),
            Map.entry(Concepts.REMOTE_WORK_WILLING.id(),
                    List.of("remote", "work from home", "from the office", "on-site",
                            "onsite")),
            Map.entry(Concepts.PREVIOUSLY_APPLIED.id(),
                    List.of("applied", "application before")),
            Map.entry(Concepts.WORKED_HERE_BEFORE.id(),
                    List.of("worked", "employed by", "employee of")),
            Map.entry(Concepts.RELATED_TO_EMPLOYEE.id(),
                    List.of("relat", "family", "relative")),
            Map.entry(Concepts.AGE_OVER_18.id(), List.of("18", "age of majority")),
            Map.entry(Concepts.VALID_PASSPORT.id(), List.of("passport")),
            Map.entry(Concepts.CURRENTLY_EMPLOYED.id(),
                    List.of("currently employed", "employed", "in a role")));

    /**
     * Phrases that reverse an option's meaning for one particular question.
     *
     * <p>Needed because the same word points opposite ways depending on what is
     * being asked. "I will need a work permit" mentions a permit and contains no
     * negation, and it means he is <em>not</em> authorised; "I need sponsorship"
     * mentions sponsorship, contains no negation, and means he <em>does</em>
     * require it. Reading either by looking for "not" gets one of them backwards.
     *
     * <p>Only work authorisation needs this today. It is a map rather than a
     * special case because the next concept that needs it will be another
     * question where wanting something means lacking it.
     */
    private static final Map<String, List<String>> INVERTS = Map.of(
            Concepts.WORK_AUTHORISATION.id(),
            List.of("need", "needs", "require", "requires", "sponsor", "sponsorship",
                    "would need", "will need", "apply for"));

    /**
     * Ways an option says the opposite.
     *
     * <p>"do not" and "don't" are listed separately from bare "not" because the
     * normalisation strips apostrophes and a bare "no" appears inside "now" -
     * which is how "No" once matched "Yes, I will now or in the future require
     * sponsorship".
     */
    private static final Pattern DENIES = Pattern.compile(
            "\\b(do not|dont|does not|doesnt|will not|wont|am not|is not|are not|"
            + "isnt|arent|no|not|never|neither|nor|without|unable|ineligible|"
            + "unauthoris|unauthoriz|none)\\b");

    /**
     * A question phrased in the negative.
     *
     * <p>Rare, and severe when mishandled. Only matched where the negation sits
     * on the question's own verb - "are you not authorised", "do you not require"
     * - which is narrow enough not to fire on "are you authorised to work without
     * sponsorship".
     */
    private static final Pattern NEGATED_QUESTION = Pattern.compile(
            "\\b(are|is|do|does|will|have|has|can)\\s+(you\\s+)?(not|never)\\b");

    /**
     * @param option    the string to select, when one was chosen
     * @param why       how it was chosen, for the record and the explanation panel
     */
    public record Match(Optional<String> option, String why) {

        public boolean matched() {
            return option.isPresent();
        }

        static Match of(String option, String why) {
            return new Match(Optional.of(option), why);
        }

        static Match none(String why) {
            return new Match(Optional.empty(), why);
        }
    }

    /**
     * The option this form offers for a semantic answer.
     *
     * @param concept  what the question is about. Without it there is no
     *                 proposition to test the options against, and only the word
     *                 matcher runs.
     * @param question the label as the board wrote it, which is the only place
     *                 the question's own polarity is visible
     */
    public static Match choose(Concept concept, String question, String answer,
            List<String> options) {

        if (answer == null || answer.isBlank() || options == null || options.isEmpty()) {
            return Match.none("nothing to choose between");
        }
        // 0. A question phrased in the negative, answered by bare yes/no options.
        //    The only case where the question's own wording changes the answer,
        //    and it has to happen before the word matcher - which would otherwise
        //    put "Yes" against "are you NOT authorised" and get it backwards.
        String effective = invertForNegatedQuestion(question, answer, options);

        // 1. The word matcher first. It handles every form that spells its
        //    options "Yes, ..." / "No, ...", which is most of them, and it is
        //    stricter than anything below.
        Optional<String> literal = OptionMatcher.match(effective, options);
        if (literal.isPresent()) {
            return Match.of(literal.get(), effective.equals(answer)
                    ? "the option says " + answer.trim()
                    : "the question is phrased in the negative, so " + answer.trim()
                            + " is answered " + effective);
        }

        Boolean wanted = asBoolean(effective);
        if (wanted == null) {
            return Match.none("'" + abbreviate(answer)
                    + "' is not one of the options and is not a yes or a no");
        }
        List<String> terms = concept == null ? List.of()
                : ASSERTS.getOrDefault(concept.id(), List.of());
        if (terms.isEmpty()) {
            return Match.none("no option says " + answer.trim()
                    + ", and this question has no known polarity to match on");
        }

        // 2. Which options talk about this question at all, and which of those
        //    assert it rather than deny it.
        List<String> inverting = concept == null ? List.of()
                : INVERTS.getOrDefault(concept.id(), List.of());
        List<String> affirming = new ArrayList<>();
        List<String> denying = new ArrayList<>();
        for (String option : options) {
            String text = normalise(option);
            if (terms.stream().noneMatch(text::contains)) {
                continue;
            }
            boolean denies = DENIES.matcher(text).find()
                    || inverting.stream().anyMatch(phrase -> containsWord(text, phrase));
            (denies ? denying : affirming).add(option);
        }

        // 3. The question's polarity is deliberately NOT applied here.
        //
        //    These options say what is true - "I am not authorized to work here"
        //    means the same thing whether the form asked "are you authorized" or
        //    "are you not authorized". Inverting against a self-describing option
        //    gets it exactly backwards, which is what the first version of this
        //    method did.
        //
        //    A negated question only changes anything when the options are bare
        //    yes/no, and that is handled before the word matcher runs - see
        //    invertForNegatedQuestion below.
        boolean want = wanted;
        String note = "";

        List<String> chosen = want ? affirming : denying;
        String side = want ? "asserts" : "denies";
        if (chosen.size() == 1) {
            return Match.of(chosen.getFirst(), "the only option that " + side + " "
                    + subjectOf(concept) + note);
        }
        if (chosen.isEmpty()) {
            return Match.none("no option " + side + " " + subjectOf(concept)
                    + ", so the answer '" + answer.trim() + "' has nowhere to go");
        }
        // Two options saying the same thing is a form this cannot read safely.
        return Match.none(chosen.size() + " options " + side + " " + subjectOf(concept)
                + ", so choosing between them would be a guess");
    }

    /**
     * Flips a yes to a no when the question is negative and the options are bare.
     *
     * <p>"Do you not require a work permit?" with options Yes and No is the one
     * shape where the question's wording decides the answer, because the options
     * carry no meaning of their own. Anywhere the options describe themselves,
     * this leaves the answer alone - "I am not authorized" is true or false
     * regardless of how the question was asked.
     *
     * <p>Narrow by construction. The pattern only fires on a negation attached to
     * the question's own verb, and if the options are anything but bare polars
     * this returns the answer untouched.
     */
    private static String invertForNegatedQuestion(String question, String answer,
            List<String> options) {

        Boolean value = asBoolean(answer);
        if (value == null || !NEGATED_QUESTION.matcher(normalise(question)).find()) {
            return answer;
        }
        boolean allBare = options.stream()
                .allMatch(option -> asBoolean(option) != null);
        if (!allBare) {
            return answer;
        }
        return value ? "No" : "Yes";
    }

    private static boolean containsWord(String haystack, String needle) {
        return (" " + haystack + " ").contains(" " + needle + " ")
                || haystack.startsWith(needle + " ") || haystack.endsWith(" " + needle);
    }

    /**
     * What a sentence asserts about a concept's proposition.
     *
     * <p>Exposed because the same question - "does this sentence say he is
     * authorised, or that he is not?" - is asked in two places. Here, to pick a
     * radio button. And by the audit, to notice that a stored rule saying "I am a
     * citizen or permanent resident of the country where I plan to work",
     * scoped to Germany, asserts the opposite of what the derivation for Germany
     * concludes.
     *
     * <p>That second use is why the check could not be a type check. The sentence
     * is a perfectly well-formed answer to a work-authorisation question - boards
     * offer that exact string as an option - and it is false for the country it
     * was filed under. Only comparing meanings catches it.
     *
     * @return true when the text asserts the proposition, false when it denies
     *         it, and empty when the text says nothing the concept recognises
     */
    public static Optional<Boolean> polarityOf(Concept concept, String text) {
        if (concept == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        Boolean plain = asBoolean(text);
        if (plain != null) {
            return Optional.of(plain);
        }
        List<String> terms = ASSERTS.getOrDefault(concept.id(), List.of());
        String normalised = normalise(text);
        if (terms.stream().noneMatch(normalised::contains)) {
            return Optional.empty();
        }
        List<String> inverting = INVERTS.getOrDefault(concept.id(), List.of());
        boolean denies = DENIES.matcher(normalised).find()
                || inverting.stream().anyMatch(phrase -> containsWord(normalised, phrase));
        return Optional.of(!denies);
    }

    /**
     * Whether an answer is a yes or a no, including as a board writes it.
     *
     * @return null when it is neither, which is not a failure: a text answer to a
     *         dropdown simply has no polarity to match on
     */
    static Boolean asBoolean(String answer) {
        String text = normalise(answer);
        if (text.equals("yes") || text.equals("y") || text.equals("true")
                || text.startsWith("yes ")) {
            return Boolean.TRUE;
        }
        if (text.equals("no") || text.equals("n") || text.equals("false")
                || text.startsWith("no ") || text.equals("none")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** "sponsorship", for a sentence explaining the choice. */
    private static String subjectOf(Concept concept) {
        List<String> terms = ASSERTS.getOrDefault(concept.id(), List.of());
        return terms.isEmpty() ? concept.id() : terms.getFirst();
    }

    /**
     * Lowercased with punctuation flattened, apostrophes removed.
     *
     * <p>Apostrophes go rather than becoming spaces, so "don't" is "dont" and
     * matches the pattern above as one word rather than as "don" and "t".
     */
    private static String normalise(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace("'", "")
                .replace("’", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static String abbreviate(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 37) + "...";
    }
}
