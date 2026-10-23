package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads Urban Company's careers site, one board.
 *
 * <p>careers.urbancompany.com fills its list from one POST to Urban Company's
 * own API, {@code getAllJobs}, which returns every open job with its full text
 * (13 on 2026-10-23). Applications go to TurboHire, whose site this never reads.
 */
@Component
public class UrbanCompanyFetcher implements AtsFetcher {

    public static final String BOARD = "urbancompany";

    private static final String JOBS = "https://www.urbanclap.com/api/v2/platform-gateway/getAllJobs";

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public UrbanCompanyFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.URBANCOMPANY;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        return FetchBatch.of(parse(http.post(JOBS, "{}", "urbancompany-jobs")));
    }

    List<RawPosting> parse(String body) throws FetchException {
        JsonNode jobs;
        try {
            jobs = json.readTree(body).path("jobs");
        } catch (Exception e) {
            throw new FetchException("Unparseable Urban Company job list", e);
        }
        if (!jobs.isArray()) {
            throw new FetchException("No jobs in Urban Company response");
        }
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            String id = job.path("job_id").asText(null);
            String title = job.path("job_title").asText(null);
            if (id == null || id.isBlank() || title == null || title.isBlank()) {
                continue;
            }
            List<String> places = new ArrayList<>();
            for (JsonNode place : job.path("location")) {
                places.add(place.asText("").trim());
            }
            postings.add(new RawPosting(id, title.trim(), String.join("; ", places),
                    Html.toPlainText(job.path("job_description").asText("")),
                    "https://careers.urbancompany.com/jobDetail?id=" + id, null));
        }
        return postings;
    }
}
