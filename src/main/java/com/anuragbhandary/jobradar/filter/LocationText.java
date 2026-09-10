package com.anuragbhandary.jobradar.filter;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The string matching that geography classification is built on.
 *
 * <p>Extracted from {@link GeoFilter} so that the classifier added in this phase
 * and the filter that predates it cannot drift apart. There is one definition of
 * "this place name appears in this text", and it is the careful one.
 *
 * <p>Two rules are load-bearing and both were written after a real miss:
 *
 * <ul>
 *   <li><b>Word boundaries, not substrings.</b> "us" must not fire inside
 *       "Columbus" and "uk" must not fire inside "Ukraine". This is the same bug
 *       as "ethnicity" containing "city" in the field classifier, and it fails
 *       the same way - quietly, as a posting filed under the wrong country rather
 *       than as an error.</li>
 *   <li><b>Adjacency for false friends.</b> "Dublin, Ohio" has to be caught while
 *       "Dublin, Ireland; Columbus, Ohio" is left alone, so the qualifier has to
 *       sit next to the city rather than merely appear in the same string. That
 *       is what {@link #flatten} is for.</li>
 * </ul>
 */
final class LocationText {

    private LocationText() {
    }

    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    /** Lowercased location and title together, which is where boards put countries. */
    static String haystack(String location, String title) {
        return ((location == null ? "" : location) + " " + (title == null ? "" : title))
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Punctuation to single spaces, so "Dublin, Ohio" reads as the contiguous
     * phrase "dublin ohio".
     */
    static String flatten(String haystack) {
        return haystack.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** First phrase occurring literally in an already-flattened haystack. */
    static String firstPhrase(String flattened, List<String> phrases) {
        if (phrases == null) {
            return null;
        }
        for (String phrase : phrases) {
            if (flattened.contains(phrase)) {
                return phrase;
            }
        }
        return null;
    }

    /** The first needle appearing as a whole word, or null. */
    static String firstMatch(String haystack, List<String> needles) {
        if (needles == null) {
            return null;
        }
        for (String needle : needles) {
            if (matches(haystack, needle)) {
                return needle;
            }
        }
        return null;
    }

    static boolean containsAny(String haystack, List<String> needles) {
        return firstMatch(haystack, needles) != null;
    }

    static boolean matches(String haystack, String needle) {
        return needle != null && !needle.isBlank()
                && pattern(needle).matcher(haystack).find();
    }

    private static Pattern pattern(String needle) {
        return PATTERNS.computeIfAbsent(needle.toLowerCase(Locale.ROOT), LocationText::compile);
    }

    /**
     * A word boundary is only added at an end that is actually a word character.
     *
     * <p>"u.s." ends in a full stop, and {@code \b} after a non-word character
     * requires a word character to follow it - so the token would never match at
     * the end of a string, which is exactly where a location string tends to put
     * it.
     *
     * <p>{@code UNICODE_CHARACTER_CLASS} is not decoration. {@code \b} is defined
     * against {@code \w}, which is ASCII-only by default, while the
     * {@link Character#isLetterOrDigit} test above is not - so "malmö" got a
     * trailing {@code \b} that could never match, because Java saw the "ö" as a
     * letter and the regex engine did not. Malmo, Munchen, Koln, Zurich, Malaga
     * and Sao Paulo are all spelled with one in the vocabulary, and every one of
     * them was silently unmatchable. The failure is the usual shape for this
     * codebase: a posting filed under no country rather than an error.
     */
    private static Pattern compile(String needle) {
        String prefix = Character.isLetterOrDigit(needle.charAt(0)) ? "\\b" : "";
        String suffix = Character.isLetterOrDigit(needle.charAt(needle.length() - 1)) ? "\\b" : "";
        return Pattern.compile(prefix + Pattern.quote(needle) + suffix,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                        | Pattern.UNICODE_CHARACTER_CLASS);
    }
}
