package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads a Workable account through its public widget API, which returns every
 * job with its full description in one request.
 *
 * <p>Added 2026-10-20 for the European startups that hire through Workable. The
 * token is the account handle in {@code apply.workable.com/<handle>}.
 *
 * <p>{@code telecommuting} is Workable's remote flag; like Recruitee's it sits
 * beside a real office, so it is appended to the location, not substituted.
 */
@Component
public class WorkableFetcher implements AtsFetcher {

    static final String BOARD_URL = "https://apply.workable.com/api/v1/widget/accounts/%s?details=true";

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public WorkableFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.WORKABLE;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        return FetchBatch.of(parse(http.get(BOARD_URL.formatted(boardToken), "workable-" + boardToken), boardToken));
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
            throw new FetchException("Board " + boardToken + " has no jobs array; token may be wrong");
        }
        List<RawPosting> postings = new ArrayList<>(jobs.size());
        for (JsonNode job : jobs) {
            String id = job.path("shortcode").asText(null);
            String title = job.path("title").asText(null);
            if (id == null || title == null || title.isBlank()) {
                continue;
            }
            postings.add(new RawPosting(id, title.strip(), locations(job),
                    Html.toPlainText(job.path("description").asText("")),
                    job.path("url").asText(null), date(job)));
        }
        return postings;
    }

    private static String locations(JsonNode job) {
        Set<String> all = new LinkedHashSet<>();
        for (JsonNode location : job.path("locations")) {
            if (location.path("hidden").asBoolean(false)) {
                continue;
            }
            String city = location.path("city").asText("");
            String country = location.path("country").asText("");
            String place = city.isBlank() ? country : country.isBlank() ? city : city + ", " + country;
            if (!place.isBlank()) {
                all.add(place);
            }
        }
        if (all.isEmpty()) {
            String city = job.path("city").asText("");
            String country = job.path("country").asText("");
            String place = city.isBlank() ? country : country.isBlank() ? city : city + ", " + country;
            if (!place.isBlank()) {
                all.add(place);
            }
        }
        if (job.path("telecommuting").asBoolean(false)) {
            all.add("Remote");
        }
        return all.isEmpty() ? null : String.join("; ", all);
    }

    private static LocalDate date(JsonNode job) {
        String value = job.path("published_on").asText(job.path("created_at").asText(""));
        try {
            return value.isBlank() ? null : LocalDate.parse(value.substring(0, Math.min(10, value.length())));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
