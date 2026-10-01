package com.anuragbhandary.jobradar.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Whether a job link still leads to an open role.
 *
 * <p>A board's listing is not enough. On 2026-09-25, three of eighteen links sent
 * for applying were dead by the time they were opened: Wayflyer had dropped the
 * role from Ashby, Flix's page answered 404, and Target's Workday page did not
 * exist - while all three were still in the local database as open.
 *
 * <p>Where a platform has an API for one job, that is asked, because the HTML
 * page of a closed job often still answers 200 (Ashby's is a JavaScript shell).
 * Everything else is fetched and read for the usual "no longer available"
 * wording. Anything inconclusive is {@code UNKNOWN}, never dead: a link is only
 * dropped on evidence.
 */
@Component
public class LinkChecker {

    public enum State { LIVE, DEAD, UNKNOWN }

    public record Result(State state, String reason) {
        public boolean dead() {
            return state == State.DEAD;
        }
    }

    private static final Pattern ASHBY = Pattern.compile(
            "jobs\\.ashbyhq\\.com/([^/?#]+)/([0-9a-f-]{36})");
    private static final Pattern GREENHOUSE = Pattern.compile(
            "(?:job-boards|boards)(?:\\.eu)?\\.greenhouse\\.io/([^/?#]+)/jobs/(\\d+)");
    private static final Pattern GREENHOUSE_EMBED = Pattern.compile("[?&]gh_jid=(\\d+)");
    private static final Pattern LEVER = Pattern.compile(
            "jobs\\.lever\\.co/([^/?#]+)/([0-9a-f-]{36})");
    private static final Pattern WORKDAY = Pattern.compile(
            "https://([^.]+)\\.(wd\\d+)\\.myworkday(?:jobs|site)\\.com/(?:[a-z]{2}-[A-Z]{2}/)?"
                    + "(?:recruiting/[^/]+/)?([^/]+)/job/(.+)$");

    private static final List<String> CLOSED_PHRASES = List.of(
            "no longer available", "no longer accepting applications", "no longer open",
            "position has been filled", "this job has expired", "job has been closed",
            "this job is closed", "job not found", "job posting is closed",
            "this position is closed", "the job you are looking for", "page you requested could not be found");

    private final HttpClient http;

    public LinkChecker(HttpClient http) {
        this.http = http;
    }

    /**
     * For a posting fetched from a platform with a per-job API, ask that API about
     * this exact job. Falls back to {@link #check(String)} for everything else.
     */
    public Result checkListing(com.anuragbhandary.jobradar.domain.Source source, String token,
            String externalId, String url) {
        try {
            return switch (source) {
                case GREENHOUSE -> byApi("https://boards-api.greenhouse.io/v1/boards/" + token
                        + "/jobs/" + externalId, "Greenhouse");
                case LEVER -> byApi("https://api.lever.co/v0/postings/" + token + "/"
                        + externalId, "Lever");
                case EIGHTFOLD -> eightfold(token, externalId);
                default -> check(url);
            };
        } catch (IOException e) {
            return new Result(State.UNKNOWN, "could not load: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(State.UNKNOWN, "interrupted");
        }
    }

    public Result check(String url) {
        if (url == null || !url.startsWith("http")) {
            return new Result(State.UNKNOWN, "no link");
        }
        try {
            Matcher m = ASHBY.matcher(url);
            if (m.find()) {
                Response r = get("https://api.ashbyhq.com/posting-api/job-board/" + m.group(1));
                if (r.status() != 200) {
                    return new Result(State.UNKNOWN, "Ashby board answered " + r.status());
                }
                return r.body().contains(m.group(2))
                        ? new Result(State.LIVE, "listed on Ashby")
                        : new Result(State.DEAD, "no longer listed on the Ashby board");
            }
            m = GREENHOUSE.matcher(url);
            if (m.find()) {
                return byApi("https://boards-api.greenhouse.io/v1/boards/" + m.group(1)
                        + "/jobs/" + m.group(2), "Greenhouse");
            }
            m = LEVER.matcher(url);
            if (m.find()) {
                return byApi("https://api.lever.co/v0/postings/" + m.group(1) + "/" + m.group(2),
                        "Lever");
            }
            m = WORKDAY.matcher(url);
            if (m.find()) {
                Response r = get("https://" + m.group(1) + "." + m.group(2)
                        + ".myworkdayjobs.com/wday/cxs/" + m.group(1) + "/" + m.group(3)
                        + "/job/" + m.group(4));
                if (!sameHost(r, "myworkdayjobs.com")) {
                    return new Result(State.UNKNOWN, "Workday redirected to " + r.finalUrl()
                            + " (maintenance)");
                }
                if (r.status() == 200) {
                    return new Result(State.LIVE, "open on Workday");
                }
                if (r.status() == 404 || r.status() == 410) {
                    return new Result(State.DEAD, "Workday has no such job");
                }
                return new Result(State.UNKNOWN, "Workday answered " + r.status()
                        + " (often weekend maintenance)");
            }
            return byPage(url);
        } catch (IOException e) {
            return new Result(State.UNKNOWN, "could not load: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(State.UNKNOWN, "interrupted");
        }
    }

    /**
     * An Eightfold job is open while the site's sitemap lists it, which is how the
     * fetch decides too. Its page is no evidence either way: NTT DATA's answered
     * 404 for a listed job (2026-10-01), and the job API still answers for jobs
     * long closed.
     */
    private Result eightfold(String token, String externalId) throws IOException, InterruptedException {
        String[] parts = token == null ? new String[0] : token.split("/");
        if (parts.length != 3) {
            return new Result(State.UNKNOWN, "not an Eightfold token: " + token);
        }
        Response r = get("https://%s/careers/sitemap.xml?domain=%s".formatted(parts[1], parts[2]));
        if (r.status() != 200 || !r.body().contains("<loc>")) {
            return new Result(State.UNKNOWN, "Eightfold sitemap answered " + r.status());
        }
        return r.body().contains("/careers/job/" + externalId)
                ? new Result(State.LIVE, "listed on the Eightfold sitemap")
                : new Result(State.DEAD, "no longer in the Eightfold sitemap");
    }

    private Result byApi(String apiUrl, String platform) throws IOException, InterruptedException {
        Response r = get(apiUrl);
        if (!sameHost(r, URI.create(apiUrl).getHost())) {
            return new Result(State.UNKNOWN, platform + " redirected to " + r.finalUrl());
        }
        if (r.status() == 200) {
            return new Result(State.LIVE, "open on " + platform);
        }
        if (r.status() == 404 || r.status() == 410) {
            return new Result(State.DEAD, platform + " has no such job");
        }
        return new Result(State.UNKNOWN, platform + " answered " + r.status());
    }

    private Result byPage(String url) throws IOException, InterruptedException {
        Response r = get(url);
        String finalUrl = r.finalUrl().toLowerCase(Locale.ROOT);
        if (r.status() == 404 || r.status() == 410) {
            return new Result(State.DEAD, "page answered " + r.status());
        }
        if (finalUrl.contains("maintenance")) {
            return new Result(State.UNKNOWN, "site in maintenance");
        }
        if (finalUrl.contains("nojob") || finalUrl.contains("error=true")
                || finalUrl.contains("job-not-found")) {
            return new Result(State.DEAD, "redirected to " + r.finalUrl());
        }
        // A Greenhouse embed on the employer's own site: ask Greenhouse about the id.
        Matcher embed = GREENHOUSE_EMBED.matcher(url);
        if (embed.find() && r.status() == 200) {
            String text = visibleText(r.body());
            for (String phrase : CLOSED_PHRASES) {
                if (text.contains(phrase)) {
                    return new Result(State.DEAD, "page says \"" + phrase + "\"");
                }
            }
            return new Result(State.LIVE, "page loads");
        }
        if (r.status() / 100 != 2) {
            return new Result(State.UNKNOWN, "page answered " + r.status());
        }
        String text = visibleText(r.body());
        for (String phrase : CLOSED_PHRASES) {
            if (text.contains(phrase)) {
                return new Result(State.DEAD, "page says \"" + phrase + "\"");
            }
        }
        return new Result(State.LIVE, "page loads");
    }

    /**
     * Whether the response came from the host asked. Redirects are followed, so a
     * maintenance page on another site answers 200 - which must not read as open.
     */
    private static boolean sameHost(Response r, String hostSuffix) {
        String host = URI.create(r.finalUrl()).getHost();
        return host != null && host.endsWith(hostSuffix);
    }

    /** Text outside scripts and styles, so a bundled "404" string does not count. */
    static String visibleText(String html) {
        return html.replaceAll("(?is)<script.*?</script>|<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private record Response(int status, String body, String finalUrl) {
    }

    private Response get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (Macintosh) job-radar link check")
                .GET()
                .build();
        CompletableFuture<HttpResponse<String>> pending =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            HttpResponse<String> response = pending.get(40, TimeUnit.SECONDS);
            return new Response(response.statusCode(), response.body(), response.uri().toString());
        } catch (java.util.concurrent.TimeoutException e) {
            pending.cancel(true);
            throw new IOException("no response within 40s");
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        }
    }
}
