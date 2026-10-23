package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads careers sites on Spire2Grow, an Indian ATS, one company per board.
 *
 * <p>The careers site (a Flutter app) asks {@code io.spire2grow.com} which
 * workspace its domain belongs to, then pages through
 * {@code requisition/_search} with that workspace in a {@code workspaceId}
 * header. Each requisition carries the full description, the places and the
 * required experience in months, so the list is the whole read. Myntra had 44
 * open on 2026-10-23.
 *
 * <p><b>The token</b> is {@code name/careers-host}: {@code myntra/jobs.myntra.com}.
 * A job's page is {@code https://<host>/jobs/<displayId>}.
 */
@Component
public class Spire2GrowFetcher implements AtsFetcher {

    private static final String API = "https://io.spire2grow.com/ies/v1/p";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public Spire2GrowFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.SPIRE2GROW;
    }

    /** {@code name/host}, as seeded. */
    record Board(String name, String host) {

        static Board parse(String token) throws FetchException {
            String[] parts = token == null ? new String[0] : token.split("/");
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new FetchException("Spire2Grow token must be name/host, got: " + token);
            }
            return new Board(parts[0], parts[1]);
        }
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);
        String workspace = http.get(API + "/workspaceId?domain=" + board.host(), null).trim();
        if (workspace.isEmpty() || workspace.contains("<")) {
            throw new FetchException("No Spire2Grow workspace for " + board.host());
        }
        List<RawPosting> postings = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String body = http.get(
                    API + "/requisition/_search?page=%d&size=%d&selectedSortOrder=desc&selectedSortField=postedOn"
                            .formatted(page, PAGE_SIZE),
                    page == 1 ? "spire2grow-" + board.name() : null,
                    Map.of("workspaceId", workspace));
            List<RawPosting> batch = parse(body, board.host());
            postings.addAll(batch);
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }
        return FetchBatch.of(postings);
    }

    /** Split out from {@link #fetch} so tests can drive it from a saved fixture. */
    List<RawPosting> parse(String body, String host) throws FetchException {
        JsonNode entities;
        try {
            entities = json.readTree(body).path("entities");
        } catch (Exception e) {
            throw new FetchException("Unparseable Spire2Grow response for " + host, e);
        }
        if (!entities.isArray()) {
            throw new FetchException("No entities in Spire2Grow response for " + host);
        }
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : entities) {
            String id = job.path("displayId").asText(null);
            String title = job.path("jobTitle").asText(null);
            if (id == null || id.isBlank() || title == null || title.isBlank()) {
                continue;
            }
            List<String> places = new ArrayList<>();
            for (JsonNode place : job.path("jobLocation")) {
                String name = place.path("fqLocationName").asText("").trim();
                if (!name.isEmpty()) {
                    places.add(name);
                }
            }
            postings.add(new RawPosting(id, title.trim(), String.join("; ", places),
                    description(job), "https://%s/jobs/%s".formatted(host, id), posted(job)));
        }
        return postings;
    }

    /** The stated experience, in years, ahead of the job text. */
    private static String description(JsonNode job) {
        String text = Html.toPlainText(job.path("jobDescription").asText(""));
        JsonNode months = job.path("requiredExperienceInMonths");
        if (months.path("from").isNumber()) {
            int from = months.path("from").asInt() / 12;
            String range = months.path("to").isNumber()
                    ? from + " - " + months.path("to").asInt() / 12 + " years"
                    : from + "+ years";
            return "Experience: " + range + "\n\n" + text;
        }
        return text;
    }

    private static LocalDate posted(JsonNode job) {
        JsonNode start = job.path("jobPosting").path("startDate");
        return start.isNumber()
                ? Instant.ofEpochMilli(start.asLong()).atZone(ZoneOffset.UTC).toLocalDate()
                : null;
    }
}
