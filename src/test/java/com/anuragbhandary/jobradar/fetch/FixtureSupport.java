package com.anuragbhandary.jobradar.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads a saved API response.
 *
 * <p>Every fixture in this project is a trimmed copy of a real response captured
 * by {@link FixtureRecorder} during a live run - never hand-written JSON. Real
 * responses carry the awkward details that matter: a trailing space in a title,
 * a date with a double space in it, an unlisted draft in the middle of a feed.
 */
final class FixtureSupport {

    private FixtureSupport() {
    }

    static String load(String name) {
        try (InputStream in = FixtureSupport.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
