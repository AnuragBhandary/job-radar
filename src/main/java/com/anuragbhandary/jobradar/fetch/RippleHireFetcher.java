package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads careers sites on RippleHire, an Indian ATS, one company per board.
 *
 * <p>The candidate site lists jobs from {@code candidatejobsearch} (JSON when
 * asked for it), with the title, city and the stated experience range, but no
 * text; {@code candidatejobdetail} gives the text, one request per job. LTM
 * (LTIMindtree) had 1,475 jobs on 2026-10-23 and 95 of the first hundred asked
 * for more than two years, so the text is read only for a job whose title passes
 * and whose stated minimum is two years or less. The rest are stored with the
 * range as their description, which screening rejects on its own.
 *
 * <p><b>The token</b> is {@code name/host/careerSiteToken}, the token being the
 * one in the company's careers link: {@code ltm/ltimindtree.ripplehire.com/xviy...}.
 */
@Component
public class RippleHireFetcher implements AtsFetcher {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 30;
    /** Above this stated minimum the job is rejected anyway; its text is not read. */
    private static final int MAX_MIN_YEARS_TO_READ = 2;

    private final HttpFetchClient http;
    private final ObjectMapper json;
    private final TitleFilter titleFilter;
    private final StoredPostings stored;

    public RippleHireFetcher(HttpFetchClient http, ObjectMapper json, TitleFilter titleFilter,
            StoredPostings stored) {
        this.http = http;
        this.json = json;
        this.titleFilter = titleFilter;
        this.stored = stored;
    }

    @Override
    public Source source() {
        return Source.RIPPLEHIRE;
    }

    /** {@code name/host/careerSiteToken}, as seeded. */
    record Board(String name, String host, String siteToken) {

        static Board parse(String token) throws FetchException {
            String[] parts = token == null ? new String[0] : token.split("/");
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new FetchException("RippleHire token must be name/host/careerSiteToken, got: " + token);
            }
            return new Board(parts[0], parts[1], parts[2]);
        }

        String list(int page) {
            String params = "{\"page\":%d,\"search\":\"*:*\",\"token\":\"%s\",\"source\":\"CAREERSITE\",\"pagesize\":%d}"
                    .formatted(page, siteToken, PAGE_SIZE);
            return "https://%s/candidate/candidatejobsearch?careerSiteUrlParams=%s&lang=en"
                    .formatted(host, encode(params));
        }

        String detail(String jobSeq) {
            return "https://%s/candidate/candidatejobdetail?token=%s&jobSeq=%s&source=CAREERSITE&lang=en"
                    .formatted(host, encode(siteToken), encode(jobSeq));
        }

        String page(String jobSeq) {
            return "https://%s/candidate/?token=%s&lang=en&source=CAREERSITE#detail/job/%s"
                    .formatted(host, siteToken, jobSeq);
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    /** One job as the list gives it. */
    record Listed(String id, String title, String place, String experience, Integer minYears) {
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);
        StoredPostings.Known known = stored.open(Source.RIPPLEHIRE, boardToken);
        List<RawPosting> postings = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Listed> listed = parseList(http.get(board.list(page),
                    page == 0 ? "ripplehire-" + board.name() : null));
            for (Listed job : listed) {
                if (!titleFilter.screen(job.title()).accepted()) {
                    continue;
                }
                if (job.minYears() != null && job.minYears() > MAX_MIN_YEARS_TO_READ) {
                    postings.add(posting(board, job, ""));
                    continue;
                }
                RawPosting reused = known.reuse(job.id());
                if (reused != null) {
                    postings.add(reused);
                    continue;
                }
                postings.add(posting(board, job, parseDetail(http.get(board.detail(job.id()), null))));
            }
            if (listed.size() < PAGE_SIZE) {
                break;
            }
        }
        return FetchBatch.of(postings);
    }

    private static RawPosting posting(Board board, Listed job, String text) {
        String experience = job.experience().isBlank() ? "" : "Experience: " + job.experience();
        String description = experience.isEmpty() ? text
                : text.isBlank() ? experience : experience + "\n\n" + text;
        return new RawPosting(job.id(), job.title(), job.place(), description, board.page(job.id()), null);
    }

    List<Listed> parseList(String body) throws FetchException {
        JsonNode jobs;
        try {
            jobs = json.readTree(body).path("jobVoList");
        } catch (Exception e) {
            throw new FetchException("Unparseable RippleHire job list", e);
        }
        if (!jobs.isArray()) {
            throw new FetchException("No jobVoList in RippleHire response");
        }
        List<Listed> listed = new ArrayList<>();
        for (JsonNode job : jobs) {
            String id = job.path("jobSeq").asText(null);
            String title = job.path("jobTitle").asText(null);
            if (id == null || id.isBlank() || title == null || title.isBlank()) {
                continue;
            }
            String city = job.path("locations").asText("").trim();
            String country = job.path("jobLocation").asText("").trim();
            String place = city.isEmpty() ? country : country.isEmpty() ? city : city + ", " + country;
            Integer minYears = null;
            try {
                minYears = Integer.valueOf(job.path("jobMinExp").asText("").trim());
            } catch (NumberFormatException ignored) {
                // No stated minimum: read the text and let screening decide.
            }
            String experience = job.path("jobReqExp").asText("").trim();
            // "0 - 0 Years" (174 of 1,475 at LTM, all senior roles abroad) means
            // nothing was stated, not that freshers are wanted.
            if (Integer.valueOf(0).equals(minYears) && "0".equals(job.path("jobMaxExp").asText("").trim())) {
                minYears = null;
                experience = "";
            }
            listed.add(new Listed(id, title.trim(), place, experience, minYears));
        }
        return listed;
    }

    String parseDetail(String body) throws FetchException {
        try {
            return Html.toPlainText(json.readTree(body).path("jobVO").path("jobDesc").asText(""));
        } catch (Exception e) {
            throw new FetchException("Unparseable RippleHire job detail", e);
        }
    }
}
