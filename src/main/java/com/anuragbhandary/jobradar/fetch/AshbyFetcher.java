package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * Reads an Ashby job board.
 *
 * <p>The easiest of the five: one request per board, and {@code descriptionPlain}
 * is already plain text, so no unescaping or tag stripping is needed.
 */
@Component
public class AshbyFetcher implements AtsFetcher {

    private static final String BOARD_URL =
            "https://api.ashbyhq.com/posting-api/job-board/%s";

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public AshbyFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.ASHBY;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        return FetchBatch.of(parse(http.get(BOARD_URL.formatted(boardToken), "ashby-" + boardToken), boardToken));
    }

    List<RawPosting> parse(String body, String boardToken) throws FetchException {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            throw new FetchException("Unparseable response from board " + boardToken, e);
        }
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) {
            throw new FetchException(
                    "Board " + boardToken + " returned no jobs array; token may be wrong");
        }

        List<RawPosting> postings = new ArrayList<>(jobs.size());
        for (JsonNode job : jobs) {
            // Ashby publishes drafts and internal roles through the same feed.
            if (job.has("isListed") && !job.path("isListed").asBoolean(true)) {
                continue;
            }
            String id = job.path("id").asText(null);
            String title = job.path("title").asText(null);
            if (id == null || title == null) {
                continue;
            }
            postings.add(new RawPosting(
                    id,
                    title.trim(),
                    locations(job),
                    job.path("descriptionPlain").asText(null),
                    job.path("jobUrl").asText(null),
                    parseDate(job.path("publishedAt").asText(null))));
        }
        return postings;
    }

    /**
     * Joins the primary and secondary locations.
     *
     * <p>A role open in San Francisco and Dublin lists only San Francisco in
     * {@code location}; dropping {@code secondaryLocations} would lose every
     * multi-site posting that happens to be listed against another city first.
     */
    private static String locations(JsonNode job) {
        StringJoiner joined = new StringJoiner("; ");
        String primary = job.path("location").asText(null);
        if (primary != null && !primary.isBlank()) {
            joined.add(primary);
        }
        for (JsonNode secondary : job.path("secondaryLocations")) {
            String location = secondary.path("location").asText(null);
            if (location != null && !location.isBlank()) {
                joined.add(location);
            }
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
