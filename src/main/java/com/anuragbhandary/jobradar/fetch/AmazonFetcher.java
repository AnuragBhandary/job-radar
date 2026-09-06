package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Reads amazon.jobs for one country.
 *
 * <p>The "board token" here is a country code - IND, DEU, IRL, NLD - because
 * Amazon has one job site rather than one board per company. Dublin in
 * particular runs a graduate requisition line entirely separate from Berlin's,
 * so all four must be swept individually.
 *
 * <p><strong>A note on trust.</strong> This endpoint has been observed returning
 * a complete, live-looking description for a posting that shows "Sorry, the job
 * you're looking for isn't available" in a real browser. Everything here is
 * therefore a lead to verify, not a fact - which is true of the whole tool, but
 * demonstrably true of this source.
 */
@Component
public class AmazonFetcher implements AtsFetcher {

    private static final String SEARCH_URL =
            "https://www.amazon.jobs/en/search.json?base_query=&country=%s"
                    + "&result_limit=%d&offset=%d&sort=recent&category%%5B%%5D=software-development";

    private static final String JOB_BASE = "https://www.amazon.jobs";

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;

    /**
     * Amazon writes "September  4, 2026" - with two spaces, because the day is
     * padded rather than trimmed. A strict formatter rejects it.
     */
    private static final DateTimeFormatter POSTED_DATE =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public AmazonFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.AMAZON;
    }

    /** @param boardToken an Amazon country code: IND, DEU, IRL or NLD */
    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        List<RawPosting> postings = new ArrayList<>();

        for (int page = 0; page < MAX_PAGES; page++) {
            String body = http.get(
                    SEARCH_URL.formatted(boardToken, PAGE_SIZE, page * PAGE_SIZE),
                    page == 0 ? "amazon-" + boardToken : null);

            JsonNode root;
            try {
                root = json.readTree(body);
            } catch (Exception e) {
                throw new FetchException("Unparseable response for country " + boardToken, e);
            }

            JsonNode jobs = root.path("jobs");
            if (!jobs.isArray()) {
                throw new FetchException("No jobs array for country " + boardToken);
            }

            int size = jobs.size();
            for (JsonNode job : jobs) {
                RawPosting posting = toPosting(job);
                if (posting != null) {
                    postings.add(posting);
                }
            }
            if (size < PAGE_SIZE) {
                break;
            }
        }
        return FetchBatch.of(postings);
    }

    /** Split out from {@link #fetch} so tests can drive it from a saved fixture. */
    List<RawPosting> parse(String body, String country) throws FetchException {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            throw new FetchException("Unparseable response for country " + country, e);
        }
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) {
            throw new FetchException("No jobs array for country " + country);
        }
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            RawPosting posting = toPosting(job);
            if (posting != null) {
                postings.add(posting);
            }
        }
        return postings;
    }

    private static RawPosting toPosting(JsonNode job) {
        String id = job.path("id_icims").asText(null);
        String title = job.path("title").asText(null);
        if (id == null || title == null) {
            return null;
        }
        String path = job.path("job_path").asText(null);
        return new RawPosting(
                id,
                title.trim(),
                job.path("normalized_location").asText(job.path("location").asText(null)),
                qualifications(job),
                path == null ? null : JOB_BASE + path,
                parseDate(job.path("posted_date").asText(null)));
    }

    /**
     * The qualifications, not the role blurb.
     *
     * <p>{@code basic_qualifications} is where Amazon states eligibility, and it
     * is where the disqualifying "N years of non-internship professional
     * software development experience" lives. The marketing description is left
     * out deliberately: the years extractor takes the smallest number it finds,
     * and prose about how long a team has existed is exactly the kind of stray
     * small number that would turn a genuine rejection into an acceptance.
     *
     * <p>It also makes change detection sharper - a reworded blurb is not a
     * change worth a line in the digest, but an edited requirement is.
     */
    private static String qualifications(JsonNode job) {
        String basic = Html.toPlainText(job.path("basic_qualifications").asText(""));
        String preferred = Html.toPlainText(job.path("preferred_qualifications").asText(""));
        return (basic + (preferred.isBlank() ? "" : "\n\nPreferred: " + preferred)).trim();
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            // Collapse the double space Amazon leaves around single-digit days.
            return LocalDate.parse(value.replaceAll("\\s+", " ").trim(), POSTED_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
