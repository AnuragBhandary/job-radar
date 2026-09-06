package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a Greenhouse job board.
 *
 * <p>{@code content=true} returns every description inline, so one board is one
 * request no matter how many postings it has. Fetching jobs individually would
 * be forty times the traffic for the same data.
 *
 * <p>The {@code content} field is entity-escaped HTML - {@code &lt;p&gt;} rather
 * than {@code <p>} - so it needs unescaping before tags can be stripped. See
 * {@link Html}.
 */
@Component
public class GreenhouseFetcher implements AtsFetcher {

    private static final Logger log = LoggerFactory.getLogger(GreenhouseFetcher.class);

    private static final String BOARD_URL =
            "https://boards-api.greenhouse.io/v1/boards/%s/jobs?content=true";

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public GreenhouseFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.GREENHOUSE;
    }

    @Override
    public List<RawPosting> fetch(String boardToken) throws FetchException {
        String body = http.get(
                BOARD_URL.formatted(boardToken), "greenhouse-" + boardToken);
        return parse(body, boardToken);
    }

    /** Split out from {@link #fetch} so tests can drive it from a saved fixture. */
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
            String externalId = job.path("id").asText(null);
            String title = job.path("title").asText(null);
            if (externalId == null || title == null) {
                // A posting with no id cannot be tracked across runs, so it is
                // worse than useless - it would look new every single day.
                log.warn("Skipping malformed posting on board {}", boardToken);
                continue;
            }
            postings.add(new RawPosting(
                    externalId,
                    title.trim(),
                    job.path("location").path("name").asText(null),
                    Html.toPlainText(job.path("content").asText(null)),
                    job.path("absolute_url").asText(null),
                    parseDate(job.path("first_published").asText(null))));
        }
        return postings;
    }

    /**
     * Greenhouse publishes an ISO offset timestamp. Only the date is kept - the
     * time of day is noise for a tool that runs once a morning.
     */
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
