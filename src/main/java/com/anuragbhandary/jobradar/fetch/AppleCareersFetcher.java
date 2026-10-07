package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.TargetPlaces;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads Apple's careers search for one country.
 *
 * <p>Apple publishes no job API or sitemap, which is why it sat on the browser
 * watch list until 2026-10-07. But its pages are rendered on the server with the
 * data embedded: {@code window.__staticRouterHydrationData = JSON.parse("...")}
 * holds twenty search results per page, and the same block on a job's own page
 * holds its minimum and preferred qualifications. So it is read like Oracle: the
 * list first, the cheap title and place filters, then one page per survivor.
 *
 * <p>The token is {@code locale/location} as the site's own URLs spell them:
 * {@code en-in/india-INDC}, {@code en-ie/ireland-IRL}. Most results are Apple
 * Retail ("IN-Business Expert"), which the title filter drops before any detail
 * page is fetched.
 */
@Component
public class AppleCareersFetcher implements AtsFetcher {

    private static final Logger log = LoggerFactory.getLogger(AppleCareersFetcher.class);

    private static final String SITE = "https://jobs.apple.com/";

    private static final Pattern DATA = Pattern.compile(
            "window\\.__staticRouterHydrationData\\s*=\\s*JSON\\.parse\\((\".*?\")\\);",
            Pattern.DOTALL);

    /** Twenty a page: 500 jobs in one country. India had 165 on 2026-10-07. */
    private static final int MAX_PAGES = 25;

    private final HttpFetchClient http;
    private final ObjectMapper json;
    private final TargetPlaces places;
    private final TitleFilter titleFilter;
    private final StoredPostings stored;

    public AppleCareersFetcher(
            HttpFetchClient http, ObjectMapper json,
            TargetPlaces places, TitleFilter titleFilter, StoredPostings stored) {
        this.http = http;
        this.json = json;
        this.places = places;
        this.titleFilter = titleFilter;
        this.stored = stored;
    }

    @Override
    public Source source() {
        return Source.APPLE;
    }

    /** {@code locale/location}, as seeded. */
    record Board(String locale, String location) {

        static Board parse(String token) throws FetchException {
            String[] parts = token == null ? new String[0] : token.split("/");
            if (parts.length != 2 || !parts[0].matches("[a-z]{2}-[a-z]{2}")
                    || !parts[1].matches("[a-z-]+-[A-Z]{3,4}")) {
                throw new FetchException("Apple token must be locale/location, got: " + token);
            }
            return new Board(parts[0], parts[1]);
        }

        String search(int page) {
            return SITE + locale + "/search?location=" + location + "&page=" + page;
        }

        String details(String id) {
            return SITE + locale + "/details/" + id;
        }
    }

    /** One search result. {@code id} is the part the job's own page is named by. */
    record Listed(String id, String title, String location, LocalDate posted) {
    }

