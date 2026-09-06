package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.GeoFilter;
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
 * Reads a Workday careers site.
 *
 * <p>The largest gap in this tool's coverage before it existed. Greenhouse,
 * Ashby and Lever are what venture-funded startups use; Workday is what large
 * European employers use, which is most of the ones that sponsor visas and run
 * graduate programmes. Philips and NXP alone add 1,569 postings in the
 * Netherlands.
 *
 * <p>Two things make it unlike the other four.
 *
 * <p><b>The token is three parts.</b> A board is a tenant, the datacentre it
 * lives in, and a site - {@code philips/wd3/jobs-and-careers}. The same tenant
 * can run several sites with different content, and the datacentre is not
 * derivable from the tenant name, so all three are carried in the token rather
 * than guessed. A wrong triple answers 404 or 422 immediately, which makes
 * Workday the opposite of SmartRecruiters: here an empty board really is empty.
 *
 * <p><b>The search is a POST.</b> Paging is a JSON body, not a query string,
 * which is why {@link HttpFetchClient} learned to post.
 *
 * <p>Like SmartRecruiters, the list carries no description, so the geography and
 * title filters run here before the per-posting detail fetch. On Philips that is
 * the difference between a handful of requests and 809.
 */
@Component
public class WorkdayFetcher implements AtsFetcher {

    private static final Logger log = LoggerFactory.getLogger(WorkdayFetcher.class);

    private static final String BASE = "https://%s.%s.myworkdayjobs.com/wday/cxs/%s/%s";

    /** Workday rejects a larger page than this on most tenants. */
    private static final int PAGE_SIZE = 20;

    /** Guards against a pagination bug turning into an unbounded crawl. */
    private static final int MAX_PAGES = 60;

    private final HttpFetchClient http;
    private final ObjectMapper json;
    private final GeoFilter geoFilter;
    private final TitleFilter titleFilter;

    public WorkdayFetcher(
            HttpFetchClient http, ObjectMapper json,
            GeoFilter geoFilter, TitleFilter titleFilter) {
        this.http = http;
        this.json = json;
        this.geoFilter = geoFilter;
        this.titleFilter = titleFilter;
    }

    @Override
    public Source source() {
        return Source.WORKDAY;
    }

    /** {@code tenant/wdN/site}, as seeded. */
    record Board(String tenant, String datacentre, String site) {

        static Board parse(String token) throws FetchException {
            String[] parts = token == null ? new String[0] : token.split("/");
            if (parts.length != 3 || parts[0].isBlank() || parts[2].isBlank()) {
                throw new FetchException(
                        "Workday token must be tenant/wdN/site, got: " + token);
            }
            return new Board(parts[0], parts[1], parts[2]);
        }

        String base() {
            return BASE.formatted(tenant, datacentre, tenant, site);
        }

        /** The page a human would apply on, which is not the API path. */
        String publicUrl(String externalPath) {
            return "https://%s.%s.myworkdayjobs.com/%s%s"
                    .formatted(tenant, datacentre, site, externalPath);
        }
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);

        List<JsonNode> shortlist = new ArrayList<>();
        int boardTotal = 0;
        int skipped = 0;

        for (int page = 0; page < MAX_PAGES; page++) {
            int offset = page * PAGE_SIZE;
            String body = http.post(
                    board.base() + "/jobs",
                    """
                    {"appliedFacets":{},"limit":%d,"offset":%d,"searchText":""}"""
                            .formatted(PAGE_SIZE, offset),
                    page == 0 ? "workday-" + board.tenant() + "-" + board.site() : null);

            JsonNode root = read(body, boardToken);
            // The board's own count, so health reflects the board and not our
            // filters - but Workday does not report it consistently. Philips
            // answers total=809 at offset 0, total=0 at offset 780 and 800, and
            // total=809 again at offset 820. Taking the latest value therefore
            // set the total to zero mid-crawl, and since the loop stops at
            // "offset + page >= total", the very first page ended the fetch: 809
            // postings were read as 20. The largest figure the board ever
            // reports is the only one that can be believed.
            boardTotal = Math.max(boardTotal, root.path("total").asInt(0));
            JsonNode postings = root.path("jobPostings");
            if (!postings.isArray()) {
                throw new FetchException(
                        "Board " + boardToken + " returned no jobPostings array");
            }

            for (JsonNode summary : postings) {
                if (passesCheapFilters(summary)) {
                    shortlist.add(summary);
                } else {
                    skipped++;
                }
            }

            // Both conditions are needed. A short page is the ordinary end of a
            // board; the total guard is what stops the crawl on a board that
            // keeps answering past its own end - offset 820 of 809 returns a
            // full page rather than an empty one.
            if (postings.size() < PAGE_SIZE
                    || (boardTotal > 0 && offset + PAGE_SIZE >= boardTotal)) {
                break;
            }
        }

        log.debug("{}: {} of {} postings shortlisted, {} filtered out before detail fetch",
                boardToken, shortlist.size(), boardTotal, skipped);

        List<RawPosting> detailed = new ArrayList<>(shortlist.size());
        for (JsonNode summary : shortlist) {
            RawPosting posting = withDescription(board, summary);
            if (posting != null) {
                detailed.add(posting);
            }
        }
        return new FetchBatch(detailed, boardTotal);
    }

    private boolean passesCheapFilters(JsonNode summary) {
        String title = summary.path("title").asText(null);
        String location = summary.path("locationsText").asText(null);
        return title != null
                && geoFilter.classify(location, title).verdict().accepted()
                && titleFilter.screen(title).accepted();
    }

    /**
     * Fetches one posting's detail.
     *
     * <p>Returns null rather than failing the board when a single posting cannot
     * be read. A requisition closed between the list call and this one is normal
     * on a board of this size, and losing the other 800 to it would be absurd.
     */
    private RawPosting withDescription(Board board, JsonNode summary) {
        String externalPath = summary.path("externalPath").asText(null);
        if (externalPath == null) {
            return null;
        }
        try {
            JsonNode info = json.readTree(http.get(board.base() + externalPath, null))
                    .path("jobPostingInfo");

            return new RawPosting(
                    externalId(summary, info, externalPath),
                    info.path("title").asText(summary.path("title").asText(null)),
                    info.path("location").asText(summary.path("locationsText").asText(null)),
                    Html.toPlainText(info.path("jobDescription").asText("")),
                    info.path("externalUrl").asText(board.publicUrl(externalPath)),
                    // The list only offers "Posted 30+ Days Ago", which stops being
                    // a date at thirty and is useless for ageing a posting. The
                    // detail carries the real one.
                    startDate(info));
        } catch (Exception e) {
            log.debug("Skipping {}: {}", externalPath, e.getMessage());
            return null;
        }
    }

    /**
     * The requisition number, which is stable across the rewrites Workday makes
     * to its own paths. Falls back to the path only when the board publishes no
     * requisition id at all.
     */
    private static String externalId(JsonNode summary, JsonNode info, String externalPath) {
        String reqId = info.path("jobReqId").asText(null);
        if (reqId == null || reqId.isBlank()) {
            JsonNode bullets = summary.path("bulletFields");
            reqId = bullets.isArray() && !bullets.isEmpty() ? bullets.get(0).asText(null) : null;
        }
        return reqId == null || reqId.isBlank() ? externalPath : reqId;
    }

    private static LocalDate startDate(JsonNode info) {
        String raw = info.path("startDate").asText(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private JsonNode read(String body, String boardToken) throws FetchException {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new FetchException("Unreadable response from " + boardToken, e);
        }
    }
}
