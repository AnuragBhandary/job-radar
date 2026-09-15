package com.anuragbhandary.jobradar.evidence;

import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether generated career prose - a cover letter, an application answer - rests on
 * the evidence it was given.
 *
 * <p>Conservative on purpose, and not a truth detector. It checks what can be
 * checked mechanically and rejects anything it cannot ground; a rejected letter
 * becomes the deterministic template and a rejected answer stays with the
 * applicant. A false rejection costs a better paragraph. A false acceptance puts a
 * claim on a real application that its author never made.
 *
 * <h2>What is checked</h2>
 * <ol>
 *   <li><b>Technologies</b> - each one named is listed by, or written in, the evidence
 *       supplied. One that appears only in the job description is not his.</li>
 *   <li><b>Requirements he cannot claim</b> - Kubernetes when his evidence is Docker -
 *       are not presented as his.</li>
 *   <li><b>Figures</b> - every number is one the evidence states, and a figure the
 *       evidence hedges ("approximately") keeps its hedge.</li>
 *   <li><b>Qualifiers</b> - an item used whose claim is scoped ("the audio side of")
 *       keeps the scoping.</li>
 *   <li><b>Inflation</b> - years of experience, "expert", "extensive".</li>
 *   <li><b>Responsibility</b> - led, owned, architected, managed... only where the
 *       evidence says so.</li>
 *   <li><b>Names</b> - every capitalised name is in the evidence, or is the company
 *       or role applied to. This is what catches an invented employer, project or
 *       third-party fact.</li>
 * </ol>
 */
public final class GroundedProse {

    private GroundedProse() {
    }

    /**
     * What a piece of prose may rest on.
     *
     * @param items          the evidence supplied to the writer
     * @param allowedNames   other names it may use: the company and role applied to,
     *                       the evidence sources' names, the subject of the question
     * @param forbiddenTerms requirements he may not present as his
     */
    public record Grounding(List<EvidenceItem> items, List<String> allowedNames,
            List<String> forbiddenTerms) {

        public Grounding {
            items = List.copyOf(items);
            allowedNames = allowedNames.stream().filter(n -> n != null && !n.isBlank()).toList();
            forbiddenTerms = forbiddenTerms.stream().filter(n -> n != null && !n.isBlank()).toList();
        }

        public List<String> ids() {
            return items.stream().map(EvidenceItem::id).toList();
        }

        /** Grounding on a context's items: their sources' names, the role, and the company. */
        public static Grounding of(List<ApplicationEvidenceContext.EvidenceUse> uses,
                List<String> names, List<String> forbidden) {
            List<String> allowed = new ArrayList<>(names);
            uses.forEach(use -> {
                if (use.source() != null) {
                    allowed.add(use.source().name());
                }
            });
            return new Grounding(uses.stream().map(ApplicationEvidenceContext.EvidenceUse::item).toList(),
                    allowed, forbidden);
        }
    }

    private static final int CI = Pattern.CASE_INSENSITIVE;

    private static final Set<String> GENERIC_CAPS = Set.of("I", "AI", "ML", "API", "APIs", "REST",
            "RESTful", "HTTP", "HTTPS", "JSON", "SQL", "CRUD", "UI", "UX", "IO", "CPU", "GPU", "OK");

    private static final Pattern TOKEN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+#/]*[A-Za-z0-9+#]|[A-Za-z0-9]");
    private static final Pattern NUMERIC = Pattern.compile("\\d[\\d,.]*[xX%]?");

    private static final Pattern NUMBER_WORDS = Pattern.compile(
            "\\b(two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|twenty|thirty|forty"
                    + "|fifty|hundred|thousand|dozen|twice|double|triple"
                    + "|(?:two|three|four|five|six|seven|eight|nine|ten)fold)\\b", CI);

    private static final Pattern HEDGE_WORD = Pattern.compile(
            "\\b(approximately|approx\\.?|roughly|about|around|nearly|almost|over|more than|up to"
                    + "|some)\\b|~|≈", CI);

    private static final Pattern HEDGE_BEFORE = Pattern.compile(
            "(approximately|approx\\.?|roughly|about|around|nearly|almost|over|more than|up to"
                    + "|some|~|≈)[\\s-]*$", CI);

