package com.anuragbhandary.jobradar.bench;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator;
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
 * text printed beneath it.
 */
public record RewriteBenchmarkReport(
        Instant generatedAt,
        String model,
        Map<String, Object> settings,
        long totalMillis,
        long modelMillis,
        List<Posting> postings) {

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
                + "is still the real output; the generative one is a candidate built beside it.\n\n");

        md.append("## Overview\n\n")
                .append("| Posting | Bullets accepted / unchanged / rejected / failed | Summary "
                        + "| Required DIRECT shown | Preferred DIRECT shown "
                        + "| Strong direct matches in bullets/summary | Related-not-his terms introduced "
                        + "| Pages | Time (requirements + rewrites) |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
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
                    .append(" | ").append(run.summary().outcome())
                    .append(" | ").append(d.requiredDirectShown()).append('/').append(d.requiredDirect())
                    .append(" → ").append(g.requiredDirectShown()).append('/').append(g.requiredDirect())
                    .append(" | ").append(d.preferredDirectShown()).append('/').append(d.preferredDirect())
                    .append(" → ").append(g.preferredDirectShown()).append('/').append(g.preferredDirect())
                    .append(" | ").append(d.strongDirectMatches()).append(" → ").append(g.strongDirectMatches())
                    .append(" | ").append(introduced.isEmpty() ? "none" : String.join(", ", introduced))
                    .append(" | ").append(pages(posting.pagesDeterministic())).append(" → ")
                    .append(pages(posting.pagesGenerative()))
                    .append(" | ").append(secs(run.extractMillis())).append(" + ")
                    .append(secs(run.rewriteMillis())).append(" |\n");
        }

        List<ItemResult> all = new ArrayList<>();
        postings.forEach(p -> {
            all.addAll(p.run().bullets());
            all.add(p.run().summary());
        });
        List<ItemResult> generated = all.stream().filter(i -> i.generated() != null).toList();
        md.append("\n## Measurements over every generated item (").append(generated.size())
                .append(" of ").append(all.size()).append(" returned text)\n\n")
                .append("- Validation pass: ").append(generated.stream()
                        .filter(i -> i.outcome() != Outcome.REJECTED).count())
                .append(" (accepted ").append(count(all, Outcome.ACCEPTED)).append(", unchanged ")
                .append(count(all, Outcome.UNCHANGED)).append("), fail: ")
                .append(count(all, Outcome.REJECTED)).append(", model failures: ")
                .append(count(all, Outcome.MODEL_FAILED)).append('\n');
        Map<ResumeClaimValidator.IssueType, Long> byType = new TreeMap<>();
        all.forEach(i -> i.issues().forEach(issue -> byType.merge(issue.type(), 1L, Long::sum)));
        md.append("- Issues found, by type: ").append(byType.isEmpty() ? "none" : byType.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "))).append('\n');
        md.append("- Target requirements named, source → generated (summed): ")
                .append(generated.stream().mapToInt(ItemResult::targetsNamedBefore).sum()).append(" → ")
                .append(generated.stream().mapToInt(ItemResult::targetsNamedAfter).sum()).append('\n');
        md.append("- Posting words shared, source → generated (summed): ")
                .append(generated.stream().mapToInt(ItemResult::postingWordsBefore).sum()).append(" → ")
                .append(generated.stream().mapToInt(ItemResult::postingWordsAfter).sum()).append('\n');
        md.append("- Materially more job-specific (by the measure above): ")
                .append(generated.stream().filter(ItemResult::moreJobSpecific).count())
                .append(" of ").append(generated.size()).append('\n');
        md.append("- Source metrics kept: ")
                .append(generated.stream().mapToInt(ItemResult::sourceMetricsKept).sum()).append(" of ")
                .append(generated.stream().mapToInt(ItemResult::sourceMetrics).sum()).append('\n');
        md.append("- Average latency per rewrite: ").append(secs((long) generated.stream()
                .mapToLong(ItemResult::latencyMillis).average().orElse(0))).append("\n\n");

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
                    .map(e -> e.requirement().term() + " " + e.level()).toList();
            md.append("- ").append(importance).append(": ")
                    .append(terms.isEmpty() ? "none" : String.join(", ", terms)).append('\n');
        }

        md.append("\n### Summary\n\n");
        item(md, run.summary(), "Deterministic");

        md.append("\n### Skills order\n\n- Deterministic: ")
                .append(groups(run.deterministic().skills())).append("\n- Generative: ")
                .append(groups(run.generative().skills())).append('\n');

        md.append("\n### Bullets\n\nThe deterministic tailor never rewords, so its text is the "
                + "source text.\n\n");
        for (ItemResult bullet : run.bullets()) {
            md.append("#### ").append(bullet.sourceId()).append(" · ").append(bullet.outcome())
                    .append("\n\n");
            item(md, bullet, "Source / deterministic");
        }
    }

    private static void item(StringBuilder md, ItemResult item, String originalLabel) {
        md.append("- ").append(originalLabel).append(": ").append(item.original()).append('\n')
                .append("- Generative: ").append(item.generated() == null ? "(nothing usable)"
                        : item.generated()).append('\n')
                .append("- Validation: ").append(item.issues().isEmpty() ? "PASS" : "FAIL: "
                        + item.issues().stream().map(i -> i.type() + " - " + i.detail())
                                .collect(Collectors.joining("; "))).append('\n');
        if (!item.warnings().isEmpty()) {
            md.append("- Warnings: ").append(String.join("; ", item.warnings())).append('\n');
        }
        md.append("- Targets: ").append(item.targets().isEmpty() ? "none" : String.join(", ", item.targets()));
        if (!item.relatedNotHis().isEmpty()) {
            md.append(" · related, not his: ").append(String.join("; ", item.relatedNotHis()));
        }
        md.append("\n- Named ").append(item.targetsNamedBefore()).append(" → ")
                .append(item.targetsNamedAfter()).append(" targets · posting words ")
                .append(item.postingWordsBefore()).append(" → ").append(item.postingWordsAfter())
                .append(" · more job-specific: ").append(item.moreJobSpecific() ? "yes" : "no")
                .append(String.format(" · retention %.0f%%", item.retention() * 100))
                .append(" · metrics kept ").append(item.sourceMetricsKept()).append('/')
                .append(item.sourceMetrics()).append(" · ").append(secs(item.latencyMillis()))
                .append("\n\n");
    }

    private static String groups(List<ResumeModel.SkillGroup> skills) {
        return skills.stream().map(g -> g.group() + " [" + String.join(", ", g.items()) + "]")
                .collect(Collectors.joining(" · "));
    }

    private static long count(List<ItemResult> items, Outcome outcome) {
        return items.stream().filter(i -> i.outcome() == outcome).count();
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
