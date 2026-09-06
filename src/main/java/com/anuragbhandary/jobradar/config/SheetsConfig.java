package com.anuragbhandary.jobradar.config;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Sheets client from a service-account key.
 *
 * <p>The bean is created lazily and may legitimately be absent: fetch, screen and
 * digest all work without Sheets configured, and requiring a credential to run
 * them would make the common case depend on the rare one.
 *
 * <p>Nothing from the key file is ever logged. Failures report the path and the
 * reason, never the contents.
 */
@Configuration
public class SheetsConfig {

    private static final Logger log = LoggerFactory.getLogger(SheetsConfig.class);

    private static final String APPLICATION_NAME = "job-radar";

    @Bean
    public SheetsClientFactory sheetsClientFactory(AppProperties properties) {
        return new SheetsClientFactory(properties.google());
    }

    /** Defers credential loading until a command actually needs the sheet. */
    public static class SheetsClientFactory {

        private final AppProperties.Google config;
        private Sheets cached;

        SheetsClientFactory(AppProperties.Google config) {
            this.config = config;
        }

        /**
         * @throws IllegalStateException with an actionable message when Sheets is
         *                               not configured or the key cannot be read
         */
        public synchronized Sheets sheets() {
            if (cached != null) {
                return cached;
            }
            if (!config.isConfigured()) {
                throw new IllegalStateException(
                        "No spreadsheet configured. Set JOB_RADAR_SHEET_ID.");
            }
            Path key = expandHome(config.credentialsPath());
            try (FileInputStream in = new FileInputStream(key.toFile())) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(in)
                        .createScoped(List.of(SheetsScopes.SPREADSHEETS));
                cached = new Sheets.Builder(
                        GoogleNetHttpTransport.newTrustedTransport(),
                        GsonFactory.getDefaultInstance(),
                        new HttpCredentialsAdapter(credentials))
                        .setApplicationName(APPLICATION_NAME)
                        .build();
                log.info("Google Sheets client ready (key: {})", key);
                return cached;
            } catch (IOException | GeneralSecurityException e) {
                // The path is safe to report; the contents are not.
                throw new IllegalStateException(
                        "Could not load the Google service-account key at " + key
                                + ": " + e.getMessage(), e);
            }
        }
    }

    /** {@code ~} in a configured path is not expanded by the JVM. */
    static Path expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return Path.of(System.getProperty("user.home"), path.substring(1));
        }
        return Path.of(path == null ? "" : path);
    }
}
