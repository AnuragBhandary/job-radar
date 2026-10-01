package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.TargetPlaces;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a careers site run on Eightfold: Microsoft, Netflix, Qualcomm.
 *
 * <p>Eightfold's search API is the obvious way in and the wrong one. Microsoft
 * answers it with 429 "Please try again later" to anything that is not a browser
 * session, often on the first request. The site's sitemap, on the other hand, is
 * a static file published for search engines, and it lists every open job. So
 * this reads the sitemap and then the per-job detail API, which answers plain
 * requests.
 *
 * <p>The sitemap carries no description, but each job URL ends in a slug made of
 * the title and the location: {@code 1970393556856779-senior-software-engineer-
 * storage-c-c--india-telangana-hyderabad}. Geography and title are screened on
 * that slug before any detail request, the way {@link WorkdayFetcher} screens its
 * list. On Microsoft that is about 2,400 jobs read as one file and fewer than a
 * hundred detail requests.
 *
 * <p><b>The token is three parts:</b> {@code microsoft/apply.careers.microsoft.com/microsoft.com},
 * a short name (what the big-tech list matches), the site's host, and the domain
 * Eightfold files the employer under. The host is not derivable from the name.
 */
@Component
public class EightfoldFetcher implements AtsFetcher {

    private static final Logger log = LoggerFactory.getLogger(EightfoldFetcher.class);

    /** A job URL in the sitemap: id, then the title-and-location slug. */
    private static final Pattern JOB_LOC = Pattern.compile(
            "<loc>(https://[^<]*?/careers/job/(\\d+)-([^<?]*)[^<]*)</loc>");

    private final HttpFetchClient http;
    private final ObjectMapper json;
    private final TargetPlaces places;
    private final TitleFilter titleFilter;
    private final StoredPostings stored;

    public EightfoldFetcher(
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
        return Source.EIGHTFOLD;
    }

    /** {@code name/host/domain}, as seeded. */
    record Board(String name, String host, String domain) {

        static Board parse(String token) throws FetchException {
            String[] parts = token == null ? new String[0] : token.split("/");
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new FetchException("Eightfold token must be name/host/domain, got: " + token);
            }
            return new Board(parts[0], parts[1], parts[2]);
        }

        String sitemap() {
            return "https://%s/careers/sitemap.xml?domain=%s".formatted(host, domain);
        }

        String detail(String id) {
            return "https://%s/api/pcsx/position_details?position_id=%s&domain=%s&hl=en"
                    .formatted(host, id, domain);
        }
    }

    /** One job as the sitemap lists it. */
    record Listed(String id, String url, String slugText) {
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);
        List<Listed> listed = parseSitemap(
                http.get(board.sitemap(), "eightfold-" + board.name() + "-sitemap"));
        if (listed.isEmpty()) {
            // A sitemap with no jobs at all is a changed format, not an empty
            // employer: Microsoft alone lists over two thousand.
            throw new FetchException("No job URLs in the sitemap of " + boardToken);
        }

        List<Listed> shortlist = listed.stream().filter(this::passesCheapFilters).toList();
        log.debug("{}: {} of {} jobs shortlisted from the sitemap",
                boardToken, shortlist.size(), listed.size());

        List<RawPosting> detailed = new ArrayList<>(shortlist.size());
        int failed = 0;
        StoredPostings.Known known = stored.open(Source.EIGHTFOLD, boardToken);
        for (Listed job : shortlist) {
            RawPosting posting = known.reuse(job.id());
            if (posting == null) {
                posting = withDetail(board, job);
            }
            if (posting == null) {
                failed++;
            } else {
                detailed.add(posting);
            }
        }
        // A posting missing from the batch is read as closed. If the site
        // started refusing detail requests halfway, most of the board would be
        // closed by one bad run, so a mostly failed run fails instead.
        if (failed > 0 && failed * 2 > shortlist.size()) {
            throw new FetchException(failed + " of " + shortlist.size()
                    + " detail requests failed on " + boardToken);
        }
        return new FetchBatch(detailed, listed.size());
    }

    /** Split out so tests can drive it from a saved sitemap. */
    static List<Listed> parseSitemap(String xml) {
        List<Listed> jobs = new ArrayList<>();
        Matcher m = JOB_LOC.matcher(xml);
        while (m.find()) {
            String url = m.group(1).replace("&amp;", "&");
            jobs.add(new Listed(m.group(2), url, slugText(m.group(3))));
        }
        return jobs;
    }

    /** "senior-software-engineer-storage-c-c--india-telangana-hyderabad" to words. */
    static String slugText(String slug) {
        String decoded;
        try {
            decoded = URLDecoder.decode(slug, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            decoded = slug;
        }
        return decoded.replace('-', ' ').replaceAll("\\s+", " ").trim();
    }

    /**
     * The slug holds the title and the location run together, so both filters
     * read the whole of it. Location words do not trip the title rules, and the
     * full screen runs again on the real title and location afterwards.
     */
    private boolean passesCheapFilters(Listed job) {
        return places.wanted(job.slugText(), null)
                && titleFilter.screen(job.slugText()).accepted();
    }

    /** Returns null, as {@link WorkdayFetcher} does, when one job cannot be read. */
    private RawPosting withDetail(Board board, Listed job) {
        try {
            return parseDetail(http.get(board.detail(job.id()), null), job);
        } catch (Exception e) {
            log.debug("Skipping {}: {}", job.url(), e.getMessage());
            return null;
        }
    }

    RawPosting parseDetail(String body, Listed job) throws FetchException {
        JsonNode data;
        try {
            data = json.readTree(body).path("data");
        } catch (Exception e) {
            throw new FetchException("Unreadable detail for " + job.url(), e);
        }
        String title = data.path("name").asText(null);
        if (title == null || title.isBlank()) {
            throw new FetchException("No title in the detail for " + job.url());
        }
        List<String> locations = new ArrayList<>();
        data.path("locations").forEach(l -> locations.add(l.asText()));
        long posted = data.path("postedTs").asLong(0);
        return new RawPosting(
                job.id(),
                title.trim(),
                String.join("; ", locations),
                Html.toPlainText(data.path("jobDescription").asText("")),
                job.url(),
                posted > 0 ? dateOf(posted) : null);
    }

    static LocalDate dateOf(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
