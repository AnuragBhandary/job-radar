package com.anuragbhandary.jobradar.evidence;

import com.anuragbhandary.jobradar.apply.resume.analysis.CompoundRequirements;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.apply.resume.analysis.Requirement;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * His career evidence: one place that says what he did, in words he approved.
 *
 * <p>The resume, and later the cover letter and the application answers, draw
 * their facts from here rather than from whatever text happens to be to hand. A
 * consumer does not read resume prose and guess what it proves; it asks:
 * <pre>
 *   bank.strongestFor("event-driven backend systems")
 * </pre>
 * and gets back ranked items, each with the reason it matched and whether the
 * match lets the requirement itself be claimed. When nothing matches the answer is
 * an empty list, never a nearest guess.
 *
 * <p>Immutable, and holds only what passed {@link EvidenceValidator}. What did not
 * pass is in {@link #problems()}, so the reason an item is missing is always
 * findable.
 */
public final class EvidenceBank {

    private final String origin;
    private final List<EvidenceSource> sources;
    private final List<EvidenceItem> items;
    private final List<EvidenceProblem> problems;
    private final Map<String, EvidenceItem> byId = new LinkedHashMap<>();
    private final Map<String, EvidenceSource> sourceById = new LinkedHashMap<>();
    private final Map<String, Integer> order = new HashMap<>();

    /** Takes already-validated content. Use {@link #of} or {@link EvidenceBankLoader} to validate. */
    EvidenceBank(String origin, List<EvidenceSource> sources, List<EvidenceItem> items,
            List<EvidenceProblem> problems) {
        this.origin = origin == null ? "none" : origin;
        this.sources = List.copyOf(sources);
        this.items = List.copyOf(items);
        this.problems = List.copyOf(problems);
        this.sources.forEach(s -> sourceById.put(s.id(), s));
        for (int i = 0; i < this.items.size(); i++) {
            byId.put(this.items.get(i).id(), this.items.get(i));
            order.put(this.items.get(i).id(), i);
        }
    }

    /** Validates, keeps what passes, and records the rest as problems. */
    public static EvidenceBank of(String origin, List<EvidenceSource> sources,
            List<EvidenceItem> items) {
        EvidenceValidator.Result result = EvidenceValidator.validate(sources, items);
        return new EvidenceBank(origin, result.sources(), result.items(), result.problems());
    }

    public static EvidenceBank empty(String origin, List<EvidenceProblem> problems) {
        return new EvidenceBank(origin, List.of(), List.of(), problems);
    }

    /** Where it was read from: a path, or "none". */
    public String origin() {
        return origin;
    }

    public List<EvidenceSource> sources() {
        return sources;
    }

    /** Every usable item, in the order the file lists them. */
    public List<EvidenceItem> items() {
        return items;
    }

    public List<EvidenceProblem> problems() {
        return problems;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public boolean hasErrors() {
        return problems.stream().anyMatch(EvidenceProblem::isError);
    }

    public Optional<EvidenceItem> find(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public Optional<EvidenceSource> source(String id) {
        return Optional.ofNullable(id == null ? null : sourceById.get(id));
    }

    public EvidenceSource sourceOf(EvidenceItem item) {
        return sourceById.get(item.source());
    }

    /** The source the resume calls this name, of this kind. */
    public Optional<EvidenceSource> sourceNamed(String name, EvidenceSource.Kind kind) {
        String wanted = EvidenceText.normalise(name);
        return sources.stream()
                .filter(s -> s.kind() == kind && EvidenceText.normalise(s.name()).equals(wanted))
                .findFirst();
    }

    /** A source's items, in file order. */
    public List<EvidenceItem> itemsFrom(String sourceId) {
        return items.stream().filter(i -> i.source().equals(sourceId)).toList();
    }

    /**
     * The item that may print this exact sentence - as its claim or as an approved
     * variant. How a resume bullet or a ledger citation is traced back to evidence.
     */
    public Optional<EvidenceItem> itemWithText(String text) {
        String wanted = EvidenceText.normalise(text);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return items.stream()
                .filter(item -> item.approvedTexts().stream()
                        .anyMatch(t -> EvidenceText.normalise(t).equals(wanted)))
                .findFirst();
    }

    /** Position in the file, which breaks every tie. */
    public int orderOf(EvidenceItem item) {
        return order.getOrDefault(item.id(), Integer.MAX_VALUE);
    }

    /**
     * The strongest evidence for a requirement, best first; empty when there is none.
     *
     * <p>A compound requirement - "Python / Go", "Kafka, Redis and PostgreSQL" - is
     * split the way the coverage ledger splits it, and each item is ranked by its best
     * part.
     */
    public List<EvidenceMatch> strongestFor(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return List.of();
        }
        String term = requirement.strip();
        List<String> parts = CompoundRequirements.expand(Requirement.of(term,
                        PostingRequirements.categoryOf(term), RequirementImportance.SIGNAL, term))
                .stream().map(Requirement::term).toList();
        Map<String, EvidenceMatch> best = new LinkedHashMap<>();
        for (EvidenceItem item : items) {
            for (String part : parts) {
                EvidenceMatcher.match(item, sourceOf(item), part)
                        .ifPresent(m -> best.merge(item.id(), m, EvidenceBank::stronger));
            }
        }
        return ranked(best.values());
    }

    public List<EvidenceMatch> strongestFor(String requirement, int limit) {
        List<EvidenceMatch> all = strongestFor(requirement);
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    /**
     * The evidence for one coverage-ledger row, respecting what the ledger decided.
     *
     * <ul>
     *   <li>DIRECT: items matching the requirement itself.</li>
     *   <li>ADJACENT, CONCEPTUAL: items carrying the technologies or ideas behind
     *       that verdict - Docker for Kubernetes - scored down by the level's credit and
     *       marked as not supporting a claim.</li>
     *   <li>TRANSFERABLE: nothing. The positioner reached it only through general
     *       foundations - backend engineering, testing - that nearly every item shows,
     *       so putting them forward says nothing about this requirement.</li>
     *   <li>NONE, for a product: nothing. The ledger has said he has not used it, and no
     *       concept in the bank can overrule that.</li>
     *   <li>NONE, for an idea: items whose own words show it. The ledger only knows
     *       technologies and a few curated phrases; the bank knows "deduplication".</li>
     * </ul>
     */
    public List<EvidenceMatch> evidenceFor(CoverageLedger.Entry entry) {
        ExperienceLevel level = entry.level();
        String term = entry.requirement().term();
        List<String> terms;
        double credit;
        boolean related;
        switch (level) {
            case DIRECT -> {
                terms = List.of(term);
                credit = 1.0;
                related = false;
            }
            case NONE -> {
                if (EvidenceText.isProductName(EvidenceItem.key(term))) {
                    return List.of();
                }
                terms = List.of(term);
                credit = 1.0;
                related = false;
            }
            case TRANSFERABLE -> {
                return List.of();
            }
            default -> {
                terms = entry.via();
                credit = CoverageLedger.credit(level);
                related = true;
            }
        }
        Map<String, EvidenceMatch> best = new LinkedHashMap<>();
        for (EvidenceItem item : items) {
            for (String t : terms) {
                EvidenceMatcher.match(item, sourceOf(item), t).ifPresent(m -> {
                    EvidenceMatch scored = related
                            ? new EvidenceMatch(m.item(), m.source(), m.kind(), term, m.matched(),
                                    Math.round(m.score() * credit * 1000) / 1000.0,
                                    m.reason() + " (" + level.name().toLowerCase() + " to " + term
                                            + ", which is not claimed)", false)
                            : new EvidenceMatch(m.item(), m.source(), m.kind(), term, m.matched(),
                                    m.score(), m.reason(), m.supportsClaim());
                    best.merge(item.id(), scored, EvidenceBank::stronger);
                });
            }
        }
        return ranked(best.values());
    }

    private static EvidenceMatch stronger(EvidenceMatch a, EvidenceMatch b) {
        return b.score() > a.score() ? b : a;
    }

    private List<EvidenceMatch> ranked(Iterable<EvidenceMatch> matches) {
        List<EvidenceMatch> out = new ArrayList<>();
        matches.forEach(out::add);
        out.sort(Comparator.comparingDouble(EvidenceMatch::score).reversed()
                .thenComparingInt(m -> orderOf(m.item())));
        return List.copyOf(out);
    }
}
