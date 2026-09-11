package com.anuragbhandary.jobradar.apply.resume.rewrite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Words that limit a claim, which a rewrite may not quietly drop.
 *
 * <p>The first rewrite benchmark removed "self-built" from a summary and the three
 * personal platforms read as professional work; it removed "a year" and the
 * experience read as open-ended. Neither added a single unsupported word, so no
 * check on added words could see them. This checks what was taken away.
 *
 * <h2>What is protected</h2>
 * Qualifiers that change a fact when removed, grouped by what they protect:
 * attribution (self-built, personal), employment status (unpaid, internship,
 * coursework), production status (production-style, prototype), certainty
 * (estimated, designed to), duration (a year, six months), ownership (helped,
 * contributed, as part of), scale (early-stage, pilot, internal), seniority
 * (junior, associate) and framing ("rather than the models themselves").
 * Approximation on a number is checked where the number is, in
 * {@link ResumeClaimValidator}, so a figure keeps its own hedge.
 *
 * <p>Words that add to a claim - "in production", "at scale" - are not protected:
 * dropping them makes the sentence claim less, which is always allowed.
 *
 * <p>Each qualifier is kept if the rewrite has the same word or one of a small
 * declared set of equivalents ("self-built" and "personal" say the same thing).
 * Not an open-ended synonym search: an equivalent that is not written down here
 * does not count.
 */
public final class QualifierGuard {

    private QualifierGuard() {
    }

    public enum Kind {
        ATTRIBUTION,
        EMPLOYMENT_STATUS,
        PRODUCTION_STATUS,
        CERTAINTY,
        DURATION,
        OWNERSHIP,
        SCALE,
        SENIORITY,
        FRAMING
    }

    private record Family(Kind kind, Pattern pattern) {
    }

    private static final int CI = Pattern.CASE_INSENSITIVE;

    private static Family family(Kind kind, String regex) {
        return new Family(kind, Pattern.compile(regex, CI));
    }

    private static final List<Family> FAMILIES = List.of(
            family(Kind.ATTRIBUTION, "\\bself[- ](built|directed|initiated|hosted|taught)\\b"
                    + "|\\bpersonal\\b|\\bside[- ]projects?\\b|\\bhobby\\b|\\bindependent(ly)?\\b"),
            family(Kind.EMPLOYMENT_STATUS, "\\b(unpaid|volunteer(ed|ing)?|pro bono|equity[- ]only"
                    + "|intern(ship)?|freelance|academic|coursework|capstone|thesis|hackathon)\\b"),
            family(Kind.PRODUCTION_STATUS, "\\bproduction[- ](style|like)\\b"
                    + "|\\b(prototype|proof[- ]of[- ]concept|demo|mvp|experimental|toy)\\b"),
            family(Kind.CERTAINTY, "\\b(estimated|expected to|projected|planned|aim(ed|s)? to"
                    + "|intended to|designed to)\\b"),
            family(Kind.OWNERSHIP, "\\b(helped|assisted|contributed|co-(wrote|built|designed|authored|led)"
                    + "|as part of|alongside|with (a|the|my) team)\\b"),
            family(Kind.SCALE, "\\b(early[- ]stage|pilot|internal|small|single[- ]user)\\b"),
            family(Kind.SENIORITY, "\\b(junior|associate|entry[- ]level|graduate|trainee)\\b"),
            family(Kind.FRAMING, "\\brather than\\b|\\binstead of\\b"));

    /** Declared equivalents. A word kept in any member of its set counts as kept. */
    private static final List<Set<String>> EQUIVALENTS = List.of(
            Set.of("self built", "self directed", "self initiated", "personal", "side project",
                    "side projects", "hobby", "independent", "independently"),
            Set.of("production style", "production like"),
            Set.of("prototype", "proof of concept", "demo", "mvp"),
            Set.of("helped", "assisted", "contributed"),
            Set.of("early stage", "startup"),
            Set.of("rather than", "instead of", "not", "without"));

    private static final Pattern DURATION = Pattern.compile(
            "\\b(a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\\d+)"
                    + "\\s+(years?|months?|weeks?)\\b|\\bpart[- ]time\\b", CI);

    private static final Map<String, String> NUMBER_WORDS = Map.ofEntries(
            Map.entry("a", "1"), Map.entry("an", "1"), Map.entry("one", "1"),
            Map.entry("two", "2"), Map.entry("three", "3"), Map.entry("four", "4"),
            Map.entry("five", "5"), Map.entry("six", "6"), Map.entry("seven", "7"),
            Map.entry("eight", "8"), Map.entry("nine", "9"), Map.entry("ten", "10"),
            Map.entry("eleven", "11"), Map.entry("twelve", "12"));

    /** @return what was removed, one line each; empty when every qualifier survived */
    public static List<String> lost(String source, String rewrite) {
        List<String> lost = new ArrayList<>();
        if (source == null || rewrite == null) {
            return lost;
        }
        String kept = normalise(rewrite);
        Set<String> reported = new HashSet<>();
        for (Family family : FAMILIES) {
            Matcher matcher = family.pattern().matcher(source);
            while (matcher.find()) {
                String word = normalise(matcher.group());
                if (!reported.contains(word) && !keptIn(kept, word)) {
                    reported.add(word);
                    lost.add("'" + matcher.group() + "' (" + family.kind()
                            + ") was removed, which changes what the sentence claims");
                }
            }
        }
        for (String duration : durations(source)) {
            if (!durations(rewrite).contains(duration)) {
                lost.add("the duration '" + duration.replace('|', ' ')
                        + "' (DURATION) was removed or changed");
            }
        }
        return lost;
    }

    private static boolean keptIn(String rewrite, String word) {
        if (containsPhrase(rewrite, word)) {
            return true;
        }
        for (Set<String> set : EQUIVALENTS) {
            if (set.contains(word)) {
                return set.stream().anyMatch(equivalent -> containsPhrase(rewrite, equivalent));
            }
        }
        return false;
    }

    private static boolean containsPhrase(String text, String phrase) {
        return Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b").matcher(text).find();
    }

    /** Lower case, hyphens as spaces, so "self-built" and "self built" are one word. */
    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replace('-', ' ').replaceAll("\\s+", " ").strip();
    }

    /** "1|year", "6|month" - singular, digits - so "a year" and "one year" match. */
    private static Set<String> durations(String text) {
        Set<String> out = new HashSet<>();
        Matcher matcher = DURATION.matcher(text);
        while (matcher.find()) {
            if (matcher.group(1) == null) {
                out.add("part|time");
                continue;
            }
            String count = matcher.group(1).toLowerCase(Locale.ROOT);
            String unit = matcher.group(2).toLowerCase(Locale.ROOT).replaceAll("s$", "");
            out.add(NUMBER_WORDS.getOrDefault(count, count) + "|" + unit);
        }
        return out;
    }
}
