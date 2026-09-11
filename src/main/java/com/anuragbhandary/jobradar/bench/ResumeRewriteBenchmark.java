package com.anuragbhandary.jobradar.bench;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.apply.resume.analysis.Requirement;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import com.anuragbhandary.jobradar.apply.resume.rewrite.EvidenceScope;
import com.anuragbhandary.jobradar.apply.resume.rewrite.GenerativeTailor;
import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteMetrics;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteParser;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewritePlanner;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewritePrompt;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteQuality;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteRequest;
import com.anuragbhandary.jobradar.apply.resume.rewrite.SkillOrdering;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The shadow comparison: the deterministic resume for a posting, and the same
 * resume with its selected bullets rewritten by a local model and validated in
 * Java.
 *
 * <p>An experiment. Its only output is a report; nothing it produces reaches an
 * application. Requests go one at a time, and the model is unloaded when the batch
 * ends.
 *
 * <p>Bullets only. The summary is the approved one the deterministic tailor chose,
 * in both versions - see {@link RewritePlanner} for why summaries are not written.
 */
public class ResumeRewriteBenchmark {

    /** The summary policy, printed in every report so nobody wonders. */
    public static final String SUMMARY_POLICY =
            "deterministic: the approved summary the tailor selects, in both versions; "
                    + "AI summary rewriting is disabled";

    public enum Outcome {
        /** Passed validation and differs from the source. */
        ACCEPTED,
        /** The model returned the source wording. */
        UNCHANGED,
        /** Failed validation or could not be parsed; the source wording stands. */
        REJECTED,
        /** No answer from the server; the source wording stands. */
        MODEL_FAILED
    }

    /**
     * @param generated     what the model wrote, or null when it wrote nothing usable
     * @param finalText     what the candidate resume uses: the rewrite if accepted,
     *                      otherwise the source
     * @param quality       a mechanical label; see {@link RewriteQuality.Label}
     * @param moreJobSpecific the generated text names more of this item's target
     *                      requirements than the source, or shares at least two more
     *                      of the posting's own content words
     */
    public record ItemResult(
            String sourceId,
            String kind,
            String where,
            String original,
            String generated,
            String finalText,
            Outcome outcome,
            RewriteQuality.Label quality,
            List<ResumeClaimValidator.Issue> issues,
            List<String> warnings,
            List<String> targets,
            List<String> relatedNotHis,
            List<String> prohibited,
            int targetsNamedBefore,
            int targetsNamedAfter,
            int postingWordsBefore,
            int postingWordsAfter,
            boolean moreJobSpecific,
            double retention,
            int sourceMetrics,
            int sourceMetricsKept,
            long latencyMillis,
            Integer outputTokens) {
    }

    /**
     * @param nonDirectTermsNamed requirements he does not directly have that the
     *                            resume text nevertheless names
     * @param requiredSkillRank   average position of the skills group first naming
     *                            each required DIRECT requirement, 1 = top
     */
    public record Coverage(
            int requiredDirect,
            int requiredDirectShown,
            int preferredDirect,
            int preferredDirectShown,
            int strongDirectMatches,
            List<String> nonDirectTermsNamed,
            List<String> skillGroupOrder,
            Double requiredSkillRank) {
    }

    public record PostingRun(
            Long postingId,
            String title,
            String why,
            String requirementSource,
            CoverageLedger ledger,
            TailoredResume deterministic,
            TailoredResume generative,
            List<ItemResult> bullets,
            String summaryPolicy,
            Coverage deterministicCoverage,
            Coverage generativeCoverage,
            long extractMillis,
            long rewriteMillis) {
    }

    public record Case(Posting posting, String why) {
    }

    private final LocalModel extractor;
    private final LocalModel writer;
    private final String model;
    private final ResumeTailor tailor;
    private final CoverageAnalyzer analyzer;
    private final ResumeSources sources;
    private final Consumer<String> progress;

    /**
     * @param extractor the model at temperature zero, for requirements
     * @param writer    the same model at a low temperature, for rewrites. Same
     *                  load options, so switching between them does not reload it.
     */
    public ResumeRewriteBenchmark(LocalModel extractor, LocalModel writer, String model,
            ResumeTailor tailor, CoverageAnalyzer analyzer, ResumeSources sources,
            Consumer<String> progress) {
        this.extractor = extractor;
        this.writer = writer;
        this.model = model;
        this.tailor = tailor;
        this.analyzer = analyzer;
        this.sources = sources;
        this.progress = progress;
    }

    /** Runs every posting in turn, then unloads the model whatever happened. */
    public List<PostingRun> run(List<Case> cases) {
        List<PostingRun> runs = new ArrayList<>();
        try {
            for (Case c : cases) {
                runs.add(runOne(c));
            }
        } finally {
            writer.unload(model);
        }
        return runs;
    }

