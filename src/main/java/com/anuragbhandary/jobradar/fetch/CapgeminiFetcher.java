package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads Capgemini's job search, one country per board.
 *
 * <p>capgemini.com's job search page is filled from a public JSON API on Azure,
 * {@code cg-jobstream-api}, which returns the full description with each job, so
 * one paged list is the whole read. Apply links go to careers.capgemini.com
 * (SuccessFactors).
 *
 * <p><b>The token</b> is the API's {@code country_code}, language first:
 * {@code en-in}, {@code de-de}, {@code nl-nl}, {@code en-gb}. The list gives the
 * city alone ("Chennai (ex Madras)"), so the country is added from the token.
 */
@Component
public class CapgeminiFetcher implements AtsFetcher {

    private static final String SEARCH_URL =
            "https://cg-jobstream-api.azurewebsites.net/api/job-search?page=%d&size=%d&country_code=%s";

    private static final int PAGE_SIZE = 100;
    /** India had 746 on 2026-10-23. */
    private static final int MAX_PAGES = 30;

    /** The country half of the token, as the geography filter reads it. */
    private static final Map<String, String> COUNTRIES = Map.of(
            "in", "India",
            "de", "Germany",
            "nl", "Netherlands",
            "ie", "Ireland",
            "gb", "United Kingdom",
            "ae", "United Arab Emirates");

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public CapgeminiFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.CAPGEMINI;
    }

    /** @param boardToken a country code such as en-in */
    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        List<RawPosting> postings = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String body = http.get(SEARCH_URL.formatted(page, PAGE_SIZE, boardToken),
                    page == 1 ? "capgemini-" + boardToken : null);
            List<RawPosting> batch = parse(body, boardToken);
            postings.addAll(batch);
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }
        return FetchBatch.of(postings);
    }

    /** Split out from {@link #fetch} so tests can drive it from a saved fixture. */
    List<RawPosting> parse(String body, String countryCode) throws FetchException {
        JsonNode jobs;
        try {
            jobs = json.readTree(body).path("data");
        } catch (Exception e) {
            throw new FetchException("Unparseable Capgemini response for " + countryCode, e);
        }
        if (!jobs.isArray()) {
            throw new FetchException("No data array in Capgemini response for " + countryCode);
        }
        String country = country(countryCode);
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            RawPosting posting = toPosting(job, country);
            if (posting != null) {
                postings.add(posting);
            }
        }
        return postings;
    }

    private static RawPosting toPosting(JsonNode job, String country) {
        String id = job.path("ref").asText(job.path("id").asText(null));
        String title = job.path("title").asText(null);
        if (id == null || id.isBlank() || title == null) {
            return null;
        }
        String city = job.path("location").asText("").trim();
        return new RawPosting(
                id,
                title.trim(),
                city.isEmpty() ? country : city + ", " + country,
                withLevel(job.path("experience_level").asText(""),
                        Html.toPlainText(job.path("description").asText(""))),
                job.path("apply_job_url").asText(null),
                // updated_at is when the API re-indexed the job, and every job is
                // re-indexed together (all read 09:50 on 2026-10-23); as a posted
                // date it would make the whole board look new each day.
                null);
    }

    /**
     * Capgemini's own level as the first line, so the card shows it. India's
     * descriptions rarely state years, and 720 of 746 were "Experienced
     * Professionals" on 2026-10-23 (freshers join through campus drives).
     */
    static String withLevel(String level, String text) {
        return level.isBlank() ? text : "Capgemini level: " + level.trim() + "\n\n" + text;
    }

    /** "en-in" and "in-en" both name India; an unknown code is passed through. */
    static String country(String countryCode) {
        for (String part : countryCode.toLowerCase(java.util.Locale.ROOT).split("-")) {
            String name = COUNTRIES.get(part);
            if (name != null) {
                return name;
            }
        }
        return countryCode;
    }
}
