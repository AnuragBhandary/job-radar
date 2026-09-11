package com.anuragbhandary.jobradar.bench;

import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import com.anuragbhandary.jobradar.apply.resume.rewrite.EvidenceScope;
import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteQuality;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteRequest;
import com.anuragbhandary.jobradar.bench.ResumeRewriteBenchmark.ItemResult;
import com.anuragbhandary.jobradar.bench.ResumeRewriteBenchmark.PostingRun;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The previous rewrite run against this one, measured the same way.
 *
 * <p>Two kinds of number. What each run <em>recorded</em> - its outcomes, as its
 * own validator judged them. And what the <em>current</em> validator says about
 * every rewrite each run accepted: the previous run's accepted rewrites are
 * re-checked against today's rules, so "the new validator would have caught this"
 * is a count rather than a claim. Quality labels are recomputed for both from the
 * text with the same classifier.
 *
 * <p>Skill ordering is reported per posting and is deterministic in both runs; it
 * is never counted as the model's improvement.
 */
public record RewriteComparison(Side before, Side after, List<String> notes) {

    /**
     * @param acceptedFailingCurrentValidator accepted rewrites today's validator rejects
     * @param currentValidatorIssues          and why, by issue type
     * @param quality                         label counts, recomputed from the text
     */
    public record Side(
            String label,
            int bullets,
            int accepted,
            int unchanged,
            int rejected,
            int modelFailed,
            int sourceIdErrors,
            Map<String, Long> recordedIssues,
            int acceptedFailingCurrentValidator,
            Map<String, Long> currentValidatorIssues,
            Map<String, Long> quality,
            int metricsKept,
            int metricsTotal,
            int summariesGenerated,
            int summariesAccepted,
            List<Coverage> coverage) {
    }

    public record Coverage(
            Long postingId,
            int requiredDirect,
            int requiredShownDeterministic,
            int requiredShownGenerative,
            int preferredDirect,
            int preferredShownDeterministic,
            int preferredShownGenerative,
            int strongDeterministic,
            int strongGenerative,
            Double skillRankDeterministic,
            Double skillRankGenerative) {
    }

    record Item(String sourceId, String original, String generated, String outcome,
            List<String> issueTypes, int targetsBefore, int targetsAfter, int wordsBefore,
            int wordsAfter, int metrics, int metricsKept) {
    }

    public static RewriteComparison of(JsonNode baseline, List<PostingRun> runs,
            ResumeSources sources) {
        List<Item> beforeItems = new ArrayList<>();
        List<Coverage> beforeCoverage = new ArrayList<>();
        int summariesGenerated = 0;
        int summariesAccepted = 0;
        for (JsonNode posting : baseline.path("postings")) {
            JsonNode run = posting.path("run");
            run.path("bullets").forEach(node -> beforeItems.add(item(node)));
            JsonNode summary = run.path("summary");
            if (summary.isObject()) {
                if (summary.hasNonNull("generated")) {
                    summariesGenerated++;
                }
                if ("ACCEPTED".equals(summary.path("outcome").asText())) {
                    summariesAccepted++;
                }
            }
            beforeCoverage.add(coverage(run.path("postingId").asLong(),
                    run.path("deterministicCoverage"), run.path("generativeCoverage")));
        }

        List<Item> afterItems = new ArrayList<>();
        List<Coverage> afterCoverage = new ArrayList<>();
        for (PostingRun run : runs) {
            run.bullets().forEach(result -> afterItems.add(item(result)));
            afterCoverage.add(coverage(run));
        }

        Side before = side("previous run", beforeItems, summariesGenerated, summariesAccepted,
                beforeCoverage, sources);
        Side after = side("this run", afterItems, 0, 0, afterCoverage, sources);
        List<String> notes = List.of(
                "Requirement totals differ between the runs: the ledger now splits compound "
                        + "requirements and merges aliases, so the denominators are not the same.",
                "Summaries: the previous run generated " + summariesGenerated + " and accepted "
                        + summariesAccepted + "; summary rewriting is now disabled.",
                "Skill ordering is deterministic in both runs and is shown per posting; it is "
                        + "not counted as an improvement by the model.");
        return new RewriteComparison(before, after, notes);
    }

