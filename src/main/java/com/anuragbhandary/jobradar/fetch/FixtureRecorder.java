package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.config.AppProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes every raw API response to disk, exactly as received.
 *
 * <p>Not in the original spec, and it earns its place twice over. It supplies
 * test fixtures for free - the alternative is hand-writing JSON that only
 * resembles what a board really returns - and it makes "why was this posting
 * rejected?" answerable after the fact, from the bytes the decision was actually
 * made on, without going back to the board.
 *
 * <p>Recording never fails a fetch. A full disk or an unwritable directory is
 * logged and ignored; losing a debugging aid must not cost the run.
 */
@Component
public class FixtureRecorder {

    private static final Logger log = LoggerFactory.getLogger(FixtureRecorder.class);

    private final AppProperties.Fixtures config;

    public FixtureRecorder(AppProperties properties) {
        this.config = properties.fixtures();
    }

    /**
     * Files a response under {@code <dir>/<yyyy-MM-dd>/<name>.json}.
     *
     * @param name a stable identifier such as {@code greenhouse-stripe}, or null
     *             to skip recording entirely
     */
    public void record(String name, String body) {
        if (!config.enabled() || name == null || body == null) {
            return;
        }
        try {
            Path dir = Path.of(config.dir(), LocalDate.now().toString());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".json"), body, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not record fixture {}: {}", name, e.getMessage());
        }
    }
}
