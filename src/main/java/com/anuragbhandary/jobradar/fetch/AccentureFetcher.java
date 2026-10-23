package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * Reads accenture.com's own job search, one country per board.
 *
 * <p>Accenture's Workday tenant answered 429 to every Accenture board from
 * 2026-10-21, and even single requests eleven seconds apart (2026-10-23), so its
 * 2,000-job India searches could not be read there any more. accenture.com's job
 * search posts a form to {@code /api/accenture/elastic/findjobs} and gets back
 * the jobs with their full text, career level and experience band in one list;
 * robots.txt shuts the search pages ({@code careers/jobsearch?}) but not the API.
 *
 * <p><b>The token</b> is {@code country/countrySite/language[/experience]}, as the
 * site names them: {@code India/in-en/en/0-2}, {@code United Kingdom/gb-en/en},
 * {@code Deutschland/de-de/de}. The optional band filters on the site's
 * "Experience: 0-2 years" facet; India had 4,707 jobs in it, where the whole
 * country runs past the API's 10,000 cap. European jobs carry plain bands
 * ("10-12") outside that facet, so those boards read the whole country.
 */
@Component
public class AccentureFetcher implements AtsFetcher {

    private static final String SEARCH = "https://www.accenture.com/api/accenture/elastic/findjobs";
    private static final int PAGE_SIZE = 100;
    /** The API stops at 10,000 hits. */
    private static final int MAX_PAGES = 100;

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public AccentureFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.ACCENTURE;
    }

    /** {@code country/countrySite/language[/experience]}, as seeded. */
    record Board(String country, String countrySite, String language, String experience) {

        static Board parse(String token) throws FetchException {
            String[] parts = token == null ? new String[0] : token.split("/");
            if (parts.length < 3 || parts.length > 4 || parts[0].isBlank() || parts[1].isBlank()
                    || parts[2].isBlank()) {
                throw new FetchException("Accenture token must be country/countrySite/language[/experience], got: "
                        + token);
            }
            return new Board(parts[0], parts[1], parts[2], parts.length == 4 ? parts[3] : null);
        }

        /** The search form, URL-encoded. */
        String form(int startIndex) {
            String filters = experience == null ? "[]"
                    : "[{\"fieldName\":\"yearsOfExperience.keyword\",\"items\":[\"Experience: " + experience
                            + " years\"],\"multiSelect\":false}]";
            StringJoiner form = new StringJoiner("&");
            add(form, "startIndex", String.valueOf(startIndex));
            add(form, "maxResultSize", String.valueOf(PAGE_SIZE));
            add(form, "jobKeyword", "");
            add(form, "jobCountry", country);
            add(form, "jobLanguage", language);
            add(form, "countrySite", countrySite);
            add(form, "sortBy", "1");
            add(form, "searchType", "vectorSearch");
            add(form, "enableQueryBoost", "true");
            add(form, "minScore", "0");
            add(form, "totalHits", "true");
            add(form, "jobFilters", filters);
            return form.toString();
        }

        private static void add(StringJoiner form, String name, String value) {
            form.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);
        List<RawPosting> postings = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            String body = http.postForm(SEARCH, board.form(page * PAGE_SIZE),
                    page == 0 ? "accenture-" + board.countrySite() : null);
            List<RawPosting> batch = parse(body, board);
            postings.addAll(batch);
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }
        return FetchBatch.of(postings);
    }

    List<RawPosting> parse(String body, Board board) throws FetchException {
        JsonNode jobs;
        try {
            jobs = json.readTree(body).path("data");
        } catch (Exception e) {
            throw new FetchException("Unparseable Accenture job search for " + board.country(), e);
        }
        if (!jobs.isArray()) {
            throw new FetchException("No data in Accenture job search for " + board.country());
        }
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            String id = job.path("requisitionId").asText(null);
            String title = job.path("title").asText(null);
            if (id == null || id.isBlank() || title == null || title.isBlank()) {
                continue;
            }
            List<String> places = new ArrayList<>();
            String country = job.path("country").asText(board.country());
            for (JsonNode city : job.path("location")) {
                places.add(city.asText("").trim() + ", " + country);
            }
            String url = job.path("jobDetailUrl").asText("").replace("{0}", board.countrySite());
            postings.add(new RawPosting(id, title.trim(),
                    places.isEmpty() ? country : String.join("; ", places),
                    description(job), url.isBlank() ? null : url, null));
        }
        return postings;
    }

    /**
     * The level first, so the card and the ranking see it, then the job and its
     * qualifications; the job's own "Minimum 2 year(s)" is what the years reader
     * takes. "Early Career" is the fresher track
     * ("New Associate"), which the ranking reads as an entry level.
     */
    private static String description(JsonNode job) {
        StringBuilder text = new StringBuilder();
        String track = job.path("jobTypeDescription").asText("").trim();
        String level = job.path("careerLevel").asText("").trim();
        if (!track.isEmpty() || !level.isEmpty()) {
            text.append("Accenture level: ").append(track.isEmpty() ? level
                    : level.isEmpty() ? track : track + " (" + level + ")");
        }
        // Not the experience band: the years reader takes the smallest number in
        // the text, so a "2-5 years" line hid the job's own "5 to 12 years" (the
        // first run of the 2-5 band read 855 of 870 candidates as 2 years).
        for (String field : List.of("jobDescription", "qualification")) {
            String part = Html.toPlainText(job.path(field).asText(""));
            if (!part.isBlank()) {
                text.append(text.isEmpty() ? "" : "\n\n").append(part);
            }
        }
        return text.toString();
    }
}
