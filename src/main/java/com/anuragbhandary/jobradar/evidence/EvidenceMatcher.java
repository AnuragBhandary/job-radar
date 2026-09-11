package com.anuragbhandary.jobradar.evidence;

import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.evidence.EvidenceMatch.Kind;
import com.anuragbhandary.jobradar.knowledge.experience.SkillGraph;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Whether one evidence item speaks to one requirement.
 *
 * <p>Deterministic and closed. Every way of matching is a lookup against something
 * written down - the item's own lists, the curated phrases in
 * {@link PostingRequirements}, the ideas {@link SkillGraph} says a technology
 * implements - so a match can always be explained in one line and checked.
 *
 * <h2>The rule for products</h2>
 * A requirement naming a product - a language, framework, database, cloud service,
 * alone or inside a longer term like "Kafka Streams" - matches only an item that
 * lists that product. Never through a concept: an item that "shows streaming" is not evidence of
 * Kafka, and ranking it as such is how a resume ends up implying a tool its owner has
 * not used. Architecture ideas ("event-driven", "microservices") are not products
 * and may match through concepts.
 */
public final class EvidenceMatcher {

    private EvidenceMatcher() {
    }

    /** Added once when the claim states a measured result. Small enough never to cross a kind. */
    static final double METRIC_BONUS = 0.02;

    /** The best way this item matches this requirement, if it does at all. */
    public static Optional<EvidenceMatch> match(EvidenceItem item, EvidenceSource source,
            String requirement) {
        if (item == null || requirement == null || requirement.isBlank()) {
            return Optional.empty();
        }
        String literal = requirement.strip().toLowerCase(Locale.ROOT);
        String subject = EvidenceItem.key(requirement);
        // A product named anywhere in the requirement makes it a product
        // requirement: "Kafka Streams" must not be answered by an item that merely
        // "shows streaming".
        boolean product = EvidenceText.isProductName(subject) || EvidenceText.isProductName(literal)
                || EvidenceText.technologiesIn(requirement).stream().anyMatch(EvidenceText::isProductName);
        Set<String> requirementStems = EvidenceText.stems(requirement);
        Set<String> specific = new LinkedHashSet<>(requirementStems);
        specific.removeAll(EvidenceText.GENERIC);
        Set<String> technologies = item.technologyKeys();

        Candidate best = null;
        if (technologies.contains(subject) || technologies.contains(literal)) {
            best = better(best, new Candidate(Kind.TECHNOLOGY, nameOf(item, subject),
                    "names " + nameOf(item, subject)));
        } else {
            for (String term : EvidenceText.technologiesIn(requirement)) {
                if (technologies.contains(term)) {
                    best = better(best, new Candidate(Kind.CONTAINED, nameOf(item, term),
                            "names " + nameOf(item, term) + ", which \"" + requirement.strip()
                                    + "\" contains"));
                    break;
                }
            }
        }
        if (!product) {
            // Every concept and idea is tried, not just the first to match: a general
            // one ("backend engineering") must not hide a specific one found later.
            for (String concept : item.concepts()) {
                if (conceptMatches(concept, subject, literal, requirementStems, specific)) {
                    best = better(best, new Candidate(general(concept) ? Kind.GENERAL : Kind.CONCEPT,
                            concept, "shows " + concept));
                }
            }
            Optional<PostingRequirements.Phrase> phrase = PostingRequirements.phraseFor(requirement);
            if (phrase.isPresent() && phrase.get().foundIn(item.claim())) {
                String term = phrase.get().term();
                best = better(best, new Candidate(general(term) ? Kind.GENERAL : Kind.CONCEPT,
                        term, "the claim says it (" + term + ")"));
            }
            for (String technology : item.technologies()) {
                for (String idea : SkillGraph.concepts(EvidenceItem.key(technology))) {
                    if (ideaMatches(idea, subject, literal, requirementStems)) {
                        best = better(best, new Candidate(general(idea) ? Kind.GENERAL : Kind.IMPLEMENTS,
                                technology, technology + " implements " + idea));
                    }
                }
            }
            for (String category : item.categories()) {
                if (conceptMatches(category.replace('-', ' '), subject, literal,
                        requirementStems, specific)) {
                    best = better(best, new Candidate(Kind.CATEGORY, category,
                            "is " + category + " work"));
                    break;
                }
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        boolean claimable = best.kind() == Kind.TECHNOLOGY || best.kind() == Kind.CONCEPT
                || best.kind() == Kind.IMPLEMENTS || best.kind() == Kind.GENERAL;
        return Optional.of(new EvidenceMatch(item, source, best.kind(), requirement.strip(),
                best.matched(), score(item, source, best.kind()), best.reason(), claimable));
    }

    /**
     * Ranking weight. The kind of match always decides first: a claim that says
     * "asynchronously" outranks one that only uses a technology implementing
     * asynchronous processing, however strong the second item is. Within a kind,
     * stronger evidence, work over projects, and a measured result come first.
     * Rounded so equal evidence compares equal and falls back to file order.
     */
    static double score(EvidenceItem item, EvidenceSource source, Kind kind) {
        double sourceWeight = source != null && source.kind() == EvidenceSource.Kind.EMPLOYMENT
                ? 1.0 : 0.9;
        double evidence = item.strength().weight() * sourceWeight;
        double score = kind.weight() * (0.8 + 0.2 * evidence)
                + (item.metrics().isEmpty() ? 0 : METRIC_BONUS);
        return Math.round(score * 1000) / 1000.0;
    }

    /**
     * A concept matches when the requirement uses all of its words, or when every
     * specific word of the requirement is one of the concept's.
     */
    static boolean conceptMatches(String concept, String subject, String literal,
            Set<String> requirementStems, Set<String> specific) {
        String c = concept.strip().toLowerCase(Locale.ROOT);
        if (c.equals(subject) || c.equals(literal)) {
            return true;
        }
        Set<String> conceptStems = EvidenceText.stems(concept);
        if (conceptStems.isEmpty()) {
            return false;
        }
        if (requirementStems.containsAll(conceptStems)) {
            return true;
        }
        return !specific.isEmpty() && conceptStems.containsAll(specific);
    }

    /** An idea made only of words that say nothing about which work is meant. */
    static boolean general(String idea) {
        Set<String> words = EvidenceText.stems(idea);
        words.removeAll(EvidenceText.GENERIC);
        return words.isEmpty();
    }

    private static boolean ideaMatches(String idea, String subject, String literal,
            Set<String> requirementStems) {
        String i = idea.toLowerCase(Locale.ROOT);
        if (i.equals(subject) || i.equals(literal)) {
            return true;
        }
        Set<String> ideaStems = EvidenceText.stems(idea);
        return !ideaStems.isEmpty() && requirementStems.containsAll(ideaStems);
    }

    private static String nameOf(EvidenceItem item, String key) {
        return item.technologies().stream()
                .filter(t -> EvidenceItem.key(t).equals(key))
                .findFirst()
                .orElse(key);
    }

    private record Candidate(Kind kind, String matched, String reason) {
    }

    private static Candidate better(Candidate current, Candidate next) {
        return current == null || next.kind().weight() > current.kind().weight() ? next : current;
    }
}
