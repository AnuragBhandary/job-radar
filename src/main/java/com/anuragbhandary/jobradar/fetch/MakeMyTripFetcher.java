package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads MakeMyTrip's careers site (go-MMT), one board.
 *
 * <p>The jobs live in Darwinbox, whose own API sits behind a bot check, but
 * careers.makemytrip.com serves them through its own {@code /api/jobs} (title,
 * place, stated experience range; 31 on 2026-10-23) and {@code /api/jobDetails}
 * (the text and the apply link). The text is read only for a job whose title
 * passes and whose stated minimum is two years or less; the rest are stored with
 * the range alone, which screening rejects on its own.
 */
@Component
public class MakeMyTripFetcher implements AtsFetcher {

    public static final String BOARD = "makemytrip";

    private static final String HOST = "https://careers.makemytrip.com";
    private static final int MAX_MIN_YEARS_TO_READ = 2;
    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final HttpFetchClient http;
    private final ObjectMapper json;
    private final TitleFilter titleFilter;
    private final StoredPostings stored;

    public MakeMyTripFetcher(HttpFetchClient http, ObjectMapper json, TitleFilter titleFilter,
            StoredPostings stored) {
        this.http = http;
        this.json = json;
        this.titleFilter = titleFilter;
        this.stored = stored;
    }

    @Override
    public Source source() {
        return Source.MAKEMYTRIP;
    }

    /** One job as the list gives it. */
    record Listed(String id, String title, String place, String experience, Integer minYears, LocalDate created) {
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        StoredPostings.Known known = stored.open(Source.MAKEMYTRIP, boardToken);
        List<RawPosting> postings = new ArrayList<>();
        for (Listed job : parseList(http.get(HOST + "/api/jobs", "makemytrip-jobs"))) {
            if (!titleFilter.screen(job.title()).accepted()) {
                continue;
            }
            if (job.minYears() != null && job.minYears() > MAX_MIN_YEARS_TO_READ) {
                postings.add(posting(job, "", null));
                continue;
            }
            RawPosting reused = known.reuse(job.id());
            if (reused != null) {
                postings.add(reused);
                continue;
            }
            JsonNode detail = parseDetail(http.get(HOST + "/api/jobDetails?jobId="
                    + URLEncoder.encode(job.id(), StandardCharsets.UTF_8), null));
            postings.add(posting(job, Html.toPlainText(Html.unescape(detail.path("job_decription").asText(""))),
                    detail.path("applyUrl").asText(null)));
        }
        return FetchBatch.of(postings);
    }

    private static RawPosting posting(Listed job, String text, String applyUrl) {
        String description = job.experience().isEmpty() ? text
                : "Experience: " + job.experience() + (text.isBlank() ? "" : "\n\n" + text);
        String url = applyUrl != null ? applyUrl
                : "https://gommt.darwinbox.in/ms/candidatev2/main/careers/jobDetails/" + job.id();
        return new RawPosting(job.id(), job.title(), job.place(), description, url, job.created());
    }

    List<Listed> parseList(String body) throws FetchException {
        JsonNode jobs;
        try {
            jobs = json.readTree(body).path("allJobs");
        } catch (Exception e) {
            throw new FetchException("Unparseable MakeMyTrip job list", e);
        }
        if (!jobs.isArray()) {
            throw new FetchException("No allJobs in MakeMyTrip response");
        }
        List<Listed> listed = new ArrayList<>();
        for (JsonNode job : jobs) {
            String id = job.path("job_id").asText(null);
            String title = job.path("job_title").asText(null);
            if (id == null || id.isBlank() || title == null || title.isBlank()) {
                continue;
            }
            List<String> places = new ArrayList<>();
            for (JsonNode place : job.path("location")) {
                // "Gurgaon, Haryana, India (Gurgaon_MMT)": the office code adds nothing.
                places.add(place.asText("").replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim());
            }
            String from = job.path("experience_from").asText("").trim();
            String to = job.path("experience_to").asText("").trim();
            Integer minYears = from.matches("\\d+") ? Integer.valueOf(from) : null;
            String experience = minYears == null ? "" : to.matches("\\d+") ? from + " - " + to + " years" : from + "+ years";
            listed.add(new Listed(id, title.trim(), String.join("; ", places), experience, minYears,
                    created(job.path("job_created_timestamp").asText(""))));
        }
        return listed;
    }

    JsonNode parseDetail(String body) throws FetchException {
        try {
            return json.readTree(body).path("data");
        } catch (Exception e) {
            throw new FetchException("Unparseable MakeMyTrip job detail", e);
        }
    }

    /** "15-10-2026 10:27:23": the date is the first ten characters. */
    private static LocalDate created(String value) {
        try {
            return value.length() >= 10 ? LocalDate.parse(value.substring(0, 10), CREATED) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
