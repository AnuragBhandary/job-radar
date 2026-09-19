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
 * Reads the current month's "Ask HN: Who is hiring?" thread.
 *
 * <p>Added for the lanes the ATS boards barely reach: in September 2026, 27 of the
 * thread's 261 postings were remote and open worldwide, to APAC or to India. Each
 * top-level comment is one posting. There is no title field, so the first line,
 * which the thread asks posters to write as "Company | Role | Location | ...", is
 * used as both title and location, and screening reads it the way it reads any
 * other. It is free text, so expect more of these to reach the handoff file for a
 * person to judge than from a structured board.
 *
 * <p>Two requests a day through the public Algolia HN API: find the thread, then
 * read it. When a new month's thread appears, the old month's postings stop being
 * returned and close, which is what a month-old HN posting usually is anyway.
 */
@Component
public class HackerNewsFetcher implements AtsFetcher {

    /** The only board token this source has. */
    public static final String BOARD = "whoishiring";

    private static final String THREADS =
            "https://hn.algolia.com/api/v1/search_by_date?tags=story,author_whoishiring&hitsPerPage=10";
    private static final String ITEM = "https://hn.algolia.com/api/v1/items/%s";
    private static final String PERMALINK = "https://news.ycombinator.com/item?id=%s";

    /** Past this the first line is a paragraph, not a header. */
    private static final int MAX_TITLE = 200;

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public HackerNewsFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.HACKER_NEWS;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        String threadId = latestThread(http.get(THREADS, "hn-threads"));
        return FetchBatch.of(parse(http.get(ITEM.formatted(threadId), "hn-" + threadId)));
    }

    /** The id of the newest "Who is hiring?" story, not "Who wants to be hired?". */
    String latestThread(String body) throws FetchException {
        JsonNode hits = read(body).path("hits");
        for (JsonNode hit : hits) {
            if (hit.path("title").asText("").startsWith("Ask HN: Who is hiring?")) {
                return hit.path("objectID").asText();
            }
        }
        throw new FetchException("No \"Who is hiring?\" thread among the latest whoishiring stories");
    }

    /** Split out from {@link #fetch} so tests can drive it from a saved fixture. */
    List<RawPosting> parse(String body) throws FetchException {
        JsonNode children = read(body).path("children");
        if (!children.isArray()) {
            throw new FetchException("Hacker News thread returned no comments array");
        }
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode child : children) {
            String id = child.path("id").asText(null);
            String raw = child.path("text").asText("");
            // Deleted and flagged comments come back with no text.
            if (id == null || raw.isBlank()) {
                continue;
            }
            // HN separates paragraphs with a bare <p>, and the header is everything
            // before the first one. Each paragraph is flattened on its own so the
            // description keeps its breaks; Html.toPlainText joins everything.
            String[] paragraphs = raw.split("(?i)<p>");
            String header = header(Html.toPlainText(paragraphs[0]));
            StringBuilder text = new StringBuilder();
            for (String paragraph : paragraphs) {
                String plain = Html.toPlainText(paragraph);
                if (!plain.isBlank()) {
                    text.append(text.isEmpty() ? "" : "\n\n").append(plain);
                }
            }
            postings.add(new RawPosting(id, header, header, text.toString(),
                    PERMALINK.formatted(id), date(child.path("created_at_i").asLong(0))));
        }
        return postings;
    }

    static String header(String firstParagraph) {
        String first = firstParagraph.strip();
        return first.length() <= MAX_TITLE ? first : first.substring(0, MAX_TITLE).strip() + "…";
    }

    private static LocalDate date(long epochSeconds) {
        return epochSeconds <= 0 ? null
                : Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private JsonNode read(String body) throws FetchException {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new FetchException("Unparseable response from the Algolia HN API", e);
        }
    }
}
