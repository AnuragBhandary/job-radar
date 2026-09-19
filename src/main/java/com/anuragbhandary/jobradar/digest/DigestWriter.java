package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.filter.YearsExtraction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Renders the handoff file and writes it to disk.
 *
 * <p>The reader is a Claude session that judges each posting against the resume,
 * so every entry carries its id (what {@code mark} takes), the facts screening
 * worked from, and a trimmed description. The header comes first and says when
 * the data was fetched: a file that is three days old should say so before
 * anyone reads thirty postings from it.
 */
@Component
public class DigestWriter {

    /**
     * Past this, a posting is old enough to say so. Not a filter: an old
     * requisition at a company worth applying to is still worth applying to.
     */
    private static final int STALE_AFTER_DAYS = 60;

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AppProperties properties;
    private final SalaryFloorAdvisor salaries;

    public DigestWriter(AppProperties properties, SalaryFloorAdvisor salaries) {
        this.properties = properties;
        this.salaries = salaries;
    }

    /**
     * Writes {@code <output-dir>/YYYY-MM-DD.md}, or
     * {@code <output-dir>/since-YYYY-MM-DD.md} for an export.
     *
     * @return the file written
     */
    public Path write(Digest digest) throws IOException {
        Path dir = Path.of(properties.outputDir());
        Files.createDirectories(dir);
        String name = digest.isExport()
                ? "since-" + digest.since() + ".md"
                : digest.date() + ".md";
        Path file = dir.resolve(name);
        Files.writeString(file, render(digest), StandardCharsets.UTF_8);
        return file;
    }

    public String render(Digest digest) {
        // Board tokens are handles like "razorpaysoftwareprivatelimited". The
        // seeder carries a readable label for each; use it where there is one.
        Map<String, String> labels = digest.boards().stream()
                .filter(b -> b.getLabel() != null)
                .collect(Collectors.toMap(BoardToken::getToken, BoardToken::getLabel,
                        (a, b) -> a));

        StringBuilder out = new StringBuilder();
        out.append("# job-radar handoff — ").append(digest.date());
        if (digest.isExport()) {
            out.append(" (everything open since ").append(digest.since()).append(')');
        }
        out.append("\n\n");
        header(out, digest);

        if (digest.salaryFloorsNeedReverification()) {
            out.append("\n> **Salary floors need re-verification.** The Blue Card, CSEP and\n")
                    .append("> kennismigrant thresholds are re-indexed annually and the configured\n")
                    .append("> review date has passed. Check them at source before relying on them.\n");
        }

        if (digest.isQuiet()) {
            // Said plainly. A file that pads a quiet day with near-misses trains
            // the reader to stop reading it.
            out.append("\nNothing new to review.\n");
        } else {
            out.append("\n## Candidates (").append(digest.candidates().size()).append(")\n");
            for (Digest.Entry entry : digest.candidates()) {
                candidate(out, entry, labels, digest.date());
            }
        }

        if (!digest.closed().isEmpty()) {
            out.append("\n## Closed since the last fetch (").append(digest.closed().size())
                    .append(")\n");
            digest.closed().forEach(p -> out.append("- ").append(p.getId()).append(" · ")
                    .append(company(p, labels)).append(" · ").append(p.getTitle()).append('\n'));
        }

        if (!digest.rejections().isEmpty()) {
            long total = digest.rejections().values().stream().mapToLong(Long::longValue).sum();
            out.append("\n## Rejected on facts (").append(total).append(")\n");
            digest.rejections().forEach((reason, count) ->
                    out.append("- ").append(count).append(" — ").append(reason).append('\n'));
        }
        return out.toString();
    }

