package com.anuragbhandary.jobradar.filter;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A posting that needs Dutch, or is written in it, which the applicant does not speak.
 *
 * <p>The counterpart of {@link GermanRequirement}, added on 2026-10-01 when
 * discovery brought in hundreds of Dutch employers: of 431 recommended candidates
 * in the Netherlands that day, 266 were written in Dutch and 103 more asked for it.
 * The same care applies: "Dutch is a plus" is a wish, not a requirement, so a
 * hedge in the same clause cancels the match.
 */
final class DutchRequirement {

    private static final Pattern REQUIRED = Pattern.compile(
            "\\b(?:fluent|fluency in|business[- ]fluent|excellent|very good|good|strong|native"
                    + "|proficient|proficiency in|professional)\\s+"
                    + "(?:(?:in|of|the|both|and|english|written|spoken|verbal|oral|level|business"
                    + "|language|professional|full|working|near-native|native)\\s+){0,4}dutch\\b"
                    + "|\\bdutch\\s+(?:language\\s+)?(?:skills\\s+)?(?:at\\s+)?(?:level\\s+)?(?:c1|c2|b2)\\b"
                    + "|\\bdutch\\s+(?:is\\s+)?(?:required|mandatory|a must|essential)\\b"
                    + "|\\b(?:goede|uitstekende|vloeiende)\\s+beheersing\\s+van\\s+de\\s+nederlandse\\s+taal"
                    + "|\\b(?:vloeiend|uitstekend)\\s+nederlands"
                    + "|\\bnederlands\\s*(?:\\(|-)?\\s*(?:vloeiend|c1|b2)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern HEDGE = Pattern.compile(
            "\\b(?:nice to have|nice-to-have|a plus|plus|bonus|advantage|preferred|desirable"
                    + "|beneficial|helpful|welcome|pluspunt|pré|een plus)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** Function words that are Dutch and not English or German. */
    private static final Set<String> DUTCH_WORDS = Set.of(
            "het", "een", "van", "voor", "jij", "jouw", "wij", "ons", "onze",
            "bij", "zijn", "naar", "dat", "niet", "ook", "wordt", "worden",
            "heb", "hebt", "heeft", "jullie", "wat", "zoals", "binnen");

    private static final Set<String> ENGLISH_WORDS = Set.of(
            "and", "the", "we", "you", "with", "for", "of", "to", "is", "a", "our", "are",
            "in", "on", "your", "this");

    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private DutchRequirement() {
    }

    /** Why the posting needs Dutch, or empty. */
    static Optional<String> find(String description) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        if (isWrittenInDutch(description)) {
            return Optional.of("posting written in Dutch");
        }
        Matcher m = REQUIRED.matcher(description);
        while (m.find()) {
            String before = description.substring(Math.max(0, m.start() - 40), m.start());
            String after = description.substring(m.end(), Math.min(description.length(), m.end() + 60));
            if (HEDGE.matcher(GermanRequirement.lastClause(before)).find()
                    || HEDGE.matcher(GermanRequirement.firstClause(after)).find()) {
                continue;
            }
            String phrase = description.substring(Math.max(0, m.start() - 20),
                    Math.min(description.length(), m.end() + 20)).replaceAll("\\s+", " ").strip();
            return Optional.of("Dutch required: \"" + phrase + "\"");
        }
        return Optional.empty();
    }

    static boolean isWrittenInDutch(String text) {
        int dutch = 0;
        int english = 0;
        Matcher w = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (w.find()) {
            String word = w.group();
            if (DUTCH_WORDS.contains(word)) {
                dutch++;
            } else if (ENGLISH_WORDS.contains(word)) {
                english++;
            }
        }
        return dutch >= 20 && dutch > english;
    }
}
