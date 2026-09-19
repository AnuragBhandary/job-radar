package com.anuragbhandary.jobradar.filter;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a posting that needs German, which the applicant does not speak.
 *
 * <p>Two shapes. An explicit requirement ("Fluent German and business-level
 * English", "Sehr gute Deutschkenntnisse (C1)"), unless it is hedged as a plus.
 * And a posting written in German, which in the real corpus always wanted a
 * German speaker whether or not it said so. Calibrated on 2026-09-19 against the
 * 332 postings the requirement pattern matched; the one wrong match was a
 * "Nice to Have" line, which is why the text before a match is read too.
 */
final class GermanRequirement {

    private static final Pattern REQUIRED = Pattern.compile(
            "\\b(?:fluent|fluency in|business[- ]fluent|excellent|very good|good|strong|native"
                    + "|proficient|proficiency in|professional)\\s+"
                    // Only language words between: "in English and German", "written
                    // and spoken German". Anything else ("good software for German
                    // customers") is not about speaking it.
                    + "(?:(?:in|of|the|both|and|english|written|spoken|verbal|oral|level|business"
                    + "|language|professional|full|working|near-native|native)\\s+){0,4}german\\b"
                    + "|\\bgerman\\s+(?:language\\s+)?(?:skills\\s+)?(?:at\\s+)?(?:level\\s+)?(?:c1|c2|b2)\\b"
                    + "|\\bgerman\\s+(?:is\\s+)?(?:required|mandatory|a must|essential)\\b"
                    + "|(?:sehr gute|fließende|verhandlungssichere|gute)\\s+deutschkenntnisse"
                    + "|\\bdeutsch\\s*(?:\\(|-)?\\s*(?:fließend|verhandlungssicher|c1|b2)"
                    + "|fließend(?:es)?\\s+deutsch",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** A hedge near the match turns a requirement into a wish. */
    private static final Pattern HEDGE = Pattern.compile(
            "\\b(?:nice to have|nice-to-have|a plus|plus|bonus|advantage|preferred|desirable"
                    + "|beneficial|helpful|welcome|von vorteil|wünschenswert)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Set<String> GERMAN_WORDS = Set.of(
            "und", "der", "die", "das", "wir", "sie", "mit", "für", "bei", "von", "zu", "ist",
            "ein", "eine", "auf", "du", "dich", "dein", "deine", "unsere", "unser", "oder",
            "als", "auch", "nicht", "werden", "sich", "im", "den", "dem");

    private static final Set<String> ENGLISH_WORDS = Set.of(
            "and", "the", "we", "you", "with", "for", "of", "to", "is", "a", "our", "are",
            "in", "on", "your", "this");

    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private GermanRequirement() {
    }

    /** Why the posting needs German, or empty. */
    static Optional<String> find(String description) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        if (isWrittenInGerman(description)) {
            return Optional.of("posting written in German");
        }
        Matcher m = REQUIRED.matcher(description);
        while (m.find()) {
            String before = description.substring(Math.max(0, m.start() - 40), m.start());
            String after = description.substring(m.end(), Math.min(description.length(), m.end() + 60));
            // Clause-bounded: "German is a plus. Strong English required" must not
            // borrow a hedge from the next sentence, or lend one to it.
            if (HEDGE.matcher(lastClause(before)).find() || HEDGE.matcher(firstClause(after)).find()) {
                continue;
            }
            String phrase = description.substring(Math.max(0, m.start() - 20),
                    Math.min(description.length(), m.end() + 20)).replaceAll("\\s+", " ").strip();
            return Optional.of("German required: \"" + phrase + "\"");
        }
        return Optional.empty();
    }

    static boolean isWrittenInGerman(String text) {
        int german = 0;
        int english = 0;
        Matcher w = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (w.find()) {
            String word = w.group();
            if (GERMAN_WORDS.contains(word)) {
                german++;
            } else if (ENGLISH_WORDS.contains(word)) {
                english++;
            }
        }
        return german >= 20 && german > english;
    }

    /** Clause breaks include commas: "Deutsch gut, Englisch von Vorteil" hedges English. */
    private static final Pattern CLAUSE_BREAK = Pattern.compile("[.,;\\n]");

    private static String lastClause(String text) {
        Matcher m = CLAUSE_BREAK.matcher(text);
        int cut = -1;
        while (m.find()) {
            cut = m.start();
        }
        return cut < 0 ? text : text.substring(cut + 1);
    }

    private static String firstClause(String text) {
        Matcher m = CLAUSE_BREAK.matcher(text);
        return m.find() ? text.substring(0, m.start()) : text;
    }
}
