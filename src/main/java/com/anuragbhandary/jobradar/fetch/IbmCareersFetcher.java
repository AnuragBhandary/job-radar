package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads IBM's careers search, one country per board.
 *
 * <p>careers.ibm.com is Avature, but it sits behind an AWS WAF challenge
 * (HTTP 202, empty body) and {@link AvatureFetcher} cannot read it. The search on
 * ibm.com/careers/search is fed by IBM's site-wide search API instead, an
 * Elasticsearch-style POST that answers without a challenge, and each hit carries
 * the full job text in {@code body}. So no job page is read at all.
 *
 * <p><b>The token</b> is the country exactly as the Location facet writes it:
 * {@code India}, {@code United Kingdom}. The facet is the country; the city is in
 * {@code field_keyword_19} as "Bangalore, IN".
 */
@Component
public class IbmCareersFetcher implements AtsFetcher {

    private static final String SEARCH_URL = "https://www-api.ibm.com/search/api/v2";

    private static final int PAGE_SIZE = 100;
    /** India had 1,038 on 2026-10-23; the cap leaves room to double. */
    private static final int MAX_PAGES = 25;

    private static final Pattern JOB_ID = Pattern.compile("[?&]jobId=(\\d+)");
    /** The ", IN" on the end of "Bangalore, IN": the facet names the country better. */
    private static final Pattern COUNTRY_CODE_SUFFIX = Pattern.compile(",\\s*[A-Z]{2}$");

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public IbmCareersFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.IBM;
    }

    /** @param boardToken a Location facet value: India, Germany, United Kingdom... */
    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        List<RawPosting> postings = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            String body = http.post(SEARCH_URL, query(boardToken, page * PAGE_SIZE),
                    page == 0 ? "ibm-" + boardToken : null);
            List<RawPosting> batch = parse(body, boardToken);
            postings.addAll(batch);
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }
        return FetchBatch.of(postings);
    }

    String query(String country, int from) {
        ObjectNode root = json.createObjectNode();
        root.put("appId", "careers");
        root.putArray("scopes").add("careers2");
        root.putObject("query").putObject("bool").putArray("filter")
                .addObject().putObject("term").put("field_keyword_05", country);
        root.put("size", PAGE_SIZE);
        root.put("from", from);
        root.putArray("sort").addObject().put("dcdate", "desc");
        root.putArray("_source").add("url").add("dcdate").add("title").add("body")
                .add("field_keyword_05").add("field_keyword_18").add("field_keyword_19");
        return root.toString();
    }

    /** Split out from {@link #fetch} so tests can drive it from a saved fixture. */
    List<RawPosting> parse(String body, String country) throws FetchException {
        JsonNode hits;
        try {
            hits = json.readTree(body).path("hits").path("hits");
        } catch (Exception e) {
            throw new FetchException("Unparseable IBM search response for " + country, e);
        }
        if (!hits.isArray()) {
            throw new FetchException("No hits array in IBM search response for " + country);
        }
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode hit : hits) {
            RawPosting posting = toPosting(hit.path("_source"), country);
            if (posting != null) {
                postings.add(posting);
            }
        }
        return postings;
    }

    private static RawPosting toPosting(JsonNode job, String country) {
        String url = job.path("url").asText(null);
        String title = job.path("title").asText(null);
        if (url == null || title == null) {
            return null;
        }
        Matcher id = JOB_ID.matcher(url);
        return new RawPosting(
                id.find() ? id.group(1) : url,
                title.trim(),
                place(job.path("field_keyword_19").asText(""), job.path("field_keyword_05").asText(country)),
                withLevel(job.path("field_keyword_18").asText(""), Html.toPlainText(job.path("body").asText(""))),
                url,
                parseDate(job.path("dcdate").asText(null)));
    }

    /**
     * IBM's own level ("Professional", "Entry Level", "Internship") as the first
     * line, so the card shows it: of 1,035 India jobs on 2026-10-23, three were
     * Entry Level and few state years, so it is the best seniority signal there is.
     */
    static String withLevel(String level, String text) {
        return level.isBlank() ? text : "IBM level: " + level.trim() + "\n\n" + text;
    }

    /** "Bangalore, IN" and India become "Bangalore, India". */
    static String place(String city, String country) {
        String trimmed = COUNTRY_CODE_SUFFIX.matcher(city.trim()).replaceFirst("").trim();
        return trimmed.isEmpty() ? country : trimmed + ", " + country;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
