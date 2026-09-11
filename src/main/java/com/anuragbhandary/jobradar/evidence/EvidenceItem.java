package com.anuragbhandary.jobradar.evidence;

import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One true thing he did, with everything a later step needs to use it honestly.
 *
 * <p>The claim is the sentence he wrote and approved. It is the default
 * presentation and the ground truth: every other field is checked against it
 * ({@link EvidenceValidator}), and every variant is checked against it with the
 * same validator that judged model rewrites in the rewrite benchmark.
 *
 * @param source       the id of the job or project it belongs to
 * @param claim        the approved sentence. Printed as written unless an approved
 *                     variant fits the posting better.
 * @param technologies what this work used. Each must be named in the claim or be in
 *                     the source's stack - the tags on a bullet are how the resume
 *                     already said so. Anything listed here may be named in a
 *                     variant; anything not listed may not.
 * @param concepts     ideas the work demonstrates - "deduplication", "event replay".
 *                     Each must be visible in the claim, or be an idea one of the
 *                     technologies implements. Product names are refused here; they
 *                     belong in technologies, where they are checked.
 * @param metrics      figures exactly as the claim states them, hedge included
 *                     ("approximately 1,200 messages"). Every variant must keep them.
 * @param qualifiers   words that limit the claim ("the audio side of"). Every
 *                     variant must keep them.
 * @param attribution  whose work it describes. SHARED needs a qualifier saying which
 *                     part was his.
 * @param categories   kinds of role this evidence speaks to - "backend",
 *                     "distributed-systems". Used for ranking only.
 * @param strength     how strong the evidence is. The convention used to populate
 *                     the bank: HIGH when the claim states a measured result.
 * @param contextOnly  technologies the claim names that he did not use - "React
 *                     clients" on a bullet about the server. They may be printed
 *                     (the claim prints them) and are never matched as evidence.
 * @param variants     other approved wordings of the same claim
 */
public record EvidenceItem(
        String id,
        String source,
        String claim,
        List<String> technologies,
        List<String> concepts,
        List<String> metrics,
        List<String> qualifiers,
        Attribution attribution,
        List<String> categories,
        Strength strength,
        @JsonProperty("context-only") List<String> contextOnly,
        List<Variant> variants) {

    public enum Attribution {
        /** The sentence describes his own work. */
        INDIVIDUAL,
        /** His part of something larger. A qualifier says which part, and every wording keeps it. */
        SHARED
    }

    public enum Strength {
        HIGH(1.0),
        MEDIUM(0.85),
        LOW(0.7);

        private final double weight;

        Strength(double weight) {
            this.weight = weight;
        }

        public double weight() {
            return weight;
        }
    }

    /**
     * Another wording of the same claim.
     *
     * @param emphasis the technologies or concepts this wording puts first. The
     *                 planner picks the variant whose emphasis matches what the
     *                 posting asks for, and the claim when none does.
     * @param approved false unless written as true. An unapproved variant is
     *                 validated and shown, and never printed on a resume.
     */
    public record Variant(String id, String text, List<String> emphasis, boolean approved) {

        public Variant {
            emphasis = clean(emphasis);
        }
    }

    /** The id the claim itself is known by when a plan says which wording it chose. */
    public static final String CLAIM = "claim";

    public EvidenceItem {
        technologies = clean(technologies);
        concepts = clean(concepts);
        metrics = clean(metrics);
        qualifiers = clean(qualifiers);
        categories = clean(categories);
        contextOnly = clean(contextOnly);
        variants = variants == null ? List.of()
                : variants.stream().filter(v -> v != null).toList();
        attribution = attribution == null ? Attribution.INDIVIDUAL : attribution;
        strength = strength == null ? Strength.MEDIUM : strength;
    }

    /** The approved variants, in the order written. */
    public List<Variant> approvedVariants() {
        return variants.stream().filter(Variant::approved).toList();
    }

    /** Everything that may be printed for this item: the claim, then each approved variant. */
    public List<String> approvedTexts() {
        List<String> texts = new ArrayList<>();
        texts.add(claim);
        approvedVariants().forEach(v -> texts.add(v.text()));
        return texts;
    }

    /** Technologies as matching keys: lower case, aliases folded ("Postgres" is "postgresql"). */
    public Set<String> technologyKeys() {
        Set<String> keys = new LinkedHashSet<>();
        technologies.forEach(t -> keys.add(key(t)));
        return keys;
    }

    public Set<String> conceptKeys() {
        Set<String> keys = new LinkedHashSet<>();
        concepts.forEach(c -> keys.add(c.strip().toLowerCase(Locale.ROOT)));
        return keys;
    }

    /** The key a technology or requirement name is matched on. */
    public static String key(String name) {
        return name == null ? ""
                : PostingRequirements.canonicalSubject(name).strip().toLowerCase(Locale.ROOT);
    }

    /** Nulls and blanks dropped, the rest stripped. YAML turns a stray "- " into a null. */
    static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::strip)
                .toList();
    }
}