    private PostingRun runOne(Case c) {
        Posting posting = c.posting();
        progress.accept("posting " + posting.getId() + ": reading requirements");

        long started = System.nanoTime();
        ModelCall extraction = extractor.chat(model, ExtractionPrompt.system(),
                ExtractionPrompt.user(posting.getTitle(), posting.getDescriptionText()),
                ExtractionPrompt.schema());
        RequirementParser.Result parsed = extraction.ok()
                ? RequirementParser.parse(extraction.content(), PostingRequirements.groundingText(
                        posting.getTitle(), posting.getDescriptionText()))
                : null;
        List<Requirement> requirements;
        String requirementSource;
        if (parsed != null && !parsed.accepted().isEmpty()) {
            requirements = parsed.accepted();
            requirementSource = model + ": " + parsed.accepted().size() + " grounded, "
                    + parsed.count(RequirementParser.Reason.UNGROUNDED) + " ungrounded rejected";
        } else {
            requirements = PostingRequirements.extract(posting.getTitle(), posting.getDescriptionText());
            requirementSource = "deterministic (model extraction "
                    + (extraction.ok() ? "gave nothing usable" : "failed: " + extraction.error()) + ")";
        }
        long extractMillis = millisSince(started);

        CoverageLedger ledger = analyzer.analyse(posting.getId(), posting.getTitle(),
                requirementSource, requirements);
        TailoredResume deterministic = tailor.tailor(posting);

        Set<String> known = new HashSet<>();
        sources.all().forEach(item -> known.add(item.id()));

        long rewriteStarted = System.nanoTime();
        List<ItemResult> bullets = new ArrayList<>();
        Map<String, String> accepted = new LinkedHashMap<>();
        for (ResumeModel.Bullet bullet : selectedBullets(deterministic)) {
            Optional<String> id = sources.idOf(bullet);
            Optional<EvidenceScope> scope = id.flatMap(i -> EvidenceScope.forBullet(sources, i));
            if (scope.isEmpty()) {
                bullets.add(notFound(bullet));
                continue;
            }
            RewriteRequest request = RewritePlanner.forBullet(scope.get(), ledger);
            ModelCall call = writer.chat(model, RewritePrompt.system(),
                    RewritePrompt.user(request, posting.getTitle()),
                    RewritePrompt.schema(request.sourceId()));
            ItemResult result = evaluate(request, call, known,
                    sources.find(id.get()).map(i -> i.kind().name()).orElse("?"),
                    sources.find(id.get()).map(ResumeSources.SourceItem::parent).orElse(""));
            bullets.add(result);
            if (result.outcome() == Outcome.ACCEPTED) {
                accepted.put(request.sourceId(), result.finalText());
            }
            progress.accept(String.format("posting %d: %s %s/%s (%.1fs)", posting.getId(),
                    request.sourceId(), result.outcome(), result.quality(),
                    result.latencyMillis() / 1000.0));
        }
        long rewriteMillis = millisSince(rewriteStarted);

        TailoredResume generative = GenerativeTailor.assemble(deterministic, sources, accepted,
                SkillOrdering.reorder(deterministic.skills(), ledger));

        return new PostingRun(posting.getId(), posting.getTitle(), c.why(), requirementSource,
                ledger, deterministic, generative, List.copyOf(bullets), SUMMARY_POLICY,
                coverage(deterministic, ledger), coverage(generative, ledger),
                extractMillis, rewriteMillis);
    }

    /** Parse, validate, classify. Public for the tests; no model involved. */
    public static ItemResult evaluate(RewriteRequest request, ModelCall call, Set<String> known,
            String kind, String where) {
        List<String> targets = request.targets().stream()
                .map(t -> t.term() + " (" + t.importance() + ")").toList();
        List<String> related = request.adjacent().stream()
                .map(a -> a.term() + " -> " + String.join(", ", a.via())).toList();
        int before = RewriteMetrics.targetsCovered(request.originalText(), request.targets());
        int wordsBefore = RewriteMetrics.postingOverlap(request.originalText(), request.targets());

        if (!call.ok()) {
            return new ItemResult(request.sourceId(), kind, where, request.originalText(), null,
                    request.originalText(), Outcome.MODEL_FAILED, RewriteQuality.Label.MODEL_FAILED,
                    List.of(new ResumeClaimValidator.Issue(
                            ResumeClaimValidator.IssueType.INVALID_RESPONSE, call.error())),
                    List.of(), targets, related, request.prohibited(), before, before,
                    wordsBefore, wordsBefore, false, 1.0, 0, 0, call.wallMillis(), null);
        }
        RewriteParser.Parsed parsed = RewriteParser.parse(call.content(), request.sourceId());
        if (!parsed.ok()) {
            ResumeClaimValidator.IssueType type = parsed.wrongSource()
                    ? ResumeClaimValidator.IssueType.UNKNOWN_SOURCE
                    : ResumeClaimValidator.IssueType.INVALID_RESPONSE;
            return new ItemResult(request.sourceId(), kind, where, request.originalText(), null,
                    request.originalText(), Outcome.REJECTED, RewriteQuality.Label.REJECTED,
                    List.of(new ResumeClaimValidator.Issue(type, parsed.error())), List.of(),
                    targets, related, request.prohibited(), before, before, wordsBefore,
                    wordsBefore, false, 1.0, 0, 0, call.wallMillis(), call.outputTokens());
        }

        String text = parsed.text();
        ResumeClaimValidator.Verdict verdict = ResumeClaimValidator.check(text, request, known);
        boolean unchanged = normalise(text).equals(normalise(request.originalText()));
        Outcome outcome = !verdict.pass() ? Outcome.REJECTED
                : unchanged ? Outcome.UNCHANGED : Outcome.ACCEPTED;
        int after = RewriteMetrics.targetsCovered(text, request.targets());
        int wordsAfter = RewriteMetrics.postingOverlap(text, request.targets());
        boolean moreSpecific = !unchanged && (after > before || wordsAfter >= wordsBefore + 2);
        RewriteQuality.Label quality = RewriteQuality.classify(outcome.name(),
                request.originalText(), text, before, after, wordsBefore, wordsAfter);

        return new ItemResult(request.sourceId(), kind, where, request.originalText(), text,
                outcome == Outcome.ACCEPTED ? text : request.originalText(), outcome, quality,
                verdict.issues(), verdict.warnings(), targets, related, request.prohibited(),
                before, after, wordsBefore, wordsAfter, moreSpecific, verdict.retention(),
                verdict.sourceMetrics(), verdict.sourceMetricsKept(), call.wallMillis(),
                call.outputTokens());
    }

