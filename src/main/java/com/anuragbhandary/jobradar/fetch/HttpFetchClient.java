package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.config.AppProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single way this application talks to someone else's server.
 *
 * <p>Every board request goes through here so that the manners are enforced in
 * one place rather than remembered in five fetchers. These are public endpoints
 * belonging to other people, used without an agreement: the identifying
 * User-Agent and the delay between requests are the price of using them, not
 * tuning parameters.
 */
@Component
public class HttpFetchClient {

    private static final Logger log = LoggerFactory.getLogger(HttpFetchClient.class);

    private final HttpClient http;
    private final AppProperties.Http config;
    private final FixtureRecorder fixtures;

    /** Guards the inter-request delay; requests may originate from several threads. */
    private final Object throttleLock = new Object();

    private long lastRequestAtNanos;

    public HttpFetchClient(HttpClient http, AppProperties properties, FixtureRecorder fixtures) {
        this.http = http;
        this.config = properties.http();
        this.fixtures = fixtures;
    }

    /**
     * GETs a URL and returns the body, retrying transient failures.
     *
     * @param fixtureName where to file the raw response for later replay, or null
     *                    to skip recording
     * @throws FetchException when the request failed permanently, or returned a
     *                        status that is not going to improve on a retry
     */
    public String get(String url, String fixtureName) throws FetchException {
        IOException lastIoFailure = null;

        for (int attempt = 1; attempt <= config.maxRetries(); attempt++) {
            throttle();
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header("User-Agent", config.userAgent())
                                .header("Accept", "application/json")
                                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    fixtures.record(fixtureName, response.body());
                    return response.body();
                }
                if (isRetryable(status)) {
                    log.warn("{} returned {} (attempt {}/{})",
                            url, status, attempt, config.maxRetries());
                    backoff(attempt);
                    continue;
                }
                // 404 and friends: the token is wrong or the board is gone.
                // Retrying cannot help and would only be rude.
                throw new FetchException("HTTP " + status + " from " + url);

            } catch (IOException e) {
                lastIoFailure = e;
                log.warn("{} failed: {} (attempt {}/{})",
                        url, e.getMessage(), attempt, config.maxRetries());
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Interrupted fetching " + url, e);
            }
        }
        throw new FetchException(
                "Gave up on " + url + " after " + config.maxRetries() + " attempts",
                lastIoFailure);
    }

    /** 429 and 5xx may succeed later; everything else will not. */
    private static boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    /** Blocks until the configured gap since the previous request has elapsed. */
    private void throttle() throws FetchException {
        long delayMs = config.delayBetweenRequestsMs();
        if (delayMs <= 0) {
            return;
        }
        synchronized (throttleLock) {
            long waitMs = delayMs
                    - Duration.ofNanos(System.nanoTime() - lastRequestAtNanos).toMillis();
            if (waitMs > 0 && lastRequestAtNanos != 0) {
                sleep(waitMs);
            }
            lastRequestAtNanos = System.nanoTime();
        }
    }

    private void backoff(int attempt) throws FetchException {
        sleep(Math.min(1000L * (1L << (attempt - 1)), 8000L));
    }

    private static void sleep(long millis) throws FetchException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted while waiting between requests", e);
        }
    }
}
