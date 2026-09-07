package com.anuragbhandary.jobradar.apply.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Makes generated prose read like a person wrote it.
 *
 * <p>Two mechanisms, and the split matters. Punctuation is <strong>rewritten</strong>,
 * because a dash is a formatting habit and removing one changes nothing about
 * what the sentence claims. Vocabulary is <strong>rejected</strong>, because the
 * tells are whole phrases and editing them out leaves the sentence around them
 * still shaped wrong.
 *
 * <h2>Dashes</h2>
 * Only the ones used as punctuation: em dash, en dash, and a hyphen with spaces
 * around it. A hyphen inside a word is left alone, and that distinction is the
 * whole reason this is not a search and replace. "event-driven", "full-time",
 * "scikit-learn" and "e-commerce" are not dashes in the sense that matters, and a
 * blanket strip turns the resume's own vocabulary into "event driven" and
 * "scikitlearn".
 *
 * <h2>Why a list of phrases rather than a better prompt</h2>
 * The prompt asks for all of this too. Models comply for a sentence and drift
 * back, and the drift is invisible without a check, because every individual
 * sentence reads fine. The list is what makes "did it actually do as it was
 * told?" answerable.
 */
public final class HumanTone {

    private HumanTone() {
    }

    /**
     * Phrases that mark text as machine-written to anyone who reads a lot of it.
     *
     * <p>Some of these are ordinary English used badly rather than wrong in
     * themselves. "Robust" is a real word; opening a paragraph with "robust,
     * scalable solutions" is a tell. In a 250-word cover letter the cost of
     * rejecting a false positive is one regeneration, and the cost of missing one
     * is an application that reads like every other application in the pile.
     */
    private static final List<String> TELLS = List.of(
            // Register that no one uses out loud.
            "delve", "tapestry", "realm of", "embark", "myriad", "plethora",
            "a testament to", "underscore", "pivotal", "paramount", "profound",
            "meticulous", "unwavering", "steadfast", "adept at", "well-versed",

            // Connective tissue that essays have and letters do not.
            "moreover", "furthermore", "in conclusion", "in summary",
            "it is worth noting", "it's worth noting", "that being said",
            "when it comes to", "in the realm of", "at the end of the day",

            // Consultancy vocabulary.
            "leverage my", "leveraging", "utilize", "utilise", "synergy",
            "holistic", "seamless", "seamlessly", "cutting-edge",
            "state-of-the-art", "best-in-class", "game-changer", "deep dive",
            "spearhead", "foster a", "unlock", "elevate", "streamline",
            "robust and scalable", "scalable solutions",

            // Cover-letter boilerplate.
            "i am excited about the opportunity", "i am thrilled",
            "excited to apply", "perfect fit", "aligns perfectly",
            "align with your", "resonates with me", "wealth of experience",
            "proven track record", "hit the ground running",
            "i believe i would be", "i am confident that i",
            "fast-paced environment", "ever-evolving", "dynamic environment",
            "passionate about", "deeply passionate",

            // The model talking about itself.
            "as an ai", "language model", "i cannot", "i don't have access");

    /**
     * The rhetorical shapes, which no single phrase catches.
     *
     * <p>"Not only X but also Y" and the rule of three are the two constructions a
     * model reaches for when asked to sound enthusiastic, and both survive a
     * vocabulary filter untouched.
     */
    private static final List<String> SHAPES = List.of(
            "not only", "but also", "whether it's", "whether it is");