    /** How much of what the posting asks for this resume shows, and where. */
    static Coverage coverage(TailoredResume resume, CoverageLedger ledger) {
        StringBuilder narrative = new StringBuilder(resume.summary().text());
        selectedBullets(resume).forEach(b -> narrative.append('\n').append(b.text()));
        StringBuilder skills = new StringBuilder();
        List<String> groupOrder = new ArrayList<>();
        for (ResumeModel.SkillGroup group : resume.skills()) {
            groupOrder.add(group.group());
            skills.append(group.group()).append(": ").append(String.join(", ", group.items())).append('\n');
        }

        int requiredDirect = 0;
        int requiredShown = 0;
        int preferredDirect = 0;
        int preferredShown = 0;
        int strong = 0;
        List<String> nonDirect = new ArrayList<>();
        List<Integer> ranks = new ArrayList<>();
        for (CoverageLedger.Entry entry : ledger.entries()) {
            String term = entry.requirement().term();
            RequirementImportance importance = entry.requirement().importance();
            boolean inNarrative = RewriteMetrics.mentions(narrative.toString(), term);
            boolean inSkills = RewriteMetrics.mentions(skills.toString(), term);
            if (entry.level() == ExperienceLevel.DIRECT) {
                if (importance == RequirementImportance.REQUIRED) {
                    requiredDirect++;
                    if (inNarrative || inSkills) {
                        requiredShown++;
                    }
                    for (int i = 0; i < resume.skills().size(); i++) {
                        ResumeModel.SkillGroup group = resume.skills().get(i);
                        if (RewriteMetrics.mentions(group.group() + ": "
                                + String.join(", ", group.items()), term)) {
                            ranks.add(i + 1);
                            break;
                        }
                    }
                } else if (importance == RequirementImportance.PREFERRED) {
                    preferredDirect++;
                    if (inNarrative || inSkills) {
                        preferredShown++;
                    }
                }
                if (importance != RequirementImportance.SIGNAL && inNarrative) {
                    strong++;
                }
            } else if (inNarrative || inSkills) {
                nonDirect.add(term + " (" + entry.level() + ")");
            }
        }
        Double rank = ranks.isEmpty() ? null
                : ranks.stream().mapToInt(Integer::intValue).average().orElse(0);
        return new Coverage(requiredDirect, requiredShown, preferredDirect, preferredShown,
                strong, List.copyOf(nonDirect), List.copyOf(groupOrder), rank);
    }

    static List<ResumeModel.Bullet> selectedBullets(TailoredResume resume) {
        List<ResumeModel.Bullet> bullets = new ArrayList<>();
        resume.experience().forEach(job -> {
            if (job.bullets() != null) {
                bullets.addAll(job.bullets());
            }
        });
        resume.projects().forEach(project -> {
            if (project.bullets() != null) {
                bullets.addAll(project.bullets());
            }
        });
        return bullets;
    }

    private static ItemResult notFound(ResumeModel.Bullet bullet) {
        return new ItemResult(null, "?", "", bullet.text(), null, bullet.text(), Outcome.REJECTED,
                RewriteQuality.Label.REJECTED,
                List.of(new ResumeClaimValidator.Issue(ResumeClaimValidator.IssueType.UNKNOWN_SOURCE,
                        "bullet has no source id")), List.of(), List.of(), List.of(), List.of(),
                0, 0, 0, 0, false, 1.0, 0, 0, 0, null);
    }

    private static String normalise(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip()
                .replaceAll("[.]$", "").toLowerCase(Locale.ROOT);
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
