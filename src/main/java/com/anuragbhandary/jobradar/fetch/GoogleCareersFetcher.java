package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads Google's careers search for one country.
 *
 * <p>Google publishes no job API, but its search page is rendered on the server
 * with the results embedded as data: an {@code AF_initDataCallback} block keyed
 * {@code ds:1} holds every job on the page with its title, locations,
 * qualifications and responsibilities. One request reads twenty jobs in full,
 * so there is no detail fetch.
 *
 * <p>The data is positional arrays, not named fields, which is the fragile part.
 * If Google reshapes it, the parse fails loudly (no {@code ds:1}, or a job with
 * no id and title) rather than storing half-read postings.
 *
 * <p>The token is a location as Google's search takes it ({@code India},
 * {@code Ireland}), like Amazon's country codes. Only the early-career and
 * intern levels are read: the big-tech cap is one year, and Google's mid level
 * starts above it.
 */
@Component
public class GoogleCareersFetcher implements AtsFetcher {

    private static final String BASE = "https://www.google.com/about/careers/applications/jobs/results";

    private static final String SEARCH = BASE
            + "?location=%s&target_level=EARLY&target_level=INTERN_AND_APPRENTICE&page=%d";

    private static final Pattern RESULTS = Pattern.compile(
            "AF_initDataCallback\\(\\{key: 'ds:1', hash: '[^']*', data:(.*?), sideChannel: \\{\\}\\}\\);</script>",
            Pattern.DOTALL);

    /** Guards against a paging bug becoming an unbounded crawl: 500 jobs. */
    private static final int MAX_PAGES = 25;

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public GoogleCareersFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.GOOGLE;
    }

    /** One page of results: the jobs on it and the search's total. */
    record Page(List<RawPosting> postings, int total) {
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        String location = URLEncoder.encode(boardToken, StandardCharsets.UTF_8);
        List<RawPosting> postings = new ArrayList<>();
        int total = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            Page result = parse(http.get(SEARCH.formatted(location, page),
                    page == 1 ? "google-" + boardToken : null), boardToken);
            total = Math.max(total, result.total());
            postings.addAll(result.postings());
            if (result.postings().isEmpty() || postings.size() >= total) {
                break;
            }
        }
        return new FetchBatch(postings, total);
    }

    /** Split out so tests can drive it from a saved page. */
    Page parse(String html, String boardToken) throws FetchException {
        Matcher m = RESULTS.matcher(html);
        if (!m.find()) {
            throw new FetchException("No ds:1 results block on Google's page for " + boardToken);
        }
        JsonNode data;
        try {
            data = json.readTree(m.group(1));
        } catch (Exception e) {
            throw new FetchException("Unreadable ds:1 results block for " + boardToken, e);
        }
        JsonNode jobs = data.path(0);
        if (!jobs.isArray()) {
            // An empty search is [null, null, 0, 20] (Google UAE, 2026-10-01): no
            // jobs array and a total of zero, nothing wrong.
            if (data.path(2).asInt(-1) == 0) {
                return new Page(List.of(), 0);
            }
            throw new FetchException("No jobs array in Google's results for " + boardToken);
        }
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            postings.add(toPosting(job));
        }
        return new Page(postings, data.path(2).asInt(postings.size()));
    }

    /**
     * Field positions, as read from the page on 2026-10-01: 0 id, 1 title,
     * 3 responsibilities, 4 qualifications, 9 locations, 12 posted (epoch seconds).
     */
    private static RawPosting toPosting(JsonNode job) throws FetchException {
        String id = job.path(0).asText(null);
        String title = job.path(1).asText(null);
        if (id == null || title == null || id.isBlank() || title.isBlank()) {
            throw new FetchException("A Google job without an id or title; the page format changed");
        }
        List<String> locations = new ArrayList<>();
        job.path(9).forEach(l -> locations.add(l.path(0).asText()));
        long posted = job.path(12).path(0).asLong(0);
        return new RawPosting(
                id,
                title.trim(),
                String.join("; ", locations),
                description(job),
                BASE + "/" + id,
                posted > 0 ? EightfoldFetcher.dateOf(posted) : null);
    }

    /**
     * The qualifications and the responsibilities, without the "About the job"
     * blurb, for the reason {@link AmazonFetcher} gives: the years extractor
     * takes the smallest number it finds, and marketing prose is where stray
     * small numbers live. The qualifications are where Google states the years.
     */
    private static String description(JsonNode job) {
        String qualifications = Html.toPlainText(job.path(4).path(1).asText(""));
        String responsibilities = Html.toPlainText(job.path(3).path(1).asText(""));
        return (qualifications
                + (responsibilities.isBlank() ? "" : "\n\nResponsibilities: " + responsibilities))
                .trim();
    }
}