    private static final List<Pattern> INFLATION = List.of(
            Pattern.compile("\\b\\d+\\+?\\s*(years?|yrs?)\\b", CI),
            Pattern.compile("\\b(one|two|three|four|five|six|seven|eight|nine|ten|several|many"
                    + "|numerous|couple of)\\s+years\\b", CI),
            Pattern.compile("\\byears of\\b", CI),
            Pattern.compile("\\bexpert(ise)?\\b", CI),
            Pattern.compile("\\bextensive(ly)?\\b", CI),
            Pattern.compile("\\bseasoned\\b", CI),
            Pattern.compile("\\badvanced (knowledge|experience|proficiency)\\b", CI),
            Pattern.compile("\\bdeep (expertise|knowledge)\\b", CI),
            Pattern.compile("\\b(mastery|mastered)\\b", CI),
            Pattern.compile("\\bspeciali[sz](t|ed)\\b", CI));

    /** A responsibility word, and the wording in the evidence that licenses it. */
    private static final Map<Pattern, Pattern> RESPONSIBILITY = Map.of(
            Pattern.compile("\\b(led|leading)\\b(?!\\s+to\\b)", CI), Pattern.compile("\\b(led|lead|leading)\\b", CI),
            Pattern.compile("\\b(owned|ownership|owning)\\b", CI), Pattern.compile("\\bown", CI),
            Pattern.compile("\\barchitect(ed|ing)\\b", CI), Pattern.compile("\\barchitect", CI),
            Pattern.compile("\\bmanag(ed|ing)\\b(?!\\s+(service|services|database|instance|cluster))", CI),
            Pattern.compile("\\bmanag", CI),
            Pattern.compile("\\b(spearhead\\w*|headed|directed|oversaw|supervised)\\b", CI),
            Pattern.compile("\\b(spearhead|headed|direct|oversaw|supervis)", CI),
            Pattern.compile("\\bmentor(ed|ing)\\b", CI), Pattern.compile("\\bmentor", CI),
            Pattern.compile("\\b(single[- ]handedly|sole)\\b", CI), Pattern.compile("\\b(single|sole)", CI));