    record Page(List<Listed> jobs, int total) {
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);
        List<Listed> shortlist = new ArrayList<>();
        int total = 0;
        int seen = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            Page result = parseSearch(http.get(board.search(page),
                    page == 1 ? "apple-" + board.location() : null), boardToken);
            total = Math.max(total, result.total());
            seen += result.jobs().size();
            for (Listed job : result.jobs()) {
                if (places.wanted(job.location(), job.title())
                        && titleFilter.screen(job.title()).accepted()) {
                    shortlist.add(job);
                }
            }
            if (result.jobs().isEmpty() || seen >= total) {
                break;
            }
        }
        log.debug("{}: {} of {} jobs shortlisted", boardToken, shortlist.size(), total);

        List<RawPosting> detailed = new ArrayList<>(shortlist.size());
        int failed = 0;
        StoredPostings.Known known = stored.open(Source.APPLE, boardToken);
        for (Listed job : shortlist) {
            RawPosting reused = known.reuse(job.id());
            if (reused != null) {
                detailed.add(reused);
                continue;
            }
            try {
                detailed.add(parseDetails(http.get(board.details(job.id()), null), job, board));
            } catch (Exception e) {
                log.debug("Skipping {} on {}: {}", job.id(), boardToken, e.getMessage());
                failed++;
            }
        }
        // As on Oracle: a missing posting reads as closed, so a run where most
        // detail pages failed must not be taken as most jobs having closed.
        if (failed > 0 && failed * 2 > shortlist.size()) {
            throw new FetchException(failed + " of " + shortlist.size()
                    + " detail pages failed on " + boardToken);
        }
        return new FetchBatch(detailed, total);
    }

    /** Split out so tests can drive it from a saved page. */
    Page parseSearch(String html, String boardToken) throws FetchException {
        JsonNode search = loaderData(html, boardToken).path("search");
        JsonNode results = search.path("searchResults");
        if (!results.isArray()) {
            throw new FetchException("No searchResults on Apple's page for " + boardToken);
        }
        List<Listed> jobs = new ArrayList<>();
        for (JsonNode job : results) {
            String title = job.path("postingTitle").asText(null);
            // A requisition's id is its page name ("200684719-1052"). A retail
            // pipeline's is "PIPE-" plus the position id, and its page is the bare id.
            String id = job.path("id").asText("").replaceFirst("^PIPE-", "");
            if (id.isBlank() || title == null || title.isBlank()) {
                throw new FetchException("An Apple job without an id or title; the page format changed");
            }
            jobs.add(new Listed(id, title.trim(), location(job.path("locations")),
                    date(job.path("postDateInGMT").asText(null))));
        }
        return new Page(jobs, search.path("totalRecords").asInt(jobs.size()));
    }

    RawPosting parseDetails(String html, Listed job, Board board) throws FetchException {
        JsonNode data = loaderData(html, job.id()).path("jobDetails").path("jobsData");
        if (data.isMissingNode() || data.isNull()) {
            throw new FetchException("No jobsData on Apple's page for " + job.id());
        }
        return new RawPosting(
                job.id(),
                job.title(),
                job.location(),
                description(data),
                board.details(job.id()),
                job.posted());
    }

    /**
     * The qualifications first and the summary left out, as for Google: Apple's
     * summaries open on company prose, which is where stray small numbers live.
     * The headings are the ones the years extractor already knows, so the
     * preferred list is read as a wish.
     */
    private static String description(JsonNode data) {
        StringBuilder text = new StringBuilder();
        section(text, "Minimum Qualifications", data.path("minimumQualifications").asText(""));
        section(text, "Description", data.path("description").asText(""));
        section(text, "Responsibilities", data.path("responsibilities").asText(""));
        section(text, "Preferred Qualifications", data.path("preferredQualifications").asText(""));
        return text.toString().trim();
    }

    private static void section(StringBuilder text, String heading, String body) {
        String plain = body.contains("<") ? Html.toPlainText(body) : body.strip();
        if (!plain.isBlank()) {
            text.append(heading).append(": ").append(plain).append("\n\n");
        }
    }

    /** "Hyderabad, India" from each location's city or name and its country, deduplicated. */
    private static String location(JsonNode locations) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode l : locations) {
            String city = l.path("city").asText("");
            String country = l.path("countryName").asText("");
            String name = city.isBlank() ? l.path("name").asText("") : city;
            // The country too: "Cambridge" alone is also in Massachusetts.
            if (!country.isBlank() && !name.contains(country)) {
                name = name.isBlank() ? country : name + ", " + country;
            }
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return String.join("; ", names);
    }

    private JsonNode loaderData(String html, String what) throws FetchException {
        Matcher m = DATA.matcher(html);
        if (!m.find()) {
            throw new FetchException("No hydration data on Apple's page for " + what);
        }
        try {
            // A JSON string holding JSON: decode the string, then the document.
            String document = json.readValue(m.group(1), String.class);
            return json.readTree(document).path("loaderData");
        } catch (Exception e) {
            throw new FetchException("Unreadable hydration data on Apple's page for " + what, e);
        }
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
