package com.anuragbhandary.jobradar.evidence;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the evidence file is, and whether resumes are planned from it.
 *
 * @param path      the evidence file. Personal data, like applicant.yml, so it lives
 *                  outside the repository; evidence.example.yml is the committed
 *                  skeleton.
 * @param tailoring plan resumes from the bank when it is usable. Off, every resume
 *                  is tailored exactly as it was before the bank existed - the same
 *                  kind of switch-and-restart rollback as the knowledge resolver's.
 */
@ConfigurationProperties(prefix = "job-radar.evidence")
public record EvidenceProperties(String path, Boolean tailoring) {

    static final String DEFAULT_PATH = "~/.config/job-radar/evidence.yml";

    public boolean tailoringEnabled() {
        return tailoring == null || tailoring;
    }

    public Path file() {
        String configured = path == null || path.isBlank() ? DEFAULT_PATH : path.strip();
        if (configured.startsWith("~/")) {
            configured = System.getProperty("user.home") + configured.substring(1);
        }
        return Path.of(configured);
    }
}
