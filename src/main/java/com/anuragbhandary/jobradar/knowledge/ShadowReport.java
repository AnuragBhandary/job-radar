package com.anuragbhandary.jobradar.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The shadow comparison, grouped by concept.
 *
 * <p>A single agreement percentage hides everything worth knowing. "82%" is the
 * same number whether the missing 18% is a formatting difference on a salary box
 * or a sponsorship answer that flipped, and only one of those should stop the
 * resolver from being trusted.
 *
 * <p>So the report is per concept and per category, and the number it leads with
 * is the count of regressions - which should be zero, and is the only figure a
 * decision hangs on.
 */
public record ShadowReport(
        int comparisons,
        int questions,
        int contexts,
        Map<String, Integer> coverage,
        Map<ShadowComparison.Verdict, Long> verdicts,
        Map<ShadowComparison.Category, Long> categories,
        List<ConceptSummary> byConcept,
        List<ShadowComparison> regressions) {

    /**
     * @param concept null for a question no concept was found for, which is
     *                itself worth seeing grouped
     */
    public record ConceptSummary(
            String concept,
            long total,
            long agree,
            long differ,
            long oldOnly,
            long newOnly,
            long blank,
            Map<ShadowComparison.Category, Long> categories,
            List<String> examples) {

        public boolean hasDifferences() {
            return differ > 0 || oldOnly > 0 || newOnly > 0;
        }
    }

    public static ShadowReport of(List<ShadowComparison> rows, int questions, int contexts,
            Map<String, Integer> coverage) {

        Map<String, List<ShadowComparison>> grouped = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> row.conceptId() == null ? "(unrecognised)" : row.conceptId(),
                        LinkedHashMap::new, Collectors.toList()));

        List<ConceptSummary> summaries = new ArrayList<>();
        grouped.forEach((concept, group) -> summaries.add(new ConceptSummary(
                concept,
                group.size(),
                count(group, ShadowComparison.Verdict.AGREE),
                count(group, ShadowComparison.Verdict.DIFFERENT_VALUE),
                count(group, ShadowComparison.Verdict.OLD_ONLY),
                count(group, ShadowComparison.Verdict.NEW_ONLY),
                count(group, ShadowComparison.Verdict.BOTH_BLANK),
                group.stream().collect(Collectors.groupingBy(ShadowComparison::category,
                        java.util.TreeMap::new, Collectors.counting())),
                group.stream()
                        .filter(ShadowComparison::isFinding)
                        .map(ShadowComparison::describe)
                        .distinct()
                        .limit(3)
                        .toList())));

        // Concepts with something to look at first, then the quiet ones.
        summaries.sort(Comparator
                .comparingLong((ConceptSummary s) -> s.differ() + s.oldOnly()).reversed()
                .thenComparing(ConceptSummary::concept));

        return new ShadowReport(rows.size(), questions, contexts, coverage,
                rows.stream().collect(Collectors.groupingBy(ShadowComparison::verdict,
                        java.util.TreeMap::new, Collectors.counting())),
                rows.stream().collect(Collectors.groupingBy(ShadowComparison::category,
                        java.util.TreeMap::new, Collectors.counting())),
                List.copyOf(summaries),
                rows.stream().filter(ShadowComparison::isRegression).distinct().toList());
    }

    private static long count(List<ShadowComparison> rows, ShadowComparison.Verdict verdict) {
        return rows.stream().filter(row -> row.verdict() == verdict).count();
    }

    /** The one number a decision hangs on. */
    public boolean isClean() {
        return regressions.isEmpty();
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        out.append(String.format(
                "%d comparisons: %d distinct real questions x %d real postings%n",
                comparisons, questions, contexts));
        out.append("Coverage: ");
        out.append(coverage.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .collect(Collectors.joining(", ")));
        out.append("\n\nBy verdict:\n");
        verdicts.forEach((verdict, count) ->
                out.append(String.format("  %6d  %s%n", count, verdict)));

        out.append("\nBy category:\n");
        categories.forEach((category, count) ->
                out.append(String.format("  %6d  %s%n", count, category)));

        out.append("\nBy concept:\n");
        for (ConceptSummary summary : byConcept) {
            if (!summary.hasDifferences()) {
                out.append(String.format("  %-34s %4d agree%n",
                        summary.concept(), summary.agree()));
                continue;
            }
            out.append(String.format("  %-34s %4d agree · %d differ · %d old only · "
                            + "%d new only%n",
                    summary.concept(), summary.agree(), summary.differ(),
                    summary.oldOnly(), summary.newOnly()));
            summary.categories().forEach((category, count) -> {
                if (category != ShadowComparison.Category.AGREEMENT) {
                    out.append(String.format("       %s: %d%n", category, count));
                }
            });
            summary.examples().forEach(example ->
                    out.append("       e.g. ").append(example).append('\n'));
        }

        out.append(regressions.isEmpty()
                ? "\nNo unexplained regressions.\n"
                : String.format("%n%d UNEXPLAINED REGRESSION(S) - the resolver must not "
                        + "become authoritative until these are understood:%n",
                        regressions.size()));
        regressions.forEach(row -> out.append("  ").append(row.describe()).append('\n'));
        return out.toString();
    }
}
