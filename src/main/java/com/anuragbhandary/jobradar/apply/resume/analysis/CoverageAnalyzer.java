package com.anuragbhandary.jobradar.apply.resume.analysis;

import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger.Entry;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger.EvidenceRef;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger.MatchedBy;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources.SourceItem;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner;
import com.anuragbhandary.jobradar.knowledge.experience.Positioning;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link CoverageLedger}: each requirement, the level of evidence he has
 * for it, and the source ids that carry that evidence.
 *
 * <p>No model is consulted. The level comes from {@link ExperiencePositioner} -
 * the same decision the application-answer path already trusts - and the
 * evidence is traced to {@link ResumeSources} ids, so every citation in a ledger
 * names an item that exists.
 *
 * <h2>Two ways to find evidence, and which requirements get which</h2>
 * <ol>
 *   <li><b>The index</b>, for everything. "Kafka" is DIRECT because a line of his
 *       resume names Kafka; "Kubernetes" is ADJACENT because the graph puts it
 *       beside Docker.</li>
 *   <li><b>The wording</b>, for non-technologies only, and only through the
 *       curated phrases in {@link PostingRequirements}. A posting asking for
 *       real-time systems and a bullet describing real-time delivery say the
 *       same thing, and the ledger records the bullet as DIRECT evidence.
 *       A technology is never matched this way: a bullet containing the word
 *       "containers" is not evidence of Kubernetes, and the positioner's
 *       ADJACENT is the honest answer.</li>
 * </ol>
 * Word overlap outside those phrases produces <em>candidates</em> - bullets worth
 * a look - and never evidence. That is the gap a model may later help close, and
 * until something checkable closes it, it stays a gap.
 */
@Component
public class CoverageAnalyzer {

    /** Citations per requirement. More reads as a search of the resume, not an argument. */
    private static final int MAX_EVIDENCE = 4;

    private static final Set<String> GENERIC = Set.of(
            "experience", "ability", "strong", "build", "building", "built", "work",
            "working", "using", "with", "develop", "developing", "design", "designing",
            "and", "the", "for", "from", "into", "your", "their", "team", "teams", "able",
            "skills", "knowledge", "understanding", "good", "great", "solid", "excellent",
            "proven", "plus", "including", "such", "other", "across", "within", "what",
            "will", "have", "high", "quality", "years", "familiarity", "hands");

    private final ExperiencePositioner positioner;
    private final ResumeSources sources;

    public CoverageAnalyzer(ExperiencePositioner positioner, ResumeSources sources) {
        this.positioner = positioner;
        this.sources = sources;
    }

    /** The ledger for a posting, from its own text alone. */
    public CoverageLedger analyse(Posting posting) {
        return analyse(posting.getId(), posting.getTitle(), "deterministic",
                PostingRequirements.extract(posting.getTitle(), posting.getDescriptionText()));
    }

    /**
     * The ledger for a set of requirements, whoever proposed them.
     *
     * <p>Callers pass only grounded requirements - the parser has already dropped
     * any whose quote is not in the posting. Duplicates merge on id, keeping the
     * stronger importance.
     */
    public CoverageLedger analyse(Long postingId, String title, String requirementSource,
            List<Requirement> requirements) {
        // Compounds are split and every id is keyed on the canonical name first,
        // so "Python / JavaScript / TypeScript" is three rows and "K8s" and
        // "Kubernetes" are one. See CompoundRequirements.
        Map<String, Requirement> unique = new LinkedHashMap<>();
        for (Requirement requirement : requirements) {
            if (requirement == null || requirement.id() == null || requirement.id().isBlank()) {
                continue;
            }
            for (Requirement part : CompoundRequirements.expand(requirement)) {
                if (!part.id().isBlank()) {
                    unique.merge(part.id(), part, CoverageAnalyzer::stronger);
                }
            }
        }
        List<Entry> entries = new ArrayList<>();
        unique.values().forEach(requirement -> entries.add(withAlternativeNote(assess(requirement))));
        return CoverageLedger.of(postingId, title, requirementSource, entries);
    }

    /**
     * Which of two mentions of one requirement to keep: the stronger importance,
     * and on a tie the one asked for on its own rather than as one option of
     * several.
     */
    static Requirement stronger(Requirement a, Requirement b) {
        if (b.importance().ordinal() != a.importance().ordinal()) {
            return b.importance().ordinal() < a.importance().ordinal() ? b : a;
        }
        return a.alternativeOf() != null && b.alternativeOf() == null ? b : a;
    }

    private static Entry withAlternativeNote(Entry entry) {
        String alternatives = entry.requirement().alternativeOf();
        if (alternatives == null) {
            return entry;
        }
        return new Entry(entry.requirement(), entry.level(), entry.matchedBy(), entry.via(),
                entry.evidence(), entry.candidateSourceIds(), entry.resumeUse(),
                "one of \"" + alternatives + "\"; " + entry.note());
    }