    private static Side side(String label, List<Item> items, int summariesGenerated,
            int summariesAccepted, List<Coverage> coverage, ResumeSources sources) {
        Set<String> known = new HashSet<>();
        sources.all().forEach(i -> known.add(i.id()));

        int accepted = 0;
        int unchanged = 0;
        int rejected = 0;
        int failed = 0;
        int idErrors = 0;
        int failing = 0;
        int metrics = 0;
        int kept = 0;
        Map<String, Long> recorded = new TreeMap<>();
        Map<String, Long> current = new TreeMap<>();
        Map<String, Long> quality = new TreeMap<>();
        for (Item item : items) {
            switch (item.outcome()) {
                case "ACCEPTED" -> accepted++;
                case "UNCHANGED" -> unchanged++;
                case "MODEL_FAILED" -> failed++;
                default -> rejected++;
            }
            if (item.issueTypes().contains("UNKNOWN_SOURCE")) {
                idErrors++;
            }
            item.issueTypes().forEach(t -> recorded.merge(t, 1L, Long::sum));
            if (item.generated() != null) {
                metrics += item.metrics();
                kept += item.metricsKept();
            }
            quality.merge(RewriteQuality.classify(item.outcome(), item.original(), item.generated(),
                    item.targetsBefore(), item.targetsAfter(), item.wordsBefore(),
                    item.wordsAfter()).name(), 1L, Long::sum);

            if ("ACCEPTED".equals(item.outcome()) && item.sourceId() != null) {
                Optional<EvidenceScope> scope = EvidenceScope.forBullet(sources, item.sourceId());
                if (scope.isPresent()) {
                    ResumeClaimValidator.Verdict verdict = ResumeClaimValidator.check(
                            item.generated(), new RewriteRequest(item.sourceId(), item.original(),
                                    scope.get(), List.of(), List.of(), List.of(), Integer.MAX_VALUE),
                            known);
                    if (!verdict.pass()) {
                        failing++;
                        verdict.issues().forEach(i -> current.merge(i.type().name(), 1L, Long::sum));
                    }
                }
            }
        }
        return new Side(label, items.size(), accepted, unchanged, rejected, failed, idErrors,
                recorded, failing, current, quality, kept, metrics, summariesGenerated,
                summariesAccepted, List.copyOf(coverage));
    }

    private static Item item(JsonNode node) {
        List<String> issues = new ArrayList<>();
        node.path("issues").forEach(i -> issues.add(i.path("type").asText()));
        return new Item(
                node.hasNonNull("sourceId") ? node.get("sourceId").asText() : null,
                node.path("original").asText(""),
                node.hasNonNull("generated") ? node.get("generated").asText() : null,
                node.path("outcome").asText(""),
                issues,
                node.path("targetsNamedBefore").asInt(),
                node.path("targetsNamedAfter").asInt(),
                node.path("postingWordsBefore").asInt(),
                node.path("postingWordsAfter").asInt(),
                node.path("sourceMetrics").asInt(),
                node.path("sourceMetricsKept").asInt());
    }

    private static Item item(ItemResult result) {
        return new Item(result.sourceId(), result.original(), result.generated(),
                result.outcome().name(),
                result.issues().stream().map(i -> i.type().name()).toList(),
                result.targetsNamedBefore(), result.targetsNamedAfter(),
                result.postingWordsBefore(), result.postingWordsAfter(),
                result.sourceMetrics(), result.sourceMetricsKept());
    }

    private static Coverage coverage(long postingId, JsonNode det, JsonNode gen) {
        return new Coverage(postingId, det.path("requiredDirect").asInt(),
                det.path("requiredDirectShown").asInt(), gen.path("requiredDirectShown").asInt(),
                det.path("preferredDirect").asInt(), det.path("preferredDirectShown").asInt(),
                gen.path("preferredDirectShown").asInt(), det.path("strongDirectMatches").asInt(),
                gen.path("strongDirectMatches").asInt(),
                det.hasNonNull("requiredSkillRank") ? det.get("requiredSkillRank").asDouble() : null,
                gen.hasNonNull("requiredSkillRank") ? gen.get("requiredSkillRank").asDouble() : null);
    }

    private static Coverage coverage(PostingRun run) {
        var det = run.deterministicCoverage();
        var gen = run.generativeCoverage();
        return new Coverage(run.postingId(), det.requiredDirect(), det.requiredDirectShown(),
                gen.requiredDirectShown(), det.preferredDirect(), det.preferredDirectShown(),
                gen.preferredDirectShown(), det.strongDirectMatches(), gen.strongDirectMatches(),
                det.requiredSkillRank(), gen.requiredSkillRank());
    }

