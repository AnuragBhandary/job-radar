package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads Jobicy's public remote-jobs API.
 *
 * <p>One board, several queries: Asia-Pacific, and the backend, Python and Java
 * tags. They overlap, so results are merged on Jobicy's id and each job is
 * stored once. Five requests a run, inside the API's asked-for manners.
 *
 * <p>{@code jobGeo} is where the hire may sit ("Anywhere", "APAC", "USA"), so it
 * becomes a remote location for the classifier to read. {@code jobLevel} is put
 * in the title, because Jobicy often keeps seniority out of it and the title
 * filter can only reject what it can see.
 */
@Component
public class JobicyFetcher implements AtsFetcher {

    public static final String BOARD = "jobicy";

    private static final String API = "https://jobicy.com/api/v2/remote-jobs?count=50&";
    private static final List<String> QUERIES =
            List.of("geo=apac", "tag=backend", "tag=python", "tag=java", "tag=kafka");

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public JobicyFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.JOBICY;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Map<String, RawPosting> byId = new LinkedHashMap<>();
        for (String query : QUERIES) {
            for (RawPosting p : parse(http.get(API + query, "jobicy-" + query.replace('=', '-')))) {
                byId.putIfAbsent(p.externalId(), p);
            }
        }
        return FetchBatch.of(new ArrayList<>(byId.values()));
    }

    List<RawPosting> parse(String body) throws FetchException {
        JsonNode jobs;
        try {
            jobs = json.readTree(body).path("jobs");
        } catch (Exception e) {
            throw new FetchException("Unparseable response from Jobicy", e);
        }
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            String id = job.path("id").asText(null);
            String title = job.path("jobTitle").asText(null);
            if (id == null || title == null) {
                continue;
            }
            String level = job.path("jobLevel").asText("");
            String company = job.path("companyName").asText("");
            String fullTitle = title.strip()
                    + (level.isBlank() || level.equalsIgnoreCase("Any") ? "" : " (" + level + ")")
                    + (company.isBlank() ? "" : " @ " + company.strip());
            postings.add(new RawPosting(id, fullTitle,
                    "Remote, " + job.path("jobGeo").asText("unspecified"),
                    Html.toPlainText(job.path("jobDescription").asText(null)),
                    job.path("url").asText(null),
                    date(job.path("pubDate").asText(null))));
        }
        return postings;
    }

    private static LocalDate date(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
