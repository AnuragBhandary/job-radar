package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.digest.Digest;
import com.anuragbhandary.jobradar.digest.DigestService;
import com.anuragbhandary.jobradar.digest.DigestWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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

    private final DigestService service;
    private final DigestWriter writer;

    public DigestCommand(DigestService service, DigestWriter writer) {
        this.service = service;
        this.writer = writer;
    }

    public void run(Map<String, String> options) {
        write(service.build(LocalDate.now()));
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