    public String markdown() {
        StringBuilder md = new StringBuilder("## Before / after\n\n")
                .append("| | Previous run | This run |\n|---|---:|---:|\n");
        row(md, "Bullet rewrites attempted", before.bullets(), after.bullets());
        row(md, "Accepted", before.accepted(), after.accepted());
        row(md, "Unchanged", before.unchanged(), after.unchanged());
        row(md, "Rejected", before.rejected(), after.rejected());
        row(md, "Model failures", before.modelFailed(), after.modelFailed());
        row(md, "Source-id errors", before.sourceIdErrors(), after.sourceIdErrors());
        row(md, "Accepted rewrites the current validator rejects",
                before.acceptedFailingCurrentValidator(), after.acceptedFailingCurrentValidator());
        for (String type : List.of("UNSUPPORTED_TECHNOLOGY", "INVENTED_NUMBER",
                "UNSUPPORTED_PRODUCT", "UNSUPPORTED_CLAIM", "INFLATED_RESPONSIBILITY",
                "QUALIFIER_LOST", "KEYWORD_STUFFING")) {
            row(md, "…of which " + type, before.currentValidatorIssues().getOrDefault(type, 0L),
                    after.currentValidatorIssues().getOrDefault(type, 0L));
        }
        for (RewriteQuality.Label label : RewriteQuality.Label.values()) {
            row(md, "Label " + label, before.quality().getOrDefault(label.name(), 0L),
                    after.quality().getOrDefault(label.name(), 0L));
        }
        md.append("| Source metrics kept | ").append(before.metricsKept()).append('/')
                .append(before.metricsTotal()).append(" | ").append(after.metricsKept()).append('/')
                .append(after.metricsTotal()).append(" |\n");
        row(md, "Summaries generated by the model", before.summariesGenerated(), after.summariesGenerated());
        row(md, "Summaries accepted", before.summariesAccepted(), after.summariesAccepted());

        md.append("\n### Coverage per posting (deterministic → generative)\n\n")
                .append("| Posting | Required DIRECT shown, previous | Required DIRECT shown, now "
                        + "| Preferred DIRECT shown, previous | now | Strong matches, previous | now "
                        + "| Required-skill rank, previous | now (deterministic ordering) |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (Coverage b : before.coverage()) {
            Coverage a = after.coverage().stream()
                    .filter(c -> c.postingId() != null && c.postingId().equals(b.postingId()))
                    .findFirst().orElse(null);
            md.append("| ").append(b.postingId())
                    .append(" | ").append(shown(b.requiredShownDeterministic(), b.requiredShownGenerative(), b.requiredDirect()))
                    .append(" | ").append(a == null ? "n/a" : shown(a.requiredShownDeterministic(), a.requiredShownGenerative(), a.requiredDirect()))
                    .append(" | ").append(shown(b.preferredShownDeterministic(), b.preferredShownGenerative(), b.preferredDirect()))
                    .append(" | ").append(a == null ? "n/a" : shown(a.preferredShownDeterministic(), a.preferredShownGenerative(), a.preferredDirect()))
                    .append(" | ").append(b.strongDeterministic()).append(" → ").append(b.strongGenerative())
                    .append(" | ").append(a == null ? "n/a" : a.strongDeterministic() + " → " + a.strongGenerative())
                    .append(" | ").append(rank(b.skillRankDeterministic())).append(" → ").append(rank(b.skillRankGenerative()))
                    .append(" | ").append(a == null ? "n/a" : rank(a.skillRankDeterministic()) + " → " + rank(a.skillRankGenerative()))
                    .append(" |\n");
        }
        md.append('\n');
        notes.forEach(note -> md.append("- ").append(note).append('\n'));
        return md.append('\n').toString();
    }

    private static void row(StringBuilder md, String label, long before, long after) {
        md.append("| ").append(label).append(" | ").append(before).append(" | ").append(after).append(" |\n");
    }

    private static String shown(int det, int gen, int total) {
        return det + "/" + total + " → " + gen + "/" + total;
    }

    private static String rank(Double rank) {
        return rank == null ? "n/a" : String.format("%.2f", rank);
    }
}