    /**
     * Replaces dash punctuation, leaving hyphenated words alone.
     *
     * <p>An em dash becomes a comma rather than a full stop: the clause after one
     * is usually a continuation, and splitting it produces a fragment. Where the
     * dash already sits next to a comma the dash simply goes.
     */
    public static String removeDashes(String text) {
        if (text == null) {
            return null;
        }
        return text
                // Dash directly after existing punctuation: drop it, keep the comma.
                .replaceAll("([,;:])\\s*[\u2014\u2013]\\s*", "$1 ")
                // Spaced em or en dash, and the ASCII "--" people type for one.
                .replaceAll("\\s*[\u2014\u2013]\\s*", ", ")
                .replaceAll("\\s+--+\\s+", ", ")
                // A hyphen with spaces on both sides is a dash. Inside a word it
                // is a hyphen and is left exactly as it is.
                .replaceAll("(?<=\\S) - (?=\\S)", ", ")
                // An em dash opening a line is a bullet somebody meant as prose.
                .replaceAll("(?m)^\\s*[\u2014\u2013]\\s*", "")
                // Tidy what the substitutions can leave behind. Spaces and tabs
                // only: `\\s` includes newlines, and collapsing those destroyed
                // every paragraph break in the text - after which the structure
                // check rejected the model for writing one unbroken block that it
                // had not written. Two rounds of prompt engineering went into a
                // fault in this line.
                .replaceAll(",[ \\t]*,", ",")
                .replaceAll("[ \\t]+,", ",")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("[ \\t]+\\n", "\\n")
                .trim();
    }

    /**
     * Distinctive phrasing from {@link #letterExample()}.
     *
     * <p>The example is there because instructions alone would not produce
     * paragraph breaks. Having added it, the model copied its closing two
     * sentences verbatim, which is the failure mode of every few-shot prompt: told
     * to copy the shape, it copies the words. Twenty applications carrying the
     * same closing line is exactly the "looks generated" problem the example was
     * added to solve, so the example's own sentences are contraband.
     */
    private static final List<String> BORROWED = List.of(
            "reconcil", "two ledgers", "idempotent", "append-only event log",
            "wearing a different hat", "wearing different hats",
            "the closest thing i have", "correctness under load is the point",
            "rather than an afterthought", "happy to talk whenever suits",
            "whenever suits");

