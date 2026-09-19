package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.digest.Digest;
import com.anuragbhandary.jobradar.digest.DigestService;
import com.anuragbhandary.jobradar.digest.DigestWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code digest} writes today's handoff file; {@code export --since=YYYY-MM-DD}
 * writes one covering every open candidate first seen since that day.
 *
 * <p>Only a summary is printed. The file carries a trimmed description per
 * posting and is meant to be read whole, not scrolled past in a terminal.
 */
@Component
public class DigestCommand {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter STAMP_READABLE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final DigestService service;
    private final DigestWriter writer;
    private final String outputDir;

    public DigestCommand(DigestService service, DigestWriter writer,
            com.anuragbhandary.jobradar.config.AppProperties properties) {
        this.service = service;
        this.writer = writer;
        this.outputDir = properties.outputDir();
    }

    public void run(Map<String, String> options) {
        write(service.build(LocalDate.now()));
    }

    /**
     * {@code openings} - everything that became worth reviewing since the last
     * review, and the shortlist still waiting for an application.
     *
     * <p>The window starts where the last reviewed one ended. {@code --done}
     * closes the window at the moment the last file was written, not at the
     * moment of the call, so a posting fetched during a review is not skipped.
     * With no review on record the window is the last seven days.
     */
    public void openings(Map<String, String> options) {
        Path dir = Path.of(outputDir);
        Path reviewed = dir.resolve(".openings-reviewed");
        Path pending = dir.resolve(".openings-pending");
        try {
            Files.createDirectories(dir);
            if (options.containsKey("done")) {
                if (!Files.exists(pending)) {
                    System.out.println("No openings file to close. Run openings first.");
                    return;
                }
                Files.move(pending, reviewed, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Review recorded up to " + read(reviewed) + ".");
                return;
            }
            Instant since = options.containsKey("since")
                    ? LocalDate.parse(options.get("since")).atStartOfDay(ZoneId.systemDefault()).toInstant()
                    : Files.exists(reviewed) ? Instant.parse(read(reviewed))
                    : Instant.now().minus(7, ChronoUnit.DAYS);
            Instant now = Instant.now();
            Digest digest = service.buildOpenings(LocalDate.now(), since);
            String name = "openings-" + STAMP.format(now) + ".md";
            Path file = writer.write(digest, name,
                    "job-radar openings — new since " + STAMP_READABLE.format(since));
            Files.writeString(pending, now.toString(), StandardCharsets.UTF_8);
            System.out.printf("%n%d new candidate(s) since %s, %d shortlisted not yet applied.%n",
                    digest.candidates().size(), STAMP_READABLE.format(since),
                    digest.shortlisted().size());
            System.out.println("Openings file: " + file.toAbsolutePath());
            System.out.println("After reviewing: openings --done");
        } catch (IOException | DateTimeParseException e) {
            System.out.println("Could not build openings: " + e.getMessage());
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).strip();
    }

    public void export(Map<String, String> options) {
        String since = options.get("since");
        if (since == null) {
            System.out.println("Usage: export --since=YYYY-MM-DD");
            return;
        }
        try {
            write(service.buildSince(LocalDate.now(), LocalDate.parse(since)));
        } catch (DateTimeParseException e) {
            System.out.println("Not a date: " + since + " (expected YYYY-MM-DD)");
        }
    }

    private void write(Digest digest) {
        System.out.printf("%n%d candidate(s) to review", digest.candidates().size());
        if (!digest.isExport()) {
            long rejected = digest.rejections().values().stream().mapToLong(Long::longValue).sum();
            System.out.printf(", %d rejected on facts, %d closed", rejected, digest.closed().size());
        }
        System.out.println(".");
        try {
            Path file = writer.write(digest);
            System.out.println("Handoff file: " + file.toAbsolutePath());
        } catch (IOException e) {
            // Printed rather than lost, since nothing else holds this output.
            System.out.println("Could not write the handoff file: " + e.getMessage());
            System.out.println(writer.render(digest));
        }
    }
}
