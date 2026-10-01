package com.anuragbhandary.jobradar.discover;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.fetch.FetchException;
import com.anuragbhandary.jobradar.fetch.HttpFetchClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Finds job boards in the Internet Archive's URL index.
 *
 * <p>Every board on a hosted applicant tracking system lives at a predictable
 * address ({@code jobs.lever.co/<board>}, {@code <tenant>.wd5.myworkdayjobs.com/<site>}),
 * and the Wayback Machine's CDX index lists every such address it has archived.
 * Asking it for one platform's host returns thousands of boards nobody had to
 * name first. That is the whole trick behind job aggregators' coverage.
 *
 * <p>Common Crawl's index was the first choice and answered 504 on every crawl on
 * 2026-10-01; the Wayback index answered every platform the same day.
 *
 * <p>Two passes. The default asks only for board home pages, through an anchored
 * {@code filter}, and returns in seconds. {@code deep} pages through every archived
 * URL on the host, job pages included: on Ashby about one board in five was only
 * ever archived through a job page.
 *
 * <p>The index is a record of what people typed and clicked, so it holds junk -
 * file names, tracking ids, and on Workday at least one URL with an email
 * address and password typed into it. A board name is accepted only if it looks
 * like one ({@link #isPlausible}), which keeps all of that out of the database.
 */
@Component
public class ArchiveIndex {

    private static final Logger log = LoggerFactory.getLogger(ArchiveIndex.class);

    private static final String CDX = "https://web.archive.org/cdx/search/cdx";

    /** Whole-domain queries take minutes on Workday; see {@link HttpFetchClient#getSlow}. */
    private static final int TIMEOUT_SECONDS = 300;

    /** A guard against a paging bug, well above Workday's 1,001 pages. */
    private static final int MAX_PAGES = 1500;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");

    private static final Pattern FILE = Pattern.compile(
            "(?i).*\\.(ico|txt|xml|js|css|png|jpe?g|gif|svg|json|html?|php|aspx?|pdf|woff2?)$");

    /** Path words that are pages of the platform itself, not boards. */
    private static final Set<String> RESERVED = Set.of(
            "api", "embed", "v0", "v1", "jobs", "job", "search", "apply", "careers", "static",
            "assets", "wday", "login", "signin", "logout", "robots", "favicon", "sitemap",
            "null", "undefined", "www", "app", "admin", "help", "support", "privacy", "terms",
            "en-us", "cdn", "images", "img", "js", "css", "error", "404", "home", "index");

    /** One platform: where to ask, which home pages count, and how to read a name. */
    enum Platform {
        GREENHOUSE(Source.GREENHOUSE, List.of(
                new Query("job-boards.greenhouse.io/", "prefix",
                        "^https?://job-boards\\.greenhouse\\.io/[^/?#]+/?$"),
                new Query("boards.greenhouse.io/", "prefix",
                        "^https?://boards\\.greenhouse\\.io/[^/?#]+/?$")),
                Pattern.compile("^https?://(?:job-)?boards\\.greenhouse\\.io/(?:embed/job_board\\?for=)?([^/?#&]+)"),
                true),
        LEVER(Source.LEVER, List.of(
                new Query("jobs.lever.co/", "prefix", "^https?://jobs\\.lever\\.co/[^/?#]+/?$")),
                Pattern.compile("^https?://jobs\\.lever\\.co/([^/?#]+)"),
                true),
        ASHBY(Source.ASHBY, List.of(
                new Query("jobs.ashbyhq.com/", "prefix", "^https?://jobs\\.ashbyhq\\.com/[^/?#]+/?$")),
                Pattern.compile("^https?://jobs\\.ashbyhq\\.com/([^/?#]+)"),
                false),
        RECRUITEE(Source.RECRUITEE, List.of(
                new Query("recruitee.com", "domain", "^https?://[^./]+\\.recruitee\\.com/?$")),
                Pattern.compile("^https?://([^./]+)\\.recruitee\\.com(?:[/?#]|$)"),
                true),
        SMARTRECRUITERS(Source.SMARTRECRUITERS, List.of(
                new Query("jobs.smartrecruiters.com/", "prefix",
                        "^https?://jobs\\.smartrecruiters\\.com/[^/?#]+/?$"),
                new Query("careers.smartrecruiters.com/", "prefix",
                        "^https?://careers\\.smartrecruiters\\.com/[^/?#]+/?$")),
                Pattern.compile("^https?://(?:jobs|careers)\\.smartrecruiters\\.com/([^/?#]+)"),
                false),
        WORKDAY(Source.WORKDAY, List.of(
                new Query("myworkdayjobs.com", "domain",
                        "^https?://[^./]+\\.wd[0-9]+\\.myworkdayjobs\\.com/([a-z]{2}-[A-Z]{2}/)?[^/?#]+/?$")),
                Pattern.compile(
                        "^https?://([^./]+)\\.(wd[0-9]+)\\.myworkdayjobs\\.com/(?:[a-z]{2}-[A-Z]{2}/)?([^/?#]+)"),
                false),
        // Tenants on eightfold.ai name their employer domain in the query string:
        // paypal.eightfold.ai/careers?domain=paypal.com (139 on 2026-10-01).
        EIGHTFOLD(Source.EIGHTFOLD, List.of(
                new Query("eightfold.ai", "domain", ".*[?&]domain=.*")),
                Pattern.compile("^https?://([a-z0-9-]+\\.eightfold\\.ai)/[^?#]*\\?(?:[^#]*&)?domain=([a-z0-9.-]+)",
                        Pattern.CASE_INSENSITIVE),
                true),
        // Oracle candidate sites: host and site number. The survey adds the
        // country id each board needs (3,176 pairs on 2026-10-01).
        ORACLE_HCM(Source.ORACLE_HCM, List.of(
                new Query("oraclecloud.com", "domain",
                        ".*/hcmUI/CandidateExperience/[a-z]+/sites/[A-Za-z0-9_]+.*")),
                Pattern.compile("^https?://([a-z0-9.-]+\\.oraclecloud\\.com)/hcmUI/CandidateExperience/[a-z]+/sites/([A-Za-z0-9_]+)",
                        Pattern.CASE_INSENSITIVE),
                false);

        final Source source;
        final List<Query> queries;
        final Pattern name;
        /** Whether the platform treats board names case-insensitively. */
        final boolean lowercase;

        Platform(Source source, List<Query> queries, Pattern name, boolean lowercase) {
            this.source = source;
            this.queries = queries;
            this.name = name;
            this.lowercase = lowercase;
        }

        static Platform of(Source source) {
            for (Platform p : values()) {
                if (p.source == source) {
                    return p;
                }
            }
            throw new IllegalArgumentException(source + " is not discovered through the archive");
        }
    }

    /** One CDX query: the URL key, how to match it, and the home-page filter. */
    record Query(String url, String matchType, String rootFilter) {
    }

    private final HttpFetchClient http;

    public ArchiveIndex(HttpFetchClient http) {
        this.http = http;
    }

    /**
     * The boards on one platform, as tokens the fetchers accept, in first-seen order.
     * Keys are lowercased for de-duplication; values keep the form to store.
     */
    public Map<String, String> boards(Source source, boolean deep) throws FetchException {
        Platform platform = Platform.of(source);
        Map<String, String> found = new LinkedHashMap<>();
        String from = String.valueOf(Year.now().getValue() - 1);
        for (Query query : platform.queries) {
            String base = CDX + "?url=" + enc(query.url()) + "&matchType=" + query.matchType()
                    + "&from=" + from + "&fl=original&collapse=urlkey";
            int before = found.size();
            collect(platform, http.getSlow(base + "&filter=" + enc("original:" + query.rootFilter()),
                    TIMEOUT_SECONDS), found);
            log.info("{}: {} boards from home pages of {}", source, found.size() - before, query.url());
            if (deep) {
                int pages = pageCount(query, from);
                for (int page = 0; page < Math.min(pages, MAX_PAGES); page++) {
                    try {
                        collect(platform, http.getSlow(base + "&page=" + page, TIMEOUT_SECONDS), found);
                    } catch (FetchException e) {
                        // One page lost is a few boards missed until the next deep
                        // pass, not a reason to throw away the rest.
                        log.warn("{}: archive page {} of {} failed: {}",
                                source, page, pages, e.getMessage());
                    }
                    if (page % 50 == 49) {
                        log.info("{}: deep pass page {}/{}, {} boards so far",
                                source, page + 1, pages, found.size());
                    }
                }
            }
        }
        return found;
    }

    private int pageCount(Query query, String from) throws FetchException {
        String body = http.getSlow(CDX + "?url=" + enc(query.url()) + "&matchType="
                + query.matchType() + "&from=" + from + "&showNumPages=true", TIMEOUT_SECONDS);
        try {
            return Integer.parseInt(body.trim());
        } catch (NumberFormatException e) {
            throw new FetchException("Unexpected page count from the archive: " + body.trim());
        }
    }

    /** Reads one CDX response, one URL per line, into the map. */
    static void collect(Platform platform, String body, Map<String, String> found) {
        for (String line : body.split("\n")) {
            String token = token(platform, line.trim());
            if (token != null) {
                found.putIfAbsent(token.toLowerCase(Locale.ROOT), token);
            }
        }
    }

    /** The board token in one archived URL, or null when there is none worth keeping. */
    static String token(Platform platform, String url) {
        Matcher m = platform.name.matcher(url);
        if (!m.find()) {
            return null;
        }
        if (platform == Platform.WORKDAY) {
            String site = m.group(3);
            if (!isPlausible(m.group(1)) || !isPlausible(site)) {
                return null;
            }
            return m.group(1).toLowerCase(Locale.ROOT) + "/" + m.group(2) + "/" + site;
        }
        if (platform == Platform.EIGHTFOLD) {
            String host = m.group(1).toLowerCase(Locale.ROOT);
            String domain = m.group(2).toLowerCase(Locale.ROOT);
            String name = domain.split("\\.")[0];
            // Test tenants, and the platform's own site.
            if (host.contains("sandbox") || domain.equals("eightfold.ai") || !isPlausible(name)) {
                return null;
            }
            return name + "/" + host + "/" + domain;
        }
        if (platform == Platform.ORACLE_HCM) {
            String site = m.group(2);
            // A site is CX, CX_1001 and the like; "jobsearch" and other words are
            // the employer's own front end, not a candidate site number.
            if (!site.matches("CX(_[0-9]+)?")) {
                return null;
            }
            return m.group(1).toLowerCase(Locale.ROOT) + "/" + site;
        }
        String name = m.group(1);
        if (!isPlausible(name)) {
            return null;
        }
        return platform.lowercase ? name.toLowerCase(Locale.ROOT) : name;
    }

    /**
     * Letters, digits, dot, dash and underscore, starting with a letter or digit,
     * and not a file or a page of the platform itself. Anything else in the index
     * is junk: encoded tracking blobs, "$10.2K", and credentials pasted into a URL.
     */
    static boolean isPlausible(String name) {
        return name != null
                && NAME.matcher(name).matches()
                // "job-boards.greenhouse.io/1005": job ids and page numbers, never
                // a board, and 36 of the first 40 Greenhouse names were these.
                && !name.chars().allMatch(Character::isDigit)
                && !FILE.matcher(name).matches()
                && !RESERVED.contains(name.toLowerCase(Locale.ROOT));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
