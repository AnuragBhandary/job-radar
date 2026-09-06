package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.digest.Digest;
import com.anuragbhandary.jobradar.digest.DigestService;
import com.anuragbhandary.jobradar.digest.DigestWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code digest} - write today's markdown and print it. */
@Component
public class DigestCommand {

    private final DigestService service;
    private final DigestWriter writer;

    public DigestCommand(DigestService service, DigestWriter writer) {
        this.service = service;
        this.writer = writer;
    }

    public void run(Map<String, String> options) {
        Digest digest = service.build(LocalDate.now());
        String markdown = writer.render(digest);
        System.out.println();
        System.out.println(markdown);
        try {
            Path file = writer.write(digest);
            System.out.println("Written to " + file.toAbsolutePath());
        } catch (IOException e) {
            // The digest has already been printed, so the run is not wasted.
            System.out.println("Could not write the digest file: " + e.getMessage());
        }
    }
}