    /** Phrases lifted from the worked example. Any of them is grounds to redraft. */
    public static List<String> borrowedFromExample(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String phrase : BORROWED) {
            if (lower.contains(phrase)) {
                found.add(phrase);
            }
        }
        return found;
    }

    /** Every tell present in the text, lowercased. Empty means it reads clean. */
    public static List<String> tells(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String phrase : TELLS) {
            if (lower.contains(phrase)) {
                found.add(phrase);
            }
        }
        for (String shape : SHAPES) {
            if (lower.contains(shape)) {
                found.add(shape);
            }
        }
        return found;
    }

    /**
     * Ways a letter reads as machine-written that no phrase list can catch.
     *
     * <p>The first real draft this filter passed had no tells, no dashes and every
     * fact correct, and was still obviously generated: one unbroken block of
     * "I developed... I designed... I built... I also built...", nine sentences,
     * eight of them starting with "I", and no mention of the job being applied
     * for. Every individual sentence was fine. The shape was the problem.
     *
     * @return one complaint per problem, empty when it reads like prose
     */
    public static List<String> structureProblems(String text) {
        if (text == null || text.isBlank()) {
            return List.of("empty");
        }
        List<String> problems = new ArrayList<>();

        // "\\\\s", not "\\s". In a Java string literal \\s is the escaped-space
        // character added in Java 15, not the regex whitespace class - so it
        // compiles, runs, and quietly means "one literal space". The sentence
        // split below had the same typo and stopped splitting on newlines, which
        // made a four-sentence list read as three and slipped under the check.
        String[] paragraphs = text.trim().split("\\n\\s*\\n");
        if (paragraphs.length < 2 && text.length() > 400) {
            problems.add("it is one unbroken block; use two or three short paragraphs");
        }

        List<String> sentences = new ArrayList<>();
        for (String sentence : text.split("(?<=[.!?])\\s+")) {
            if (!sentence.isBlank()) {
                sentences.add(sentence.trim());
            }
        }
        long startingWithI = sentences.stream()
                .filter(sentence -> sentence.startsWith("I ") || sentence.startsWith("I'"))
                .count();
        // Half is generous. A letter is written in the first person, so some of
        // this is unavoidable; a list of achievements is when nearly every
        // sentence opens the same way.
        if (sentences.size() >= 4 && startingWithI * 2 > sentences.size()) {
            problems.add(startingWithI + " of " + sentences.size()
                    + " sentences begin with \"I\"; vary how they open");
        }
        return problems;
    }

    /** True if any dash punctuation survives. Used to check the rewrite worked. */
    public static boolean hasDashes(String text) {
        return text != null
                && (text.contains("\u2014") || text.contains("\u2013")
                        || text.matches("(?s).*(?<=\\S) - (?=\\S).*")
                        || text.matches("(?s).*\\s--+\\s.*"));
    }

    /**
     * The style half of a system prompt, written once and shared.
     *
     * <p>Stated as concrete instructions rather than "sound natural", which models
     * read as "be more enthusiastic" and which produces the opposite.
     */
    public static String styleRules() {
        return """
                How to write, and these matter as much as the content:
                - No dashes of any kind as punctuation. No em dashes, no en dashes,
                  no " - " between clauses. Use a comma or start a new sentence.
                  Hyphens inside words like "event-driven" are fine.
                - Short sentences. Plain British English. Say the thing directly.
                - No enthusiasm words: excited, thrilled, passionate, delighted,
                  perfect fit, hit the ground running.
                - No consultancy words: leverage, utilise, seamless, robust,
                  cutting-edge, spearhead, streamline, holistic.
                - No essay connectives: moreover, furthermore, in conclusion, it is
                  worth noting.
                - No "not only X but also Y", and no lists of exactly three
                  adjectives.
                - Do not open by naming the role and saying you are applying for
                  it. They know. Open with something specific you have built.
                - Contractions are fine and make it read like a person.
                """;
    }

    /**
     * One worked example of the shape a letter should have.
     *
     * <p>Added because instructions alone did not work. "Exactly three paragraphs
     * separated by a blank line" was stated twice, and both the first draft and
     * the draft written after being told it had failed came back as one unbroken
     * block. Format compliance is the thing models learn from an example and not
     * from a rule.
     *
     * <p>Its content is deliberately from another industry, and the prompt says so
     * twice, because the failure mode of a few-shot example is the model borrowing
     * its facts. Nothing here overlaps with anything in the resume, so a borrowed
     * sentence would be obvious in review rather than plausible.
     */
    public static String letterExample() {
        return """
                Here is the SHAPE to copy. Its content is about a different person
                in a different industry. Never reuse a fact, a sentence or a phrase
                from it, including the closing line. Copy only the rhythm, the
                length, and the blank lines between paragraphs.

                ---
                The hardest part of the payments reconciliation service was not the
                matching, it was the retries. Two ledgers disagreed about once every
                thousand transactions and the fix had to be idempotent, so I moved
                the whole thing onto an append-only event log and rebuilt state from
                it rather than mutating rows.

                Your posting mentions exactly-once delivery, which is the same
                problem wearing a different hat. That work is the closest thing I
                have to what this role is asking for.

                What I want next is a backend team where correctness under load is
                the point rather than an afterthought. Happy to talk whenever suits.
                ---
                """;
    }

    /** Feedback for a second attempt, naming what was wrong with the first. */
    public static String retryNote(List<String> tells, boolean dashes) {
        return retryNote(tells, dashes, List.of());
    }

    /** Feedback for a second attempt, naming what was wrong with the first. */
    public static String retryNote(
            List<String> tells, boolean dashes, List<String> structure) {

        StringBuilder note = new StringBuilder("Your previous draft was rejected. ");
        if (!tells.isEmpty()) {
            note.append("It used these, which must not appear: ")
                    .append(String.join(", ", tells)).append(". ");
        }
        if (dashes) {
            note.append("It used a dash as punctuation. Use a comma or a full stop. ");
        }
        for (String problem : structure) {
            note.append(problem).append(". ");
        }
        note.append("Do not reuse any wording from the example. ");
        note.append("Write it again, plainer, with the same facts.");
        return note.toString();
    }
}
