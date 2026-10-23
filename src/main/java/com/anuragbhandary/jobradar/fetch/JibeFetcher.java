package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads careers sites built on Jibe (iCIMS's candidate site), one country per board.
 *
 * <p>A Jibe site answers {@code /api/jobs?location=...&page=N&limit=100} with
 * JSON that carries the description, responsibilities and qualifications in
 * full, so the list is the whole read. AMD and Booking Holdings use it; their
 * robots.txt shuts nothing under {@code /api/}.
 *
 * <p><b>The token</b> is {@code name/host/location}: {@code amd/careers.amd.com/India}.
 * The location is the site's own search term, a country name. The name is what
 * the big-tech list matches.
 */
@Component
public class JibeFetcher implements AtsFetcher {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public JibeFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.JIBE;
    }

    /** {@code name/host/location}, as seeded. */
    record Board(String name, String host, String location) {

        static Board parse(String token) throws FetchException {
            String[] parts = token == null ? new String[0] : token.split("/", 3);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new FetchException("Jibe token must be name/host/location, got: " + token);
            }
            return new Board(parts[0], parts[1], parts[2]);
        }

        String page(int page) {
            return "https://%s/api/jobs?location=%s&page=%d&limit=%d".formatted(
                    host, URLEncoder.encode(location, StandardCharsets.UTF_8), page, PAGE_SIZE);
        }
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);
        List<RawPosting> postings = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<RawPosting> batch = parse(http.get(board.page(page),
                    page == 1 ? "jibe-" + board.name() + "-" + board.location() : null));
            postings.addAll(batch);
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }
        return FetchBatch.of(postings);
    }

    /** Split out from {@link #fetch} so tests can drive it from a saved fixture. */
    List<RawPosting> parse(String body) throws FetchException {
        JsonNode jobs;
        try {
            jobs = json.readTree(body).path("jobs");
        } catch (Exception e) {
            throw new FetchException("Unparseable Jibe response", e);
        }
        if (!jobs.isArray()) {
            throw new FetchException("No jobs array in Jibe response");
        }
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode wrapper : jobs) {
            RawPosting posting = toPosting(wrapper.path("data"));
            if (posting != null) {
                postings.add(posting);
            }
        }
        return postings;
    }

    private static RawPosting toPosting(JsonNode job) {
        String id = job.path("req_id").asText(job.path("slug").asText(null));
        String title = job.path("title").asText(null);
        if (id == null || id.isBlank() || title == null) {
            return null;
        }
        String place = job.path("full_location").asText("");
        if (place.isBlank()) {
            place = String.join(", ", job.path("city").asText(""), job.path("country").asText("")).trim();
        }
        // The canonical page, not apply_url: that one is iCIMS's login screen.
        String url = job.path("meta_data").path("canonical_url").asText(job.path("apply_url").asText(null));
        return new RawPosting(id, title.trim(), place, description(job), url,
                parseDate(job.path("posted_date").asText(null)));
    }

    private static String description(JsonNode job) {
        StringBuilder text = new StringBuilder();
        for (String field : List.of("description", "responsibilities", "qualifications")) {
            String part = Html.toPlainText(job.path(field).asText(""));
            if (!part.isBlank()) {
                text.append(text.isEmpty() ? "" : "\n\n").append(part);
            }
        }
        return text.toString();
    }

    /** "2026-09-08T10:51:00+0000": the date is the first ten characters. */
    private static LocalDate parseDate(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