    Entry assess(Requirement requirement) {
        String subject = PostingRequirements.canonicalSubject(requirement.term());
        Positioning position = positioner.position(subject);
        boolean technology = PostingRequirements.isTechnology(subject);

        if (position.level() == ExperienceLevel.DIRECT) {
            return fromPosition(requirement, position, MatchedBy.INDEX, List.of());
        }

        if (technology) {
            Optional<Entry> narrower = narrowerThanEvidence(requirement, subject, position);
            if (narrower.isPresent()) {
                return narrower.get();
            }
            return fromPosition(requirement, position,
                    position.level() == ExperienceLevel.NONE ? MatchedBy.NONE : MatchedBy.INDEX,
                    List.of());
        }

        // Not a technology: his own wording may already say it.
        Optional<PostingRequirements.Phrase> phrase = PostingRequirements.phraseFor(subject)
                .or(() -> PostingRequirements.phraseFor(requirement.term()));
        if (phrase.isPresent()) {
            List<SourceItem> said = sources.bullets().stream()
                    .filter(item -> phrase.get().foundIn(item.text()))
                    .limit(MAX_EVIDENCE)
                    .toList();
            if (!said.isEmpty()) {
                List<EvidenceRef> refs = said.stream().map(CoverageAnalyzer::ref).toList();
                return new Entry(requirement, ExperienceLevel.DIRECT, MatchedBy.TEXT, List.of(),
                        refs, List.of(), CoverageLedger.ResumeUse.CLAIM,
                        "the resume already says this: " + ids(refs));
            }
            return fromPosition(requirement, position,
                    position.level() == ExperienceLevel.NONE ? MatchedBy.NONE : MatchedBy.INDEX,
                    List.of());
        }

        return fromPosition(requirement, position,
                position.level() == ExperienceLevel.NONE ? MatchedBy.NONE : MatchedBy.INDEX,
                candidates(requirement.term()));
    }

    /**
     * "Kafka Streams" when he has Kafka.
     *
     * <p>A longer name wrapping exactly one of his technologies. Reported as the
     * thing he has, at most ADJACENT, because the posting asked for something
     * narrower than his evidence shows - and reporting it as DIRECT is how "Kafka"
     * on a resume becomes "Kafka Streams" in an interview.
     */
    private Optional<Entry> narrowerThanEvidence(Requirement requirement, String subject,
            Positioning position) {
        if (position.level() != ExperienceLevel.NONE) {
            return Optional.empty();
        }
        Set<String> inside = TechVocabulary.found(subject);
        if (inside.size() != 1 || inside.contains(subject.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        String contained = inside.iterator().next();
        Positioning broader = positioner.position(
                PostingRequirements.ALIASES.getOrDefault(contained, contained));
        if (broader.level() == ExperienceLevel.NONE) {
            return Optional.empty();
        }
        ExperienceLevel level = broader.level() == ExperienceLevel.DIRECT
                ? ExperienceLevel.ADJACENT : broader.level();
        List<EvidenceRef> refs = refs(broader);
        return Optional.of(new Entry(requirement, level, MatchedBy.INDEX, List.of(contained),
                refs, List.of(), CoverageLedger.useFor(level),
                "the resume has " + contained + ", not " + requirement.term()
                        + " itself; emphasise " + contained + " without claiming "
                        + requirement.term()));
    }

    private Entry fromPosition(Requirement requirement, Positioning position, MatchedBy by,
            List<String> candidates) {
        List<EvidenceRef> refs = refs(position);
        return new Entry(requirement, position.level(), by, position.via(), refs, candidates,
                CoverageLedger.useFor(position.level()),
                note(requirement, position, refs, candidates));
    }

    /** Source items for the positioner's evidence, strongest first, at most two per term. */
    private List<EvidenceRef> refs(Positioning position) {
        List<EvidenceRef> refs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ExperienceIndex.Entry entry : position.evidence()) {
            int taken = 0;
            for (SourceItem item : sources.itemsNaming(entry.term())) {
                if (taken >= 2 || refs.size() >= MAX_EVIDENCE) {
                    break;
                }
                if (seen.add(item.id())) {
                    refs.add(ref(item));
                    taken++;
                }
            }
        }
        return List.copyOf(refs);
    }

    /**
     * Bullets sharing at least half of the requirement's content words.
     *
     * <p>Crude on purpose: six-letter prefixes and a stop list. These are for a
     * person to look at, not for the ledger to count.
     */
    private List<String> candidates(String term) {
        Set<String> words = contentWords(term);
        if (words.isEmpty()) {
            return List.of();
        }
        int needed = Math.max(1, (words.size() + 1) / 2);
        List<String> found = new ArrayList<>();
        for (SourceItem bullet : sources.bullets()) {
            Set<String> bulletWords = contentWords(bullet.text());
            long shared = words.stream().filter(bulletWords::contains).count();
            if (shared >= needed) {
                found.add(bullet.id());
            }
            if (found.size() >= 3) {
                break;
            }
        }
        return List.copyOf(found);
    }

    static Set<String> contentWords(String text) {
        Set<String> words = new LinkedHashSet<>();
        if (text == null) {
            return words;
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (word.length() >= 4 && !GENERIC.contains(word)) {
                words.add(word.length() > 6 ? word.substring(0, 6) : word);
            }
        }
        return words;
    }

    private static String note(Requirement requirement, Positioning position,
            List<EvidenceRef> refs, List<String> candidates) {
        String term = requirement.term();
        return switch (position.level()) {
            case DIRECT -> refs.stream().allMatch(r -> r.kind() == ResumeSources.Kind.SKILL_GROUP)
                    ? "in the skills list only; no bullet shows it in use"
                    : "direct evidence";
            case ADJACENT, CONCEPTUAL, TRANSFERABLE -> "no direct " + term
                    + " experience; emphasise "
                    + String.join(", ", position.via().isEmpty()
                            ? position.namedEvidence() : position.via())
                    + ". Never list " + term + " as a skill or say it was used";
            case NONE -> candidates.isEmpty()
                    ? "nothing in the resume supports this; leave it out"
                    : "nothing counts as evidence; bullets worth reviewing: "
                            + String.join(", ", candidates);
        };
    }

    private static EvidenceRef ref(SourceItem item) {
        return new EvidenceRef(item.id(), item.kind(), item.parent(), item.text());
    }

    private static String ids(List<EvidenceRef> refs) {
        return String.join(", ", refs.stream().map(EvidenceRef::sourceId).toList());
    }
}
