package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.TargetPlaces;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a careers site on Oracle's recruiting cloud: JPMorgan Chase, Oracle.
 *
 * <p>The candidate site is a JavaScript app over a public REST API, the same
 * one the page calls. The list gives the title, locations and posting date; the
 * description needs one request per job. So, as on Workday, geography and title
 * are screened on the list and only the survivors are read in full.
 *
 * <p><b>The token is four parts:</b> {@code jpmc/jpmc.fa.oraclecloud.com/CX_1001/300000000289360}:
 * a short name (what the big-tech list matches), the host, the candidate site
 * number, and the id of a country in that site's geography. The country id is
 * per site (India is 300000000289360 on JPMorgan's and 300000000106947 on
 * Oracle's), and the API's {@code location=India} text filter returned nothing
 * on the day this was written, so the id is carried rather than looked up.
 */
@Component
public class OracleHcmFetcher implements AtsFetcher {

    private static final Logger log = LoggerFactory.getLogger(OracleHcmFetcher.class);

    private static final int PAGE_SIZE = 25;

    /** 1,000 postings in one country; JPMorgan's India desks were 323. */
    private static final int MAX_PAGES = 40;

    private final HttpFetchClient http;
    private final ObjectMapper json;
    private final TargetPlaces places;
    private final TitleFilter titleFilter;
    private final StoredPostings stored;

    public OracleHcmFetcher(
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
        return Source.ORACLE_HCM;
    }

    /** {@code name/host/site/locationId}, as seeded. */
    record Board(String name, String host, String site, String locationId) {

        static Board parse(String token) throws FetchException {
            String[] parts = token == null ? new String[0] : token.split("/");
            if (parts.length != 4 || !parts[3].matches("\\d+")
                    || parts[0].isBlank() || parts[1].isBlank() || !parts[2].matches("\\w+")) {
                throw new FetchException(
                        "Oracle HCM token must be name/host/site/locationId, got: " + token);
            }
            return new Board(parts[0], parts[1], parts[2], parts[3]);
        }

        String list(int offset) {
            return ("https://%s/hcmRestApi/resources/latest/recruitingCEJobRequisitions"
                    + "?onlyData=true&expand=requisitionList.secondaryLocations"
                    + "&finder=findReqs;siteNumber=%s,locationId=%s,limit=%d,offset=%d,"
                    + "sortBy=POSTING_DATES_DESC")
                    .formatted(host, site, locationId, PAGE_SIZE, offset);
        }

        String detail(String id) {
            return ("https://%s/hcmRestApi/resources/latest/recruitingCEJobRequisitionDetails"
                    + "?expand=all&onlyData=true&finder=ById;Id=%%22%s%%22,siteNumber=%s")
                    .formatted(host, id, site);
        }

        /** The page a person applies on. */
        String publicUrl(String id) {
            return "https://%s/hcmUI/CandidateExperience/en/sites/%s/job/%s".formatted(host, site, id);
        }
    }

    /** One requisition as the list gives it. */
    record Listed(String id, String title, String location, LocalDate posted) {
    }

    /** One page of the list and the search's total. */
    record ListPage(List<Listed> jobs, int total) {
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);
        List<Listed> shortlist = new ArrayList<>();
        int total = 0;
        int seen = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            ListPage result = parseList(http.get(board.list(page * PAGE_SIZE),
                    page == 0 ? "oracle-" + board.name() + "-list" : null), boardToken);
            total = Math.max(total, result.total());
            seen += result.jobs().size();
            for (Listed job : result.jobs()) {
                if (passesCheapFilters(job)) {
                    shortlist.add(job);
                }
            }
            if (result.jobs().size() < PAGE_SIZE || seen >= total) {
                break;
            }
        }
        log.debug("{}: {} of {} requisitions shortlisted", boardToken, shortlist.size(), total);

        List<RawPosting> detailed = new ArrayList<>(shortlist.size());
        int failed = 0;
        StoredPostings.Known known = stored.open(Source.ORACLE_HCM, boardToken);
        for (Listed job : shortlist) {
            RawPosting reused = known.reuse(job.id());
            if (reused != null) {
                detailed.add(reused);
                continue;
            }
            try {
                detailed.add(parseDetail(http.get(board.detail(job.id()), null), job, board));
            } catch (Exception e) {
                log.debug("Skipping {} on {}: {}", job.id(), boardToken, e.getMessage());
                failed++;
            }
        }
        // As on Eightfold: a missing posting reads as closed, so a run where most
        // detail requests failed must not be taken as most jobs having closed.
        if (failed > 0 && failed * 2 > shortlist.size()) {
            throw new FetchException(failed + " of " + shortlist.size()
                    + " detail requests failed on " + boardToken);
        }
        return new FetchBatch(detailed, total);
    }

    ListPage parseList(String body, String boardToken) throws FetchException {
        JsonNode search = read(body, boardToken).path("items").path(0);
        JsonNode list = search.path("requisitionList");
        if (!list.isArray()) {
            throw new FetchException("No requisitionList from " + boardToken);
        }
        List<Listed> jobs = new ArrayList<>();
        for (JsonNode req : list) {
            String id = req.path("Id").asText(null);
            String title = req.path("Title").asText(null);
            if (id == null || title == null) {
                continue;
            }
            List<String> places = new ArrayList<>();
            places.add(req.path("PrimaryLocation").asText(""));
            req.path("secondaryLocations").forEach(s -> places.add(s.path("Name").asText("")));
            places.removeIf(String::isBlank);
            jobs.add(new Listed(id, title.trim(), String.join("; ", places),
                    date(req.path("PostedDate").asText(null))));
        }
        return new ListPage(jobs, search.path("TotalJobsCount").asInt(jobs.size()));
    }

    RawPosting parseDetail(String body, Listed job, Board board) throws FetchException {
        JsonNode req = read(body, board.name()).path("items").path(0);
        if (req.isMissingNode()) {
            throw new FetchException("No detail for requisition " + job.id());
        }
        StringBuilder text = new StringBuilder(
                Html.toPlainText(req.path("ExternalDescriptionStr").asText("")));
        appendSection(text, "Responsibilities", req.path("ExternalResponsibilitiesStr").asText(""));
        appendSection(text, "Qualifications", req.path("ExternalQualificationsStr").asText(""));
        return new RawPosting(
                job.id(),
                job.title(),
                job.location(),
                text.toString().trim(),
                board.publicUrl(job.id()),
                job.posted());
    }

    private static void appendSection(StringBuilder text, String heading, String html) {
        String plain = Html.toPlainText(html);
        if (!plain.isBlank()) {
            text.append("\n\n").append(heading).append(": ").append(plain);
        }
    }

    private boolean passesCheapFilters(Listed job) {
        return places.wanted(job.location(), job.title())
                && titleFilter.screen(job.title()).accepted();
    }

    private JsonNode read(String body, String what) throws FetchException {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new FetchException("Unreadable response from " + what, e);
        }
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
