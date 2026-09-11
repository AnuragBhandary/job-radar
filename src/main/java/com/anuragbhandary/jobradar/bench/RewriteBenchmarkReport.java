package com.anuragbhandary.jobradar.bench;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteQuality;
import com.anuragbhandary.jobradar.bench.ResumeRewriteBenchmark.Coverage;
import com.anuragbhandary.jobradar.bench.ResumeRewriteBenchmark.ItemResult;
import com.anuragbhandary.jobradar.bench.ResumeRewriteBenchmark.Outcome;
import com.anuragbhandary.jobradar.bench.ResumeRewriteBenchmark.PostingRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The rewrite benchmark as JSON for keeping and Markdown for reading side by side.
 *
 * <p>No single score. Every number here is a count someone can check against the
 * text printed beneath it, and the quality labels are mechanical - the report says
 * which rewrites are candidates for a person to judge, not which are good.
 *
 * @param comparison the previous run against this one, or null when there was no
 *                   previous run to compare with
 */
public record RewriteBenchmarkReport(
        Instant generatedAt,
        String model,
        Map<String, Object> settings,
        long totalMillis,
        long modelMillis,
        List<Posting> postings,
        RewriteComparison comparison) {

    /** @param pagesDeterministic null when the PDF could not be measured */
    public record Posting(PostingRun run, Integer pagesDeterministic, Integer pagesGenerative) {
    }

    public String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Resume rewrite benchmark (shadow mode)\n\n")
                .append("Generated ").append(generatedAt).append(" · model `").append(model)
                .append("` · total ").append(secs(totalMillis)).append(" (model ")
                .append(secs(modelMillis)).append(")\n\n");
        settings.forEach((k, v) -> md.append(k).append(" `").append(v).append("` "));
        md.append("\n\nNothing here is used by the application flow. The deterministic resume "
                        + "is still the real output; the generative one is a candidate built beside it.\n\n")
                .append("Summary: ").append(ResumeRewriteBenchmark.SUMMARY_POLICY).append(".\n\n");

        md.append("## Overview\n\n")
                .append("| Posting | Bullets accepted / unchanged / rejected / failed "
                        + "| Candidate improvements / cosmetic / unnatural / stuffed "
                        + "| Required DIRECT shown | Preferred DIRECT shown "
                        + "| Strong direct matches in bullets | Related-not-his terms introduced "
                        + "| Required-skill rank (deterministic ordering) | Pages | Time |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Posting posting : postings) {
            PostingRun run = posting.run();
            Coverage d = run.deterministicCoverage();
            Coverage g = run.generativeCoverage();
            List<String> introduced = new ArrayList<>(g.nonDirectTermsNamed());
            introduced.removeAll(d.nonDirectTermsNamed());
            md.append("| ").append(run.postingId()).append(' ').append(shorten(run.title(), 40))
                    .append(" | ").append(count(run.bullets(), Outcome.ACCEPTED)).append(" / ")
                    .append(count(run.bullets(), Outcome.UNCHANGED)).append(" / ")
                    .append(count(run.bullets(), Outcome.REJECTED)).append(" / ")
                    .append(count(run.bullets(), Outcome.MODEL_FAILED))
                    .append(" | ").append(label(run.bullets(), RewriteQuality.Label.CANDIDATE_IMPROVEMENT))
                    .append(" / ").append(label(run.bullets(), RewriteQuality.Label.COSMETIC))
                    .append(" / ").append(label(run.bullets(), RewriteQuality.Label.UNNATURAL))
                    .append(" / ").append(label(run.bullets(), RewriteQuality.Label.STUFFED))
                    .append(" | ").append(d.requiredDirectShown()).append('/').append(d.requiredDirect())
                    .append(" → ").append(g.requiredDirectShown()).append('/').append(g.requiredDirect())
                    .append(" | ").append(d.preferredDirectShown()).append('/').append(d.preferredDirect())
                    .append(" → ").append(g.preferredDirectShown()).append('/').append(g.preferredDirect())
                    .append(" | ").append(d.strongDirectMatches()).append(" → ").append(g.strongDirectMatches())
                    .append(" | ").append(introduced.isEmpty() ? "none" : String.join(", ", introduced))
                    .append(" | ").append(rank(d.requiredSkillRank())).append(" → ").append(rank(g.requiredSkillRank()))
                    .append(" | ").append(pages(posting.pagesDeterministic())).append(" → ")
                    .append(pages(posting.pagesGenerative()))
                    .append(" | ").append(secs(run.extractMillis())).append(" + ")
                    .append(secs(run.rewriteMillis())).append(" |\n");
        }

        List<ItemResult> all = new ArrayList<>();
        postings.forEach(p -> all.addAll(p.run().bullets()));
        List<ItemResult> generated = all.stream().filter(i -> i.generated() != null).toList();
        md.append("\n## Measurements over every bullet (").append(generated.size())
                .append(" of ").append(all.size()).append(" returned usable text)\n\n");
        Map<ResumeClaimValidator.IssueType, Long> byType = new TreeMap<>();
        all.forEach(i -> i.issues().forEach(issue -> byType.merge(issue.type(), 1L, Long::sum)));
        md.append("- Issues found, by type: ").append(byType.isEmpty() ? "none" : byType.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "))).append('\n');
        Map<RewriteQuality.Label, Long> labels = new TreeMap<>();
        all.forEach(i -> labels.merge(i.quality(), 1L, Long::sum));
        md.append("- Labels: ").append(labels.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "))).append('\n');
        md.append("- Source metrics kept: ")
                .append(generated.stream().mapToInt(ItemResult::sourceMetricsKept).sum()).append(" of ")
                .append(generated.stream().mapToInt(ItemResult::sourceMetrics).sum()).append('\n');
        md.append("- Average latency per rewrite: ").append(secs((long) generated.stream()
                .mapToLong(ItemResult::latencyMillis).average().orElse(0))).append("\n\n");

        if (comparison != null) {
            md.append(comparison.markdown());
        }
        for (Posting posting : postings) {
            section(md, posting);
        }
        return md.toString();
    }

    private static void section(StringBuilder md, Posting posting) {
        PostingRun run = posting.run();
        md.append("\n---\n\n## ").append(run.postingId()).append(" · ").append(run.title()).append("\n\n")
                .append("Why chosen: ").append(run.why()).append("  \nRequirements: ")
                .append(run.requirementSource()).append("\n\n");
        for (RequirementImportance importance : List.of(RequirementImportance.REQUIRED,
                RequirementImportance.PREFERRED)) {
            List<String> terms = run.ledger().entries().stream()
                    .filter(e -> e.requirement().importance() == importance)
                    .map(e -> e.requirement().term() + " " + e.level()
                            + (e.requirement().alternativeOf() == null ? ""
                                    : " (one of \"" + e.requirement().alternativeOf() + "\")"))
                    .toList();
            md.append("- ").append(importance).append(": ")
                    .append(terms.isEmpty() ? "none" : String.join(", ", terms)).append('\n');
        }

        md.append("\n### Summary\n\n").append(run.deterministic().summary().text())
                .append("\n\n(").append(run.summaryPolicy()).append(")\n");

        md.append("\n### Skills order (deterministic)\n\n- Before: ")
                .append(groups(run.deterministic().skills())).append("\n- After: ")
                .append(groups(run.generative().skills())).append('\n');

        md.append("\n### Bullets\n\nThe deterministic tailor never rewords, so its text is the "
                + "source text.\n\n");
        for (ItemResult bullet : run.bullets()) {
            md.append("#### ").append(bullet.sourceId()).append(" · ").append(bullet.outcome())
                    .append(" · ").append(bullet.quality()).append("\n\n")
                    .append("- Source / deterministic: ").append(bullet.original()).append('\n')
                    .append("- Generative: ").append(bullet.generated() == null ? "(nothing usable)"
                            : bullet.generated()).append('\n')
                    .append("- Validation: ").append(bullet.issues().isEmpty() ? "PASS" : "FAIL: "
                            + bullet.issues().stream().map(i -> i.type() + " - " + i.detail())
                                    .collect(Collectors.joining("; "))).append('\n');
            if (!bullet.warnings().isEmpty()) {
                md.append("- Warnings: ").append(String.join("; ", bullet.warnings())).append('\n');
            }
            md.append("- Targets: ").append(bullet.targets().isEmpty() ? "none"
                    : String.join(", ", bullet.targets()));
            if (!bullet.relatedNotHis().isEmpty()) {
                md.append(" · related, not his: ").append(String.join("; ", bullet.relatedNotHis()));
            }
            md.append("\n- Named ").append(bullet.targetsNamedBefore()).append(" → ")
                    .append(bullet.targetsNamedAfter()).append(" targets · posting words ")
                    .append(bullet.postingWordsBefore()).append(" → ").append(bullet.postingWordsAfter())
                    .append(String.format(" · retention %.0f%%", bullet.retention() * 100))
                    .append(" · metrics kept ").append(bullet.sourceMetricsKept()).append('/')
                    .append(bullet.sourceMetrics()).append(" · ").append(secs(bullet.latencyMillis()))
                    .append("\n\n");
        }
    }

    private static String groups(List<ResumeModel.SkillGroup> skills) {
        return skills.stream().map(g -> g.group() + " [" + String.join(", ", g.items()) + "]")
                .collect(Collectors.joining(" · "));
    }

    private static long count(List<ItemResult> items, Outcome outcome) {
        return items.stream().filter(i -> i.outcome() == outcome).count();
    }

    private static long label(List<ItemResult> items, RewriteQuality.Label label) {
        return items.stream().filter(i -> i.quality() == label).count();
    }

    private static String rank(Double rank) {
        return rank == null ? "n/a" : String.format("%.2f", rank);
    }

    private static String pages(Integer pages) {
        return pages == null ? "n/a" : pages.toString();
    }

    private static String secs(long millis) {
        return String.format("%.1fs", millis / 1000.0);
    }

    private static String shorten(String text, int max) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
