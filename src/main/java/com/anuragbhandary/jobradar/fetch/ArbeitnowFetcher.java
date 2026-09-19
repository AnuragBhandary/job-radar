package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads Arbeitnow's free public job API, which is mostly Germany.
 *
 * <p>Newest first, 250 a page. Four pages are read, which is the newest thousand
 * postings; older ones drop out and close, which is what a posting that old on
 * this board usually is. Many are written in German, and screening rejects those,
 * so expect a small yield from a large page.
 */
@Component
public class ArbeitnowFetcher implements AtsFetcher {

    public static final String BOARD = "arbeitnow";

    private static final String API = "https://www.arbeitnow.com/api/job-board-api?page=%d";
    private static final int PAGES = 4;

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public ArbeitnowFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.ARBEITNOW;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        List<RawPosting> all = new ArrayList<>();
        for (int page = 1; page <= PAGES; page++) {
            List<RawPosting> batch = parse(http.get(API.formatted(page), "arbeitnow-" + page));
            all.addAll(batch);
            if (batch.isEmpty()) {
                break;
            }
        }
        return FetchBatch.of(all);
    }

    List<RawPosting> parse(String body) throws FetchException {
        JsonNode data;
        try {
            data = json.readTree(body).path("data");
        } catch (Exception e) {
            throw new FetchException("Unparseable response from Arbeitnow", e);
        }
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : data) {
            String slug = job.path("slug").asText(null);
            String title = job.path("title").asText(null);
            if (slug == null || title == null) {
                continue;
            }
            String company = job.path("company_name").asText("");
            String location = job.path("location").asText("");
            boolean remote = job.path("remote").asBoolean(false);
            long created = job.path("created_at").asLong(0);
            postings.add(new RawPosting(slug,
                    title.strip() + (company.isBlank() ? "" : " @ " + company.strip()),
                    (remote ? "Remote, " : "") + location,
                    Html.toPlainText(job.path("description").asText(null)),
                    job.path("url").asText(null),
                    created <= 0 ? null
                            : Instant.ofEpochSecond(created).atZone(ZoneOffset.UTC).toLocalDate()));
        }
        return postings;
    }
}
