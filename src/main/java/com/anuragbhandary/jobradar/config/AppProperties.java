package com.anuragbhandary.jobradar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything user-specific, bound from {@code application.yml}.
 *
 * <p>No path, key or spreadsheet id is ever hardcoded in Java source. Secrets
 * arrive through environment variables that the YAML references.
 */
@ConfigurationProperties(prefix = "job-radar")
public record AppProperties(Google google, String outputDir, Http http) {

    public record Google(String credentialsPath, String spreadsheetId, String sheetName) {

        /** Sheets config is optional at boot; only the Sheets commands require it. */
        public boolean isConfigured() {
            return spreadsheetId != null && !spreadsheetId.isBlank();
        }
    }

    /**
     * HTTP manners. These endpoints belong to other people and are being used
     * without an agreement, so the delay and user-agent are not optional extras.
     */
    public record Http(
            String userAgent,
            long delayBetweenRequestsMs,
            int timeoutSeconds,
            int maxRetries) {
    }
}
