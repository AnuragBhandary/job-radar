package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.filter.YearsExtraction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

    /**
     * Past this, a posting is old enough to say so. Not a filter: an old
     * requisition at a company worth applying to is still worth applying to, and
     * this is the one field that says which of two identical-looking postings has
     * been sitting there since spring.
     */
    private static final int STALE_AFTER_DAYS = 60;

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

        section(out, "New candidates", digest.newCandidates(), labels, digest.date());
        section(out, "Updated postings", digest.updated(), labels, digest.date());
        section(out, "Needs human review — no years stated", digest.needsHumanReview(), labels, digest.date());

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

        if (digest.suppressedAlreadyApplied() > 0) {
            out.append("\n_")
                    .append(digest.suppressedAlreadyApplied())
                    .append(" candidate(s) hidden — already applied to that company._\n");
        }

        if (digest.duplicatesCollapsed() > 0) {
            out.append("\n_")
                    .append(digest.duplicatesCollapsed())
                    .append(" repeat listing(s) folded into a role already shown above._\n");
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
            Map<String, String> labels, LocalDate today) {
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
                    .append(" | ").append(age(p, today))
                    .append('\n');
            out.append("  Floor: ").append(salaries.floorFor(p)).append('\n');
            if (p.getSalaryText() != null) {
                out.append("  Stated: ").append(p.getSalaryText()).append('\n');
            }
            if (p.getSponsorshipSignal() != null) {
                out.append("  Visa: ").append(p.getSponsorshipSignal()).append('\n');
            }
            if (p.getUrl() != null) {
                out.append("  ").append(p.getUrl()).append('\n');
            }
            out.append('\n');
        }
    }

    /**
     * How old the posting is, and whether that is old enough to matter.
     *
     * <p>{@code posted_date} was captured from the first milestone and never
     * read. A requisition open since May is usually filled, on hold, or a
     * permanent advertisement for a pipeline - and it looks identical to
     * yesterday's in every other field.
     */
    private static String age(Posting p, LocalDate today) {
        LocalDate posted = p.getPostedDate();
        if (posted == null || today == null) {
            return "posted: unknown";
        }
        long days = ChronoUnit.DAYS.between(posted, today);
        if (days < 0) {
            return "posted: " + posted;
        }
        String age = days == 0 ? "today" : days + "d ago";
        return days >= STALE_AFTER_DAYS ? "posted: " + age + " — stale" : "posted: " + age;
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
