package com.anuragbhandary.jobradar.apply.resume.plan;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.plan.TailoringPlan.Coverage;
import com.anuragbhandary.jobradar.apply.resume.plan.TailoringPlan.Selection;
import com.anuragbhandary.jobradar.apply.resume.rewrite.SkillOrdering;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import com.anuragbhandary.jobradar.evidence.EvidenceItem;
import com.anuragbhandary.jobradar.evidence.EvidenceMatch;
import com.anuragbhandary.jobradar.evidence.EvidenceSource;
import com.anuragbhandary.jobradar.evidence.ResumeConsistency;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Plans a posting's resume from the evidence bank. Selection, never generation.
 *
 * <pre>
 *   posting ─▶ requirements ─▶ coverage ledger ─▶ evidence for each requirement
 *           ─▶ items ranked per job and project ─▶ approved wording per item
 *           ─▶ skills ordered by the ledger ─▶ verified ─▶ TailoredResume
 * </pre>
 *
 * <h2>What it decides</h2>
 * <ul>
 *   <li><b>Which bullets.</b> Each item's relevance is the sum, over the ledger's
 *       requirements, of the requirement's weight times how well the item matches -
 *       {@link EvidenceBank#evidenceFor}, which respects the ledger's levels: a
 *       Kubernetes requirement lifts the Docker item, and never licenses the word.
 *       The most relevant items per job and project are kept, up to the resume's
 *       caps, and printed in the order the file lists them.</li>
 *   <li><b>Which projects.</b> By total relevance, the resume's own order breaking
 *       ties, up to the resume's cap.</li>
 *   <li><b>Which wording.</b> The claim, unless an approved variant's emphasis matches
 *       what this posting asks of that item.</li>
 *   <li><b>Skill order.</b> {@link SkillOrdering}: what the ledger found, first.
 *       Nothing is added.</li>
 * </ul>
 * The summary is chosen exactly as before, from the approved paragraphs.
 *
 * <h2>What it cannot do</h2>
 * It has no way to write a sentence. Every bullet it outputs is the text of an item's
 * claim or approved variant, and {@link ResumeVerifier} checks that again before the
 * plan is returned. No model is consulted.
 *
 * <h2>When it steps aside</h2>
 * No bank, a bank the resume does not match, or a result that fails verification:
 * the resume is tailored exactly as it was before the bank existed, and the plan
 * says why.
 */
@Component
public class TailoringPlanner {

    private final ResumeModel resume;
    private final ResumeTailor tailor;
    private final EvidenceBank bank;

    public TailoringPlanner(ResumeModel resume, ResumeTailor tailor, EvidenceBank bank) {
        this.resume = resume;
        this.tailor = tailor;
        this.bank = bank;
    }

    public TailoringPlan plan(Posting posting, CoverageLedger ledger) {
        TailoredResume base = tailor.tailor(posting);
        if (bank.isEmpty()) {
            return TailoringPlan.legacy(base, ledger, List.of(bank.hasErrors()
                    ? "the evidence bank at " + bank.origin()
                            + " has no usable items - run `evidence --check`"
                    : "no evidence bank at " + bank.origin()));
        }
        ResumeConsistency consistency = ResumeConsistency.check(bank, resume);
        if (!consistency.consistent()) {
            List<String> reasons = new ArrayList<>();
            reasons.add("the evidence bank does not match the resume - run `evidence --check`");
            consistency.errors().forEach(p -> reasons.add(p.toString()));
            return TailoringPlan.legacy(base, ledger, reasons);
        }

        Map<String, Relevance> relevance = relevance(ledger);
        List<Selection> selections = new ArrayList<>();

        List<ResumeModel.Job> jobs = new ArrayList<>();
        for (ResumeModel.Job job : orEmpty(resume.experience())) {
            List<ResumeModel.Bullet> bullets = bank
                    .sourceNamed(job.company(), EvidenceSource.Kind.EMPLOYMENT)
                    .map(source -> choose(source, job.company(), resume.maxBulletsPerJob(),
                            relevance, selections))
                    .orElse(List.of());
            jobs.add(new ResumeModel.Job(job.company(), job.title(), job.location(), job.period(),
                    job.note(), bullets));
        }

        record Ranked(ResumeModel.Project project, EvidenceSource source, double score, int index) {
        }
        List<Ranked> ranked = new ArrayList<>();
        List<ResumeModel.Project> declared = resume.projects();
        for (int i = 0; i < declared.size(); i++) {
            ResumeModel.Project project = declared.get(i);
            EvidenceSource source = bank.sourceNamed(project.name(), EvidenceSource.Kind.PROJECT)
                    .orElse(null);
            double score = source == null ? 0 : bank.itemsFrom(source.id()).stream()
                    .mapToDouble(item -> relevance.get(item.id()).score).sum();
            ranked.add(new Ranked(project, source, round(score), i));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed()
                .thenComparingInt(Ranked::index));

        List<ResumeModel.Project> projects = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (Ranked entry : ranked) {
            ResumeModel.Project project = entry.project();
            if (projects.size() < resume.maxProjects()) {
                List<ResumeModel.Bullet> bullets = entry.source() == null ? List.of()
                        : choose(entry.source(), project.name(), resume.maxBulletsPerProject(),
                                relevance, selections);
                projects.add(new ResumeModel.Project(project.name(), project.stack(),
                        project.tags(), bullets));
            } else {
                dropped.add(project.name());
            }
        }

        TailoredResume planned = new TailoredResume(
                base.summary(),
                SkillOrdering.reorder(resume.skills(), ledger),
                jobs,
                projects,
                resume.education(),
                resume.extras(),
                base.matchedTags(),
                dropped);

        List<String> violations = ResumeVerifier.verify(planned, bank, resume);
        if (!violations.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            reasons.add("the planned resume failed verification, so the resume was tailored as before");
            reasons.addAll(violations);
            return TailoringPlan.legacy(base, ledger, reasons);
        }
        return new TailoringPlan(TailoringPlan.Mode.EVIDENCE, planned, ledger, selections,
                coverage(ledger, selections), List.of());
    }

    // ------------------------------------------------------------------

    /** How much of this posting an item answers, and through which requirements. */
    private static final class Relevance {
        double score;
        final List<Hit> hits = new ArrayList<>();
    }

    private record Hit(CoverageLedger.Entry entry, EvidenceMatch match, double weight) {
    }

    private Map<String, Relevance> relevance(CoverageLedger ledger) {
        Map<String, Relevance> out = new LinkedHashMap<>();
        bank.items().forEach(item -> out.put(item.id(), new Relevance()));
        if (ledger == null) {
            return out;
        }
        for (CoverageLedger.Entry entry : ledger.entries()) {
            double weight = entry.requirement().importance().weight();
            for (EvidenceMatch match : bank.evidenceFor(entry)) {
                Relevance r = out.get(match.itemId());
                r.score += weight * match.score();
                r.hits.add(new Hit(entry, match, weight));
            }
        }
        return out;
    }

    /**
     * The most relevant items of one source, up to the cap, printed in file order.
     *
     * <p>Two orderings, as in {@link ResumeTailor}: selection by relevance,
     * presentation by the order he wrote. A list re-sorted by score reads as
     * assembled by a machine. Ties go to the earlier item, so a posting that asks
     * for nothing he has keeps his opening bullets.
     */
    private List<ResumeModel.Bullet> choose(EvidenceSource source, String section, int cap,
            Map<String, Relevance> relevance, List<Selection> selections) {
        List<EvidenceItem> items = bank.itemsFrom(source.id());
        List<EvidenceItem> byRelevance = new ArrayList<>(items);
        byRelevance.sort(Comparator.comparingDouble((EvidenceItem i) -> round(relevance.get(i.id()).score))
                .reversed()
                .thenComparingInt(bank::orderOf));
        Set<String> chosen = new LinkedHashSet<>();
        byRelevance.stream().limit(cap).forEach(i -> chosen.add(i.id()));

        List<ResumeModel.Bullet> bullets = new ArrayList<>();
        for (EvidenceItem item : items) {
            Relevance r = relevance.get(item.id());
            boolean included = chosen.contains(item.id());
            Wording wording = included ? wording(item, r) : new Wording(EvidenceItem.CLAIM, item.claim());
            selections.add(new Selection(section, item.id(), wording.id(), wording.text(),
                    round(r.score), because(r), included));
            if (included) {
                bullets.add(new ResumeModel.Bullet(wording.text(), tags(item), item.id()));
            }
        }
        return bullets;
    }

    private record Wording(String id, String text) {
    }

    /**
     * The claim, unless an approved variant puts first what this posting asks of the
     * item. Scored by the weight of the requirements its emphasis answers; the claim
     * wins every tie, and an earlier variant beats a later one.
     */
    private static Wording wording(EvidenceItem item, Relevance relevance) {
        Wording best = new Wording(EvidenceItem.CLAIM, item.claim());
        double bestScore = 0;
        for (EvidenceItem.Variant variant : item.approvedVariants()) {
            double score = 0;
            for (Hit hit : relevance.hits) {
                if (emphasises(variant, hit)) {
                    score += hit.weight();
                }
            }
            if (score > bestScore) {
                best = new Wording(variant.id(), variant.text());
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean emphasises(EvidenceItem.Variant variant, Hit hit) {
        // A HashSet, not Set.of: the matched term and the requirement are often the
        // same word, and Set.of refuses duplicates.
        Set<String> answered = new HashSet<>(List.of(
                lower(hit.match().matched()),
                EvidenceItem.key(hit.match().matched()),
                lower(hit.entry().requirement().term()),
                EvidenceItem.key(hit.entry().requirement().term())));
        return variant.emphasis().stream()
                .anyMatch(e -> answered.contains(lower(e)) || answered.contains(EvidenceItem.key(e)));
    }

    private static List<String> because(Relevance relevance) {
        return relevance.hits.stream()
                .map(h -> h.entry().requirement().term() + ": " + h.match().reason())
                .distinct()
                .toList();
    }

    private static List<String> tags(EvidenceItem item) {
        return Stream.concat(item.technologies().stream(), item.concepts().stream())
                .map(TailoringPlanner::lower)
                .distinct()
                .toList();
    }

    private List<Coverage> coverage(CoverageLedger ledger, List<Selection> selections) {
        List<Coverage> rows = new ArrayList<>();
        if (ledger == null) {
            return rows;
        }
        Set<String> included = new LinkedHashSet<>();
        selections.stream().filter(Selection::included).forEach(s -> included.add(s.evidenceId()));
        for (CoverageLedger.Entry entry : ledger.entries()) {
            List<EvidenceMatch> evidence = bank.evidenceFor(entry);
            List<EvidenceMatch> printed = evidence.stream()
                    .filter(m -> included.contains(m.itemId())).toList();
            String note;
            if (!printed.isEmpty()) {
                note = printed.stream().anyMatch(EvidenceMatch::supportsClaim)
                        ? "shown" : "related work shown; " + entry.requirement().term() + " is not claimed";
            } else if (!evidence.isEmpty()) {
                note = "evidence exists and was not selected: " + String.join(", ",
                        evidence.stream().map(EvidenceMatch::itemId).toList());
            } else if (entry.level() == ExperienceLevel.NONE) {
                note = "nothing supports this; left out";
            } else if (entry.level() == ExperienceLevel.TRANSFERABLE) {
                note = "only general engineering foundations apply; nothing specific is put forward";
            } else if (entry.level() == ExperienceLevel.DIRECT) {
                note = "no evidence item shows it in use (the ledger found it in the skills list or tags)";
            } else {
                note = "no evidence item carries what it is related to";
            }
            rows.add(new Coverage(entry.requirement().term(), entry.requirement().importance(),
                    entry.level(), entry.resumeUse(),
                    printed.stream().map(EvidenceMatch::itemId).toList(), note));
        }
        return rows;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static String lower(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
