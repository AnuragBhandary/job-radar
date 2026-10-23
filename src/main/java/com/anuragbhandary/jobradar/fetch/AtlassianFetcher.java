package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads Atlassian's careers listing, one board for the whole company.
 *
 * <p>Atlassian hires through iCIMS, but its own careers page is filled from one
 * JSON endpoint that returns every open job (363 on 2026-10-23) with the
 * overview, responsibilities and qualifications in full. robots.txt shuts only
 * the {@code /company/careers/detail/} pages, which this never reads.
 *
 * <p>A job lists several places ("Bengaluru - India - Bengaluru, 560071 India",
 * "Remote - India - Remote"); they are joined with "; " for the geography filter.
 * The token is ignored: there is one board, seeded as {@code atlassian}.
 */
@Component
public class AtlassianFetcher implements AtsFetcher {

    private static final String LISTINGS_URL = "https://www.atlassian.com/endpoint/careers/listings";

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public AtlassianFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.ATLASSIAN;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        return FetchBatch.of(parse(http.get(LISTINGS_URL, "atlassian")));
    }

    /** Split out from {@link #fetch} so tests can drive it from a saved fixture. */
    List<RawPosting> parse(String body) throws FetchException {
        JsonNode jobs;
        try {
            jobs = json.readTree(body);
        } catch (Exception e) {
            throw new FetchException("Unparseable Atlassian listings", e);
        }
        if (!jobs.isArray()) {
            throw new FetchException("Atlassian listings is not an array");
        }
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            RawPosting posting = toPosting(job);
            if (posting != null) {
                postings.add(posting);
            }
        }
        return postings;
    }

    private static RawPosting toPosting(JsonNode job) {
        String id = job.path("id").asText(null);
        String title = job.path("title").asText(null);
        if (id == null || id.isBlank() || title == null) {
            return null;
        }
        List<String> places = new ArrayList<>();
        for (JsonNode place : job.path("locations")) {
            places.add(place.asText("").replaceAll("\\s+", " ").trim());
        }
        String url = job.path("portalJobPost").path("portalUrl").asText(job.path("applyUrl").asText(null));
        return new RawPosting(
                id,
                title.trim(),
                String.join("; ", places),
                description(job),
                url,
                parseDate(job.path("portalJobPost").path("updatedDate").asText(null)));
    }

    private static String description(JsonNode job) {
        StringBuilder text = new StringBuilder();
        for (String field : List.of("overview", "responsibilities", "qualifications")) {
            String part = Html.toPlainText(job.path(field).asText(""));
            if (!part.isBlank()) {
                text.append(text.isEmpty() ? "" : "\n\n").append(part);
            }
        }
        return text.toString();
    }

    /** "2026-10-23 06:28 AM": the date is the first ten characters. */
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
