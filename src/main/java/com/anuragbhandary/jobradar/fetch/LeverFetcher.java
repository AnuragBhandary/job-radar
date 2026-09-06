package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * Reads a Lever job board.
 *
 * <p>Two things differ from the others. The response is a bare JSON array rather
 * than an object with a {@code jobs} key, so there is no envelope to validate
 * against - a wrong token gives an empty array, not an error.
 *
 * <p>And the description is split: {@code descriptionPlain} holds the opening
 * prose, while the requirements - the part screening actually depends on - live
 * in {@code lists[]} as HTML. Reading only {@code descriptionPlain} would mean
 * screening every Lever posting on its marketing copy.
 */
@Component
public class LeverFetcher implements AtsFetcher {

    private static final String BOARD_URL =
            "https://api.lever.co/v0/postings/%s?mode=json";

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public LeverFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.LEVER;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        return FetchBatch.of(parse(http.get(BOARD_URL.formatted(boardToken), "lever-" + boardToken), boardToken));
    }

    List<RawPosting> parse(String body, String boardToken) throws FetchException {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            throw new FetchException("Unparseable response from board " + boardToken, e);
        }
        if (!root.isArray()) {
            throw new FetchException(
                    "Board " + boardToken + " did not return an array; token may be wrong");
        }

        List<RawPosting> postings = new ArrayList<>(root.size());
        for (JsonNode job : root) {
            String id = job.path("id").asText(null);
            String title = job.path("text").asText(null);
            if (id == null || title == null) {
                continue;
            }
            postings.add(new RawPosting(
                    id,
                    title.trim(),
                    locations(job),
                    description(job),
                    job.path("hostedUrl").asText(null),
                    parseDate(job.path("createdAt"))));
        }
        return postings;
    }

    /** Opening prose plus every requirements list, flattened to plain text. */
    private static String description(JsonNode job) {
        StringBuilder text = new StringBuilder(job.path("descriptionPlain").asText(""));
        for (JsonNode list : job.path("lists")) {
            text.append("\n\n").append(list.path("text").asText(""))
                    .append('\n').append(Html.toPlainText(list.path("content").asText("")));
        }
        String additional = job.path("additionalPlain").asText("");
        if (!additional.isBlank()) {
            text.append("\n\n").append(additional);
        }
        return text.toString().trim();
    }

    private static String locations(JsonNode job) {
        JsonNode categories = job.path("categories");
        // allLocations is authoritative when present; location is the first of them.
        Set<String> all = new LinkedHashSet<>();
        String primary = categories.path("location").asText(null);
        if (primary != null && !primary.isBlank()) {
            all.add(primary);
        }
        for (JsonNode location : categories.path("allLocations")) {
            String value = location.asText(null);
            if (value != null && !value.isBlank()) {
                all.add(value);
            }
        }
        if (all.isEmpty()) {
            return null;
        }
        StringJoiner joined = new StringJoiner("; ");
        all.forEach(joined::add);
        return joined.toString();
    }

    /** Lever publishes creation time as epoch milliseconds. */
    private static LocalDate parseDate(JsonNode createdAt) {
        if (!createdAt.isNumber()) {
            return null;
        }
        return Instant.ofEpochMilli(createdAt.asLong()).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