    /** Every reason the prose is not grounded; empty when it is. */
    public static List<String> problems(String text, Grounding grounding) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            out.add("it is empty");
            return out;
        }
        String evidence = evidenceText(grounding);
        String allowed = evidence + "\n" + String.join("\n", grounding.allowedNames());
        Set<String> technologies = new HashSet<>();
        grounding.items().forEach(item -> technologies.addAll(item.technologyKeys()));
        grounding.allowedNames().forEach(name -> technologies.addAll(EvidenceText.technologiesIn(name)));
        grounding.allowedNames().forEach(name -> technologies.add(EvidenceItem.key(name)));

        technologies(text, technologies, allowed, out);
        forbidden(text, grounding, technologies, evidence, out);
        figures(text, allowed, out);
        hedges(text, grounding, out);
        qualifiers(text, grounding, out);
        for (Pattern pattern : INFLATION) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                out.add("it claims '" + matcher.group() + "', which no evidence supports");
            }
        }
        RESPONSIBILITY.forEach((word, licence) -> {
            Matcher matcher = word.matcher(text);
            if (matcher.find() && !licence.matcher(evidence).find()) {
                out.add("it says '" + matcher.group() + "', and the evidence makes no such claim");
            }
        });
        names(text, allowed, out);
        return List.copyOf(new LinkedHashSet<>(out));
    }

    private static void technologies(String text, Set<String> technologies, String allowed,
            List<String> out) {
        for (String term : EvidenceText.technologiesNamed(text)) {
            if (PostingRequirements.AMBIGUOUS.contains(term) && !capitalised(text, term)) {
                continue;
            }
            if (technologies.contains(term) || EvidenceText.wholeWord(allowed, term)) {
                continue;
            }
            out.add("it names " + term + ", which the evidence it was given does not show");
        }
    }

    private static void forbidden(String text, Grounding grounding, Set<String> technologies,
            String evidence, List<String> out) {
        for (String term : grounding.forbiddenTerms()) {
            if (EvidenceText.containsPhrase(text, term) && !EvidenceText.containsPhrase(evidence, term)
                    && !technologies.contains(EvidenceItem.key(term))) {
                out.add("it presents " + term + " as his experience, which the evidence does not support");
            }
        }
    }

    private static void figures(String text, String allowed, List<String> out) {
        for (String figure : EvidenceValidator.figures(text)) {
            if (!EvidenceText.containsFigure(allowed, figure)) {
                out.add("it states '" + figure + "', which no evidence it was given states");
            }
        }
        Matcher words = NUMBER_WORDS.matcher(text);
        while (words.find()) {
            if (!EvidenceText.containsPhrase(allowed, words.group())) {
                out.add("it states '" + words.group() + "', which no evidence it was given states");
            }
        }
    }

    /** A figure the evidence gives as an approximation keeps the approximation. */
    private static void hedges(String text, Grounding grounding, List<String> out) {
        for (EvidenceItem item : grounding.items()) {
            for (String metric : item.metrics()) {
                if (!HEDGE_WORD.matcher(metric).find()) {
                    continue;
                }
                List<String> figures = new ArrayList<>(EvidenceValidator.figures(metric));
                Matcher words = NUMBER_WORDS.matcher(metric);
                while (words.find()) {
                    figures.add(words.group());
                }
                for (String figure : figures) {
                    Matcher at = Pattern.compile("(?<![0-9A-Za-z,.])" + Pattern.quote(figure)
                            + "(?![0-9]|[,.][0-9])", CI).matcher(text);
                    while (at.find()) {
                        String before = text.substring(Math.max(0, at.start() - 25), at.start());
                        if (!HEDGE_BEFORE.matcher(before).find()) {
                            out.add("it drops the hedge on '" + metric + "'");
                        }
                    }
                }
            }
        }
    }

    /**
     * An item counts as used when the prose repeats one of its figures or names a
     * technology no other supplied item has. A used item keeps its qualifiers.
     */
    private static void qualifiers(String text, Grounding grounding, List<String> out) {
        for (EvidenceItem item : grounding.items()) {
            if (item.qualifiers().isEmpty()) {
                continue;
            }
            Set<String> unique = new LinkedHashSet<>(item.technologyKeys());
            grounding.items().stream().filter(other -> other != item)
                    .forEach(other -> unique.removeAll(other.technologyKeys()));
            boolean used = item.metrics().stream().anyMatch(m -> EvidenceText.containsPhrase(text, m))
                    || item.technologies().stream().anyMatch(t -> unique.contains(EvidenceItem.key(t))
                            && EvidenceText.wholeWord(text, t));
            if (!used) {
                continue;
            }
            for (String qualifier : item.qualifiers()) {
                if (!EvidenceText.containsPhrase(text, qualifier)) {
                    out.add("it uses " + item.id() + " without '" + qualifier + "'");
                }
            }
        }
    }

    private static void names(String text, String allowed, List<String> out) {
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (NUMERIC.matcher(token).matches() || GENERIC_CAPS.contains(token)) {
                continue;
            }
            boolean capital = Character.isUpperCase(token.charAt(0));
            boolean digit = token.chars().anyMatch(Character::isDigit);
            boolean innerUpper = token.length() > 1
                    && token.substring(1).chars().anyMatch(Character::isUpperCase);
            if (!capital && !digit && !innerUpper) {
                continue;
            }
            boolean allCaps = token.length() > 1 && token.equals(token.toUpperCase(Locale.ROOT));
            if (sentenceStart(text, matcher.start()) && !digit && !innerUpper && !allCaps) {
                continue;
            }
            if (EvidenceText.wholeWord(allowed, token)
                    || (token.endsWith("s") && token.length() > 2
                            && EvidenceText.wholeWord(allowed, token.substring(0, token.length() - 1)))) {
                continue;
            }
            out.add("it names " + token + ", which is not in his evidence");
        }
    }

    private static boolean sentenceStart(String text, int index) {
        int i = index - 1;
        while (i >= 0 && text.charAt(i) == ' ') {
            i--;
        }
        return i < 0 || ".!?:;(\"'“\n-*•".indexOf(text.charAt(i)) >= 0;
    }

    private static boolean capitalised(String text, String term) {
        Matcher matcher = Pattern.compile("(?<![\\w+#.])" + Pattern.quote(term) + "(?![\\w+#])", CI)
                .matcher(text);
        while (matcher.find()) {
            if (Character.isUpperCase(text.charAt(matcher.start()))) {
                return true;
            }
        }
        return false;
    }

    /** Everything the evidence says, as one text: every approved wording and every field. */
    static String evidenceText(Grounding grounding) {
        StringBuilder all = new StringBuilder();
        for (EvidenceItem item : grounding.items()) {
            item.approvedTexts().forEach(t -> all.append(t).append('\n'));
            item.technologies().forEach(t -> all.append(t).append('\n'));
            item.concepts().forEach(c -> all.append(c).append('\n'));
            item.metrics().forEach(m -> all.append(m).append('\n'));
            item.qualifiers().forEach(q -> all.append(q).append('\n'));
        }
        return all.toString();
    }
}
