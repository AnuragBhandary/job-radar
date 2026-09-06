package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.filter.YearsExtraction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Renders a digest as markdown and writes it to disk.
 *
 * <p>The brief is that a human scans it in under a minute, so it is short by
 * construction: candidates get three lines each, rejections are collapsed to
 * counts by reason, and boards are only named individually when something is
 * wrong with them.
 */
@Component
public class DigestWriter {

    private final AppProperties properties;
    private final SalaryFloorAdvisor salaries;

    public DigestWriter(AppProperties properties, SalaryFloorAdvisor salaries) {
        this.properties = properties;
        this.salaries = salaries;
    }

    /**
     * Writes {@code <output-dir>/YYYY-MM-DD.md}.
     *
     * @return the file written
     */
    public Path write(Digest digest) throws IOException {
        Path dir = Path.of(properties.outputDir());
        Files.createDirectories(dir);
        Path file = dir.resolve(digest.date() + ".md");
        Files.writeString(file, render(digest), StandardCharsets.UTF_8);
        return file;
    }

    public String render(Digest digest) {
        // Board tokens are handles like "razorpaysoftwareprivatelimited". The
        // seeder carries a readable label for each; use it where we have one.
        Map<String, String> labels = digest.boards().stream()
                .filter(b -> b.getLabel() != null)
                .collect(Collectors.toMap(BoardToken::getToken, BoardToken::getLabel,
                        (a, b) -> a));

        StringBuilder out = new StringBuilder();
        out.append("# job-radar — ").append(digest.date()).append('\n');

        if (digest.isQuiet()) {
            // Saying so plainly is the point. A digest that pads a quiet day
            // with near-misses trains you to stop reading it.
            out.append("\nNothing new today.\n");
        }

        if (digest.salaryFloorsNeedReverification()) {
            out.append("\n> **Salary floors need re-verification.** The Blue Card, CSEP and\n")
                    .append("> kennismigrant thresholds are re-indexed annually and the configured\n")
                    .append("> review date has passed. Check them at source before relying on them.\n");
        }

        section(out, "New candidates", digest.newCandidates(), labels);
        section(out, "Updated postings", digest.updated(), labels);
        section(out, "Needs human review — no years stated", digest.needsHumanReview(), labels);

        if (!digest.closed().isEmpty()) {
            out.append("\n## Closed (").append(digest.closed().size()).append(")\n");
            digest.closed().forEach(p -> out.append("- ").append(company(p, labels))
                    .append(" — ").append(p.getTitle()).append('\n'));
        }

        if (!digest.rejections().isEmpty()) {
            long total = digest.rejections().values().stream().mapToLong(Long::longValue).sum();
            out.append("\n## Rejected (").append(total).append(")\n");
            digest.rejections().forEach((reason, count) ->
                    out.append("- ").append(count).append(" — ").append(reason).append('\n'));
        }

        out.append("\n## Board health\n");
        boolean anyProblem = false;
        for (BoardToken board : digest.boards()) {
            if (board.getLastError() != null) {
                anyProblem = true;
                out.append("- **").append(board.getToken()).append("** — ")
                        .append(board.getLastPostingCount() == null
                                ? "never fetched successfully"
                                : "was " + board.getLastPostingCount() + " postings")
                        .append(", now failing: ").append(board.getLastError()).append('\n');
            }
        }
        // An empty board is not an error, and that is the problem. SmartRecruiters
        // answers HTTP 200 with totalFound 0 for a company it has never heard of,
        // so a dead token and a company with no openings look identical to the
        // fetcher. Naming them is the only way the difference reaches a human.
        List<BoardToken> empty = digest.boards().stream()
                .filter(b -> b.getLastError() == null)
                .filter(b -> b.getLastPostingCount() != null && b.getLastPostingCount() == 0)
                .toList();
        if (!empty.isEmpty()) {
            anyProblem = true;
            out.append("- ").append(empty.size())
                    .append(" boards returned nothing (token may be dead — verify by hand): ");
            out.append(empty.stream().map(b -> b.getSource() + "/" + b.getToken())
                    .collect(Collectors.joining(", "))).append('\n');
        }
        if (!anyProblem) {
            int total = digest.boards().stream()
                    .mapToInt(b -> b.getLastPostingCount() == null ? 0 : b.getLastPostingCount())
                    .sum();
            out.append("All ").append(digest.boards().size())
                    .append(" boards healthy — ").append(total).append(" postings.\n");
        }
        return out.toString();
    }

    private void section(StringBuilder out, String heading, List<Posting> postings,
            Map<String, String> labels) {
        if (postings.isEmpty()) {
            return;
        }
        out.append("\n## ").append(heading).append(" (").append(postings.size()).append(")\n\n");
        for (Posting p : postings) {
            out.append("- **").append(company(p, labels)).append("** — ").append(p.getTitle())
                    .append(" — ").append(p.getLocation() == null ? "?" : p.getLocation())
                    .append('\n');
            out.append("  Years: ").append(years(p))
                    .append(" | Graduate signal: ").append(p.isGraduateSignal() ? "yes" : "no")
                    .append('\n');
            out.append("  Floor: ").append(salaries.floorFor(p)).append('\n');
            if (p.getUrl() != null) {
                out.append("  ").append(p.getUrl()).append('\n');
            }
            out.append('\n');
        }
    }

    private static String years(Posting p) {
        Integer minYears = p.getMinYears();
        if (minYears == null || minYears == YearsExtraction.NONE_STATED) {
            return "none stated";
        }
        return minYears == 0 ? "0 (entry level)" : String.valueOf(minYears);
    }

    /** Falls back to the raw token when the board has no label. */
    private static String company(Posting p, Map<String, String> labels) {
        return labels.getOrDefault(p.getBoardToken(), p.getBoardToken());
    }
}
