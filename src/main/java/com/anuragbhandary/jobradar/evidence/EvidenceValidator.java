package com.anuragbhandary.jobradar.evidence;

import static com.anuragbhandary.jobradar.evidence.EvidenceProblem.error;

import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.apply.resume.rewrite.EvidenceScope;
import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteRequest;
import com.anuragbhandary.jobradar.evidence.EvidenceItem.Variant;
import com.anuragbhandary.jobradar.knowledge.experience.SkillGraph;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides what in the evidence file may be used, and says why the rest may not.
 *
 * <p>The claim is taken as true - it is his sentence. Everything else is checked
 * against it:
 * <ul>
 *   <li><b>Technologies</b> are named in the claim or in the source's stack.</li>
 *   <li><b>Concepts</b> are visible in the claim, or are ideas one of the item's
 *       technologies implements. A product name is refused as a concept, because
 *       concepts are matched more loosely than technologies are.</li>
 *   <li><b>Metrics and qualifiers</b> appear in the claim as written, and every
 *       figure the claim states is declared as a metric - so no variant can drop
 *       one.</li>
 *   <li><b>Shared work</b> carries a qualifier saying which part was his.</li>
 * </ul>
 * Each variant must keep every metric and qualifier, may emphasise only what the
 * item lists, and must pass {@link ResumeClaimValidator} against the claim - the same
 * checks the rewrite benchmark applied to model output: no new technology, product,
 * number or responsibility, no lost qualifier or hedge, no tacked-on keywords, and
 * still recognisably the same accomplishment.
 *
 * <p>An item with an error is left out of the bank; a variant with an error is left
 * out of its item. Nothing is repaired: a repaired claim is a claim nobody approved.
 */
public final class EvidenceValidator {

    private EvidenceValidator() {
    }

    static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern CATEGORY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    /** A figure: "12", "3,000", "3.5", "4" in "4x" - not the 3 in "S3". */
    private static final Pattern FIGURE =
            Pattern.compile("(?<![A-Za-z0-9.,])\\d[\\d,]*(?:\\.\\d+)?(?=[xX]?(?![A-Za-z0-9]))");

    private static final int CI = Pattern.CASE_INSENSITIVE;

    /**
     * Concepts that are a fair name for wording the claim uses, when the concept's
     * own words are not in it. Written down, like {@link SkillGraph}: an equivalence
     * that is not listed here does not count.
     */
    private static final Map<String, Pattern> CONCEPT_WORDING = Map.ofEntries(
            Map.entry("performance optimisation", performance()),
            Map.entry("performance optimization", performance()),
            Map.entry("performance", performance()),
            Map.entry("reliability", Pattern.compile(
                    "\\bretr(y|ies|ied)\\b|\\bfailure\\w*|\\bfault\\w*|\\breliab\\w*|\\berror handling\\b", CI)),
            Map.entry("fault tolerance", Pattern.compile(
                    "\\bretr(y|ies|ied)\\b|\\bfailure[- ](recovery|handling)\\b|\\bfault\\w*", CI)),
            Map.entry("event-driven", Pattern.compile("\\bevents?\\b|\\bkafka\\b|\\bmessag\\w*", CI)),
            Map.entry("real-time systems", Pattern.compile("\\breal[- ]time\\b", CI)),
            Map.entry("asynchronous processing", Pattern.compile(
                    "\\basynchronous\\w*|\\basync\\b|\\bbackground workers?\\b", CI)),
            Map.entry("api design", Pattern.compile("\\bapis?\\b", CI)),
            Map.entry("llm integration", Pattern.compile("\\bllms?\\b|\\bgemini\\b|\\bgroq\\b|\\bopenai\\b", CI)),
            Map.entry("ai integration", Pattern.compile("\\bai\\b|\\bai/ml\\b|\\bllms?\\b|\\bml\\b", CI)),
            Map.entry("media processing", Pattern.compile("\\bffmpeg\\b|\\baudio\\b|\\bvideo\\b", CI)),
            Map.entry("testing", Pattern.compile("\\btest\\w*|\\bjunit\\b|\\bmockito\\b|\\bpytest\\b", CI)),
            Map.entry("data pipeline", Pattern.compile("\\bpipelines?\\b", CI)));

    private static Pattern performance() {
        return Pattern.compile("\\bfaster\\b|\\breduc\\w*\\b[^.]{0,40}\\btime\\b|\\blatency\\b"
                + "|\\bspeed(ed)?[- ]up\\b|\\b\\d+(\\.\\d+)?x\\b|\\b\\w+fold\\b", CI);
    }

    public record Result(List<EvidenceSource> sources, List<EvidenceItem> items,
            List<EvidenceProblem> problems) {
    }

