package com.anuragbhandary.jobradar.apply.resume.plan;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.plan.TailoringPlan.Coverage;
import com.anuragbhandary.jobradar.apply.resume.plan.TailoringPlan.Selection;
import com.anuragbhandary.jobradar.apply.resume.rewrite.SkillOrdering;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.evidence.ApplicationEvidenceContext;
import com.anuragbhandary.jobradar.evidence.ApplicationEvidenceContext.EvidenceUse;
import com.anuragbhandary.jobradar.evidence.ApplicationEvidenceContext.RequirementEvidence;
import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import com.anuragbhandary.jobradar.evidence.EvidenceIntegrityException;
import com.anuragbhandary.jobradar.evidence.EvidenceItem;
import com.anuragbhandary.jobradar.evidence.EvidenceMatch;
import com.anuragbhandary.jobradar.evidence.EvidenceMatcher;
import com.anuragbhandary.jobradar.evidence.EvidenceSource;
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
import org.springframework.stereotype.Component;

/**
 * Plans a posting's resume, and the evidence context behind it, from the evidence
 * bank. Selection, never generation.
 *
 * <pre>
 *   posting ─▶ requirements ─▶ coverage ledger ─▶ evidence for each requirement
 *           ─▶ items ranked per job and project ─▶ approved wording per item
 *           ─▶ skills ordered by the ledger ─▶ verified ─▶ TailoredResume
 *                                                       + ApplicationEvidenceContext
 * </pre>
 *
 * <h2>What it decides</h2>
 * <ul>
 *   <li><b>Which bullets.</b> Each item's relevance is the sum, over the ledger's
 *       requirements, of the requirement's weight times how well the item matches -
 *       {@link EvidenceBank#evidenceFor}, which respects the ledger's levels: a
 *       Kubernetes requirement lifts the Docker item and never licenses the word.
 *       The most relevant items per job and project are kept, up to the resume's
 *       caps, and printed in the order the bank lists them.</li>
 *   <li><b>Which projects.</b> By total relevance, the bank's order breaking ties.</li>
 *   <li><b>Which wording.</b> The claim, unless an approved variant's emphasis matches
 *       what this posting asks of that item.</li>
 *   <li><b>Skill order.</b> {@link SkillOrdering}: what the ledger found, first.
 *       Nothing is added.</li>
 * </ul>
 * The summary is chosen exactly as before, from the approved paragraphs.
 *
 * <h2>What it cannot do</h2>
 * It has no way to write a sentence, and no model is consulted. The resume it reads
 * is composed from the bank, so every bullet it can print is a bank claim or an
 * approved variant, and {@link ResumeVerifier} checks that again. A plan that fails
 * verification is refused with {@link EvidenceIntegrityException}; there is no other
 * copy of the claims to fall back to.
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

    /** The evidence-planned resume. */
    public TailoringPlan plan(Posting posting, CoverageLedger ledger) {
        TailoredResume base = tailor.tailor(posting);
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
        verify(planned);
        return assemble(TailoringPlan.Mode.EVIDENCE, planned, posting, ledger, relevance, selections);
    }

    /**
     * The kill switch's resume: {@link ResumeTailor}'s tag selection over the same
     * composed, bank-backed resume. Claims only - no variants, no skill reordering -
     * and verified like any other.
     */
    public TailoringPlan simple(Posting posting, CoverageLedger ledger) {
        TailoredResume base = tailor.tailor(posting);
        Map<String, Relevance> relevance = relevance(ledger);
        List<Selection> selections = new ArrayList<>();
        for (ResumeModel.Job job : base.experience()) {
            orEmpty(job.bullets()).forEach(b -> selections.add(printed(job.company(), b, relevance)));
        }
        for (ResumeModel.Project project : base.projects()) {
            orEmpty(project.bullets()).forEach(b -> selections.add(printed(project.name(), b, relevance)));
        }
        verify(base);
        return assemble(TailoringPlan.Mode.SIMPLE, base, posting, ledger, relevance, selections);
    }

    /**
     * The evidence context of a resume already printed with these ids - how a resumed
     * application grounds its letter and answers on the PDF it already has.
     *
     * @throws EvidenceIntegrityException when an id is no longer in the bank
     */
    public ApplicationEvidenceContext context(Posting posting, CoverageLedger ledger,
            List<String> printedIds) {
        List<String> missing = printedIds.stream().filter(id -> bank.find(id).isEmpty()).toList();
        if (!missing.isEmpty()) {
            throw new EvidenceIntegrityException("The resume already rendered for this application "
                    + "cites evidence the bank no longer holds - prepare it again to render a new one",
                    missing.stream().map(id -> "missing evidence item " + id).toList());
        }
        Map<String, Relevance> relevance = relevance(ledger);
        Map<String, String> wordings = new LinkedHashMap<>();
        for (String id : printedIds) {
            EvidenceItem item = bank.find(id).orElseThrow();
            wordings.put(id, wording(item, relevance.get(id)).text());
        }
        return context(posting, ledger, relevance, printedIds, wordings);
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
     * The most relevant items of one source, up to the cap, printed in bank order.
     *
     * <p>Two orderings, as in {@link ResumeTailor}: selection by relevance,
     * presentation by the order he wrote. Ties go to the earlier item, so a posting
     * that asks for nothing he has keeps his opening bullets.
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

    private Selection printed(String section, ResumeModel.Bullet bullet, Map<String, Relevance> relevance) {
        Relevance r = bullet.id() == null ? new Relevance() : relevance.getOrDefault(bullet.id(), new Relevance());
        return new Selection(section, bullet.id(), EvidenceItem.CLAIM, bullet.text(), round(r.score),
                because(r), true);
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
        List<String> tags = new ArrayList<>(item.technologies());
        tags.addAll(item.concepts());
        return tags.stream().distinct().toList();
    }

    private void verify(TailoredResume planned) {
        List<String> violations = ResumeVerifier.verify(planned, bank, resume);
        if (!violations.isEmpty()) {
            throw new EvidenceIntegrityException(
                    "The resume failed verification, so it was not generated", violations);
        }
    }

    private TailoringPlan assemble(TailoringPlan.Mode mode, TailoredResume planned, Posting posting,
            CoverageLedger ledger, Map<String, Relevance> relevance, List<Selection> selections) {
        List<String> printed = new ArrayList<>();
        Map<String, String> wordings = new LinkedHashMap<>();
        for (Selection s : selections) {
            if (s.included() && s.evidenceId() != null) {
                printed.add(s.evidenceId());
                wordings.put(s.evidenceId(), s.text());
            }
        }
        ApplicationEvidenceContext context = context(posting, ledger, relevance, printed, wordings);
        List<Coverage> coverage = new ArrayList<>();
        if (ledger != null) {
            for (int i = 0; i < ledger.entries().size(); i++) {
                CoverageLedger.Entry entry = ledger.entries().get(i);
                RequirementEvidence row = context.requirements().get(i);
                List<String> shown = new ArrayList<>();
                row.claimableIds().stream().filter(printed::contains).forEach(shown::add);
                row.relatedIds().stream().filter(printed::contains).forEach(shown::add);
                coverage.add(new Coverage(entry.requirement().term(), entry.requirement().importance(),
                        entry.level(), entry.resumeUse(), shown, row.note()));
            }
        }
        return new TailoringPlan(mode, planned, ledger, selections, coverage, context);
    }

    private ApplicationEvidenceContext context(Posting posting, CoverageLedger ledger,
            Map<String, Relevance> relevance, List<String> printed, Map<String, String> wordings) {
        Set<String> onResume = new LinkedHashSet<>(printed);
        List<RequirementEvidence> requirements = new ArrayList<>();
        if (ledger != null) {
            for (CoverageLedger.Entry entry : ledger.entries()) {
                List<EvidenceMatch> matches = bank.evidenceFor(entry);
                List<String> claimable = matches.stream().filter(EvidenceMatch::supportsClaim)
                        .map(EvidenceMatch::itemId).toList();
                List<String> related = matches.stream().filter(m -> !m.supportsClaim())
                        .map(EvidenceMatch::itemId).toList();
                List<String> via = entry.level() == ExperienceLevel.ADJACENT
                        || entry.level() == ExperienceLevel.CONCEPTUAL ? entry.via() : List.of();
                String term = entry.requirement().term();
                String display = matches.stream().filter(EvidenceMatch::supportsClaim)
                        .map(EvidenceMatch::matched).findFirst().orElse(term);
                requirements.add(new RequirementEvidence(term, display, entry.requirement().importance(),
                        entry.level(), EvidenceMatcher.isProduct(term), claimable, related, via,
                        note(entry, matches, onResume)));
            }
        }

        List<EvidenceUse> uses = new ArrayList<>();
        for (EvidenceItem item : bank.items()) {
            Relevance r = relevance.get(item.id());
            boolean on = onResume.contains(item.id());
            if (!on && (r == null || r.hits.isEmpty())) {
                continue;
            }
            List<String> supports = r == null ? List.of() : r.hits.stream()
                    .filter(h -> h.match().supportsClaim())
                    .map(h -> h.entry().requirement().term()).distinct().toList();
            List<String> relatedTo = r == null ? List.of() : r.hits.stream()
                    .filter(h -> !h.match().supportsClaim())
                    .map(h -> h.entry().requirement().term()).distinct().toList();
            uses.add(new EvidenceUse(item, bank.sourceOf(item),
                    wordings.getOrDefault(item.id(), item.claim()),
                    r == null ? 0 : round(r.score), on, supports, relatedTo));
        }
        List<String> order = new ArrayList<>(printed);
        uses.sort(Comparator.comparing((EvidenceUse u) -> !u.onResume())
                .thenComparing(u -> !u.supportsDirectClaim())
                .thenComparing(Comparator.comparingDouble(EvidenceUse::relevance).reversed())
                .thenComparingInt(u -> order.contains(u.id()) ? order.indexOf(u.id()) : Integer.MAX_VALUE)
                .thenComparingInt(u -> bank.orderOf(u.item())));
        return new ApplicationEvidenceContext(posting.getId(), posting.getTitle(), requirements, uses,
                printed);
    }

    private static String note(CoverageLedger.Entry entry, List<EvidenceMatch> matches,
            Set<String> printed) {
        List<EvidenceMatch> shown = matches.stream().filter(m -> printed.contains(m.itemId())).toList();
        if (!shown.isEmpty()) {
            return shown.stream().anyMatch(EvidenceMatch::supportsClaim)
                    ? "shown" : "related work shown; " + entry.requirement().term() + " is not claimed";
        }
        if (!matches.isEmpty()) {
            return "evidence exists and was not selected: " + String.join(", ",
                    matches.stream().map(EvidenceMatch::itemId).toList());
        }
        return switch (entry.level()) {
            case NONE -> "nothing supports this; left out";
            case TRANSFERABLE -> "only general engineering foundations apply; nothing specific is put forward";
            case DIRECT -> "no evidence item shows it in use (the ledger found it in the skills list or tags)";
            default -> "no evidence item carries what it is related to";
        };
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
