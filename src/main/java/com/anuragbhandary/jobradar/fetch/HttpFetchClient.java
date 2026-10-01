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

    /**
     * One gap per site, not one for the whole application. The delay is a courtesy
     * to whoever runs the server; a request to Lever costs Greenhouse nothing, and
     * a single global gap made a run of ~150 boards take over ten minutes.
     */
    private final java.util.concurrent.ConcurrentMap<String, Pace> paces =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The last request time for one site. Its monitor serialises that site's requests. */
    private static final class Pace {
        long lastRequestAtNanos;
    }

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
        HttpResult result = getRaw(url, fixtureName);
        if (result.isSuccess()) {
            return result.body();
        }
        // 404 and friends: the token is wrong or the board is gone. Retrying
        // cannot help and would only be rude.
        throw new FetchException("HTTP " + result.status() + " from " + url);
    }

    /**
     * As {@link #get}, but returns the status instead of throwing on it.
     *
     * <p>For probing, where a 404 is the answer rather than a failure. Transient
     * problems - 429 and 5xx - are still retried before being reported.
     */
    public HttpResult getRaw(String url, String fixtureName) throws FetchException {
        return execute(url, null, fixtureName);
    }

    /**
     * POSTs a JSON body and returns the response body.
     *
     * <p>Only Workday needs this. Its job search is a POST with a paging body
     * rather than a query string, so a GET-only client cannot read it at all.
     * The manners are identical - same throttle, same retries, same User-Agent.
     */
    public String post(String url, String jsonBody, String fixtureName) throws FetchException {
        HttpResult result = execute(url, jsonBody, fixtureName);
        if (result.isSuccess()) {
            return result.body();
        }
        throw new FetchException("HTTP " + result.status() + " from " + url);
    }

    private HttpResult execute(String url, String jsonBody, String fixtureName)
            throws FetchException {
        IOException lastIoFailure = null;

        for (int attempt = 1; attempt <= config.maxRetries(); attempt++) {
            throttle(url);
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", config.userAgent())
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(config.timeoutSeconds()));
                if (jsonBody == null) {
                    request.GET();
                } else {
                    request.header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
                }

                // The request timeout only runs until the response headers
                // arrive. A body that stalls after them (seen on Lever, after
                // the Mac slept mid-run) blocked send() for hours and froze the
                // whole daily run, so the full exchange gets a hard deadline.
                HttpResponse<String> response;
                java.util.concurrent.CompletableFuture<HttpResponse<String>> pending =
                        http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
                try {
                    response = pending.get(config.timeoutSeconds() * 3L,
                            java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    pending.cancel(true);
                    throw new IOException("no complete response within "
                            + config.timeoutSeconds() * 3 + "s");
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof IOException io) {
                        throw io;
                    }
                    throw new IOException(e.getCause());
                }

                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    fixtures.record(fixtureName, response.body());
                    return new HttpResult(status, response.body());
                }
                if (isRetryable(status)) {
                    log.warn("{} returned {} (attempt {}/{})",
                            url, status, attempt, config.maxRetries());
                    backoff(attempt);
                    continue;
                }
                return new HttpResult(status, response.body());

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

    /** A response that reached us, whatever its status. */
    public record HttpResult(int status, String body) {

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        public boolean isAbsent() {
            return status == 404;
        }
    }

    /** 429 and 5xx may succeed later; everything else will not. */
    private static boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    /**
     * Blocks until the configured gap since the previous request to the same site
     * has elapsed. Holding the site's monitor while sleeping is deliberate: it
     * queues every other thread bound for that site behind this one.
     */
    private void throttle(String url) throws FetchException {
        String site = site(url);
        long delayMs = config.delayFor(site);
        if (delayMs <= 0) {
            return;
        }
        Pace pace = paces.computeIfAbsent(site, k -> new Pace());
        synchronized (pace) {
            long waitMs = delayMs
                    - Duration.ofNanos(System.nanoTime() - pace.lastRequestAtNanos).toMillis();
            if (waitMs > 0 && pace.lastRequestAtNanos != 0) {
                sleep(waitMs);
            }
            pace.lastRequestAtNanos = System.nanoTime();
        }
    }

    /**
     * The operator a request lands on: the last two labels of the host. Every
     * Workday tenant ({@code target.wd5.myworkdayjobs.com}) is one Workday, and
     * every Greenhouse board one Greenhouse, so each keeps the full delay.
     */
    static String site(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (host == null) {
            return "";
        }
        String[] labels = host.toLowerCase(java.util.Locale.ROOT).split("\\.");
        return labels.length <= 2 ? host
                : labels[labels.length - 2] + "." + labels[labels.length - 1];
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