    /**
     * When the data is from, what was set aside, and whether the boards are healthy.
     *
     * <p>Every count of something withheld is printed even when it is small: a file
     * that quietly shrinks is one you stop trusting.
     */
    private static void header(StringBuilder out, Digest digest) {
        Instant lastFetch = digest.boards().stream()
                .map(BoardToken::getLastFetchedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        out.append("- Last fetch: ").append(lastFetch == null ? "never" : WHEN.format(lastFetch));
        if (lastFetch != null) {
            long days = ChronoUnit.DAYS.between(
                    lastFetch.atZone(ZoneId.systemDefault()).toLocalDate(), digest.date());
            if (days > 0) {
                out.append(" — **").append(days).append(" day(s) old; run `run` first**");
            }
        }
        out.append('\n');

        List<BoardToken> broken = digest.boards().stream().filter(BoardToken::isBroken).toList();
        List<BoardToken> empty = digest.boards().stream()
                .filter(b -> !b.isBroken())
                .filter(b -> b.getLastPostingCount() != null && b.getLastPostingCount() == 0)
                .toList();
        int total = digest.boards().stream()
                .mapToInt(b -> b.getLastPostingCount() == null ? 0 : b.getLastPostingCount())
                .sum();
        out.append("- Boards: ").append(digest.boards().size()).append(" active, ")
                .append(total).append(" postings");
        if (!broken.isEmpty()) {
            out.append("; failing: ").append(broken.stream()
                    .map(b -> b.getSource() + "/" + b.getToken() + " (" + b.getLastError() + ")")
                    .collect(Collectors.joining(", ")));
        }
        if (!empty.isEmpty()) {
            // SmartRecruiters answers HTTP 200 with nothing for a company it has
            // never heard of, so a dead token and a company with no openings look
            // identical. Naming them is the only way the difference reaches a human.
            out.append("; returned nothing (token may be dead): ").append(empty.stream()
                    .map(b -> b.getSource() + "/" + b.getToken())
                    .collect(Collectors.joining(", ")));
        }
        out.append('\n');

        StringBuilder withheld = new StringBuilder();
        count(withheld, digest.alreadyDecided(), "already marked");
        count(withheld, digest.duplicatesCollapsed(), "repeat listings folded");
        count(withheld, digest.staleSetAside(), "stale");
        if (!withheld.isEmpty()) {
            out.append("- Withheld: ").append(withheld).append('\n');
        }
    }

    private static void count(StringBuilder out, int n, String what) {
        if (n <= 0) {
            return;
        }
        if (!out.isEmpty()) {
            out.append(", ");
        }
        out.append(n).append(' ').append(what);
    }

    private void candidate(StringBuilder out, Digest.Entry entry, Map<String, String> labels,
            LocalDate today) {
        Posting p = entry.posting();
        out.append("\n### ").append(p.getId()).append(" · ").append(company(p, labels))
                .append(" · ").append(p.getTitle());
        if (entry.updated()) {
            out.append(" _(description changed)_");
        }
        out.append('\n');
        if (entry.companyApplied()) {
            out.append("**Already applied to this company** (this role or another)\n");
        }

        out.append(p.getLocation() == null ? "?" : p.getLocation())
                .append(" · ").append(orDash(p.getCountryCode()))
                .append(" · ").append(p.getWorkMode() == null ? "work mode ?" : p.getWorkMode().name().toLowerCase(java.util.Locale.ROOT))
                .append(" · remote from: ").append(orDash(p.getRemoteEligibleFrom()))
                .append(" · lane: ").append(p.getStrategicClass() == null ? "-" : p.getStrategicClass().name())
                .append('\n');
        out.append("Years: ").append(years(p))
                .append(" · graduate signal: ").append(p.isGraduateSignal() ? "yes" : "no")
                .append(" · ").append(age(p, today))
                .append('\n');
        if (p.getSalaryText() != null) {
            out.append("Stated pay: ").append(p.getSalaryText()).append('\n');
        }
        out.append("Floor: ").append(salaries.floorFor(p)).append('\n');
        if (p.getSponsorshipSignal() != null) {
            out.append("Visa: ").append(p.getSponsorshipSignal()).append('\n');
        }
        if (p.getUrl() != null) {
            out.append(p.getUrl()).append('\n');
        }
        out.append("\n```text\n").append(DescriptionTrimmer.trim(p.getDescriptionText()))
                .append("\n```\n");
    }

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

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** Falls back to the raw token when the board has no label. */
    private static String company(Posting p, Map<String, String> labels) {
        return labels.getOrDefault(p.getBoardToken(), p.getBoardToken());
    }
}