    public static Result validate(List<EvidenceSource> sources, List<EvidenceItem> items) {
        List<EvidenceProblem> problems = new ArrayList<>();
        Map<String, EvidenceSource> valid = new LinkedHashMap<>();
        for (EvidenceSource source : sources == null ? List.<EvidenceSource>of() : sources) {
            if (source == null) {
                continue;
            }
            String where = source.id() == null || source.id().isBlank() ? "source" : source.id();
            if (source.id() == null || !VALID_ID.matcher(source.id()).matches()) {
                problems.add(error(where, "a source needs an id of letters, digits, '.', '_' or '-'"));
            } else if (valid.containsKey(source.id())) {
                problems.add(error(where, "source id used twice"));
            } else if (source.kind() == null) {
                problems.add(error(where, "a source needs a kind: employment or project"));
            } else if (source.name() == null || source.name().isBlank()) {
                problems.add(error(where, "a source needs a name, written as the resume writes it"));
            } else {
                valid.put(source.id(), source);
            }
        }

        List<EvidenceItem> kept = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (EvidenceItem item : items == null ? List.<EvidenceItem>of() : items) {
            if (item == null) {
                continue;
            }
            String where = item.id() == null || item.id().isBlank() ? "item" : item.id();
            List<String> errors = new ArrayList<>();
            if (item.id() == null || !VALID_ID.matcher(item.id()).matches()) {
                errors.add("needs an id of letters, digits, '.', '_' or '-', at most 64 characters");
            } else if (!ids.add(item.id())) {
                errors.add("id used twice - every id must name one piece of evidence");
            }
            EvidenceSource source = item.source() == null ? null : valid.get(item.source());
            if (source == null) {
                errors.add("source '" + item.source() + "' is not a valid source in this file");
            }
            if (item.claim() == null || item.claim().isBlank()) {
                errors.add("has no claim");
            } else {
                errors.addAll(itemErrors(item, source));
            }
            if (!errors.isEmpty()) {
                errors.forEach(e -> problems.add(error(where, e)));
                continue;
            }
            List<Variant> variants = new ArrayList<>();
            Set<String> variantIds = new HashSet<>();
            for (Variant variant : item.variants()) {
                String at = where + "/" + (variant.id() == null ? "variant" : variant.id());
                List<String> variantErrors = variantErrors(item, source, variant, variantIds);
                if (variantErrors.isEmpty()) {
                    variants.add(variant);
                } else {
                    variantErrors.forEach(e -> problems.add(error(at, e)));
                }
            }
            kept.add(withVariants(item, variants));
        }
        return new Result(List.copyOf(valid.values()), List.copyOf(kept), List.copyOf(problems));
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    static List<String> itemErrors(EvidenceItem item, EvidenceSource source) {
        List<String> errors = new ArrayList<>();
        String claim = item.claim();
        for (String technology : item.technologies()) {
            if (!namedIn(claim, technology) && !inStack(technology, source)) {
                errors.add("technology '" + technology + "' is named neither in the claim nor in the "
                        + "stack of source '" + item.source() + "'");
            }
        }
        for (String name : item.contextOnly()) {
            if (!namedIn(claim, name)) {
                errors.add("context-only '" + name + "' is not named in the claim");
            }
            if (item.technologyKeys().contains(EvidenceItem.key(name))) {
                errors.add("'" + name + "' is listed both as used and as context only");
            }
        }
        for (String metric : item.metrics()) {
            if (!EvidenceText.containsPhrase(claim, metric)) {
                errors.add("metric '" + metric + "' is not in the claim as written");
            }
        }
        for (String qualifier : item.qualifiers()) {
            if (!EvidenceText.containsPhrase(claim, qualifier)) {
                errors.add("qualifier '" + qualifier + "' is not in the claim as written");
            }
        }
        for (String figure : figures(claim)) {
            if (item.metrics().stream().noneMatch(m -> EvidenceText.containsFigure(m, figure))) {
                errors.add("the claim states '" + figure + "' and no metric declares it; declare it, "
                        + "so that every wording has to keep it");
            }
        }
        if (item.attribution() == EvidenceItem.Attribution.SHARED && item.qualifiers().isEmpty()) {
            errors.add("attribution is shared, so a qualifier must say which part was his");
        }
        for (String concept : item.concepts()) {
            if (EvidenceText.isProductName(concept)) {
                errors.add("concept '" + concept + "' is a technology; list it under technologies, "
                        + "where it is checked");
            } else if (!conceptGrounded(concept, item)) {
                errors.add("concept '" + concept + "' is not visible in the claim and is not an idea "
                        + "any of its technologies implements");
            }
        }
        for (String category : item.categories()) {
            if (!CATEGORY.matcher(category).matches()) {
                errors.add("category '" + category + "' must be lower-case words joined by hyphens");
            }
        }
        return errors;
    }

    private static boolean namedIn(String claim, String technology) {
        return EvidenceText.wholeWord(claim, technology)
                || EvidenceText.technologiesIn(claim).contains(EvidenceItem.key(technology));
    }

    private static boolean inStack(String technology, EvidenceSource source) {
        if (source == null) {
            return false;
        }
        String key = EvidenceItem.key(technology);
        return source.stack().stream().anyMatch(s -> EvidenceItem.key(s).equals(key));
    }

    static boolean conceptGrounded(String concept, EvidenceItem item) {
        String key = concept.strip().toLowerCase(Locale.ROOT);
        Set<String> words = EvidenceText.stems(concept);
        if (!words.isEmpty() && EvidenceText.stems(item.claim()).containsAll(words)) {
            return true;
        }
        Pattern wording = CONCEPT_WORDING.get(key);
        if (wording != null && wording.matcher(item.claim()).find()) {
            return true;
        }
        for (PostingRequirements.Phrase phrase : PostingRequirements.PHRASES) {
            if (phrase.term().equalsIgnoreCase(key) && phrase.foundIn(item.claim())) {
                return true;
            }
        }
        for (String technology : item.technologies()) {
            if (SkillGraph.concepts(EvidenceItem.key(technology)).contains(key)) {
                return true;
            }
        }
        return false;
    }

    static List<String> figures(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = FIGURE.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String figure = matcher.group().replaceAll("[,.]+$", "");
            if (!figure.isEmpty()) {
                out.add(figure);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Variants
    // ------------------------------------------------------------------

    static List<String> variantErrors(EvidenceItem item, EvidenceSource source, Variant variant,
            Set<String> seen) {
        List<String> errors = new ArrayList<>();
        if (variant.id() == null || !VALID_ID.matcher(variant.id()).matches()
                || EvidenceItem.CLAIM.equals(variant.id())) {
            errors.add("needs an id of letters, digits, '.', '_' or '-', other than 'claim'");
        } else if (!seen.add(variant.id())) {
            errors.add("variant id used twice in this item");
        }
        if (variant.text() == null || variant.text().isBlank()) {
            errors.add("has no text");
            return errors;
        }
        if (EvidenceText.normalise(variant.text()).equals(EvidenceText.normalise(item.claim()))) {
            errors.add("is the claim itself");
        }
        for (String metric : item.metrics()) {
            if (!EvidenceText.containsPhrase(variant.text(), metric)) {
                errors.add("drops or changes the metric '" + metric + "'");
            }
        }
        for (String qualifier : item.qualifiers()) {
            if (!EvidenceText.containsPhrase(variant.text(), qualifier)) {
                errors.add("drops the qualifier '" + qualifier + "'");
            }
        }
        for (String emphasis : variant.emphasis()) {
            if (!item.technologyKeys().contains(EvidenceItem.key(emphasis))
                    && !item.conceptKeys().contains(emphasis.strip().toLowerCase(Locale.ROOT))) {
                errors.add("emphasises '" + emphasis + "', which is not one of this item's "
                        + "technologies or concepts");
            }
        }
        errors.addAll(claimCheck(item, source, variant.text()));
        return errors;
    }

    /**
     * The rewrite benchmark's validator, applied to a variant against its claim.
     *
     * <p>The item's technologies and concepts are its evidence: a variant may name
     * those, and nothing the claim does not already support.
     */
    static List<String> claimCheck(EvidenceItem item, EvidenceSource source, String text) {
        Set<String> terms = new LinkedHashSet<>(item.technologyKeys());
        terms.addAll(item.conceptKeys());
        StringBuilder support = new StringBuilder(item.claim());
        if (source != null) {
            support.append('\n').append(source.name());
        }
        item.technologies().forEach(t -> support.append('\n').append(t));
        item.concepts().forEach(c -> support.append('\n').append(c));
        EvidenceScope scope = new EvidenceScope(item.id(), item.claim(), support.toString(),
                terms, Map.of());
        RewriteRequest request = new RewriteRequest(item.id(), item.claim(), scope,
                List.of(), List.of(), List.of(), Math.max(200, item.claim().length() * 2));
        return ResumeClaimValidator.check(text, request, Set.of(item.id())).issues().stream()
                .map(issue -> issue.type() + ": " + issue.detail())
                .toList();
    }

    private static EvidenceItem withVariants(EvidenceItem item, List<Variant> variants) {
        return new EvidenceItem(item.id(), item.source(), item.claim(), item.technologies(),
                item.concepts(), item.metrics(), item.qualifiers(), item.attribution(),
                item.categories(), item.strength(), item.contextOnly(), List.copyOf(variants));
    }
}
