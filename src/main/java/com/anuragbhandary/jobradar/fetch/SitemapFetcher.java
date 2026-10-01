package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.GeoFilter;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads any careers site that lists its job pages in a sitemap.
 *
 * <p>Large employers publish job data for search engines in two forms: a
 * {@code JobPosting} block of JSON-LD (Intuit, HSBC), or the older microdata, the
 * same fields as {@code itemprop} attributes on the page (EY and other sites on
 * SAP SuccessFactors). Some SuccessFactors sites mark only the title and
 * description (Standard Chartered, Wipro). This reads whichever is there, and
 * falls back to the URL for the location.
 *
 * <p>The reader is general; what it needs from a site is a sitemap of job pages
 * whose URLs carry the title and the place
 * ({@code /job/Mumbai-Apprentice-Global-Support-Functions-India-2026/1372812457/}).
 * Geography and title are screened on that slug before any page is read, as on
 * Eightfold. A site whose URLs carry no place would have every page read every
 * run, so it is not seeded.
 *
 * <p><b>Pages already read are not read again.</b> EY lists 1,747 jobs in India.
 * A job page already stored and not modified in the last {@link #REREAD_DAYS}
 * days (the sitemap's {@code lastmod}) is returned from the database, which is
 * the one database read a fetcher makes, done once per board. New pages are read
 * newest first, at most {@link #MAX_PAGES_PER_RUN} a run; the rest wait for the
 * next run, which is harmless because a page never stored cannot be closed.
 *
 * <p><b>The token</b> is {@code name/host/path-to-sitemap}:
 * {@code ey/careers.ey.com/sitemap.xml}. The name is what the big-tech list matches.
 */
@Component
public class SitemapFetcher implements AtsFetcher {

    private static final Logger log = LoggerFactory.getLogger(SitemapFetcher.class);

    static final int MAX_PAGES_PER_RUN = 400;

    static final int REREAD_DAYS = 2;

    /** A sitemap index with more children than this is not a careers sitemap. */
    private static final int MAX_CHILD_SITEMAPS = 60;

    private static final Pattern URL_ENTRY = Pattern.compile("<url>(.*?)</url>", Pattern.DOTALL);
    private static final Pattern LOC = Pattern.compile("<loc>\\s*([^<\\s]+)\\s*</loc>");
    private static final Pattern LASTMOD = Pattern.compile("<lastmod>\\s*([^<\\s]+)\\s*</lastmod>");

    /** "/job/..." or "/jobs/...", with something that reads like a title after it. */
    private static final Pattern JOB_PATH = Pattern.compile("(?i)/jobs?/([^?#]+)");

    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{6,}");

    private static final Pattern JSON_LD = Pattern.compile(
            "<script[^>]+application/ld\\+json[^>]*>(.*?)</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final HttpFetchClient http;
    private final ObjectReader lenientJson;
    private final GeoFilter geoFilter;
    private final TitleFilter titleFilter;
    private final PostingRepository postings;

    public SitemapFetcher(HttpFetchClient http, ObjectMapper json, GeoFilter geoFilter,
            TitleFilter titleFilter, PostingRepository postings) {
        this.http = http;
        // Job descriptions are pasted from word processors, and raw tabs and line
        // breaks inside JSON strings are common enough to need tolerating.
        this.lenientJson = json.readerFor(JsonNode.class)
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
        this.geoFilter = geoFilter;
        this.titleFilter = titleFilter;
        this.postings = postings;
    }

    @Override
    public Source source() {
        return Source.SITEMAP;
    }

    /** {@code name/host/path}, as seeded. */
    record Board(String name, String host, String path) {

        static Board parse(String token) throws FetchException {
            String[] parts = token == null ? new String[0] : token.split("/", 3);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new FetchException("Sitemap token must be name/host/path, got: " + token);
            }
            return new Board(parts[0], parts[1], parts[2]);
        }

        String sitemap() {
            return "https://" + host + "/" + path;
        }
    }

    /** One job page as the sitemap lists it. */
    record Listed(String id, String url, String slugText, Instant lastmod) {
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);
        List<Listed> listed = new ArrayList<>(jobPages(read(board.sitemap(), "sitemap-" + board.name(), 0)
                .stream().map(SitemapFetcher::listed).filter(java.util.Objects::nonNull).toList()));
        if (listed.isEmpty()) {
            throw new FetchException("No job pages in the sitemap of " + boardToken);
        }
        List<Listed> shortlist = listed.stream().filter(this::passesCheapFilters).toList();

        Map<String, Posting> stored = new HashMap<>();
        for (Posting p : postings.findBySourceAndBoardToken(Source.SITEMAP, boardToken)) {
            if (p.getStatus() != PostingStatus.CLOSED) {
                stored.put(p.getExternalId(), p);
            }
        }

        Instant rereadAfter = Instant.now().minus(java.time.Duration.ofDays(REREAD_DAYS));
        List<RawPosting> out = new ArrayList<>(shortlist.size());
        List<Listed> toRead = new ArrayList<>();
        for (Listed job : shortlist) {
            Posting known = stored.get(job.id());
            if (known != null && (job.lastmod() == null || job.lastmod().isBefore(rereadAfter))) {
                out.add(new RawPosting(known.getExternalId(), known.getTitle(), known.getLocation(),
                        known.getDescriptionText(), known.getUrl(), known.getPostedDate()));
            } else {
                toRead.add(job);
            }
        }
        toRead.sort(Comparator.comparing(Listed::lastmod,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (toRead.size() > MAX_PAGES_PER_RUN) {
            log.info("{}: {} pages to read, reading the newest {} this run",
                    boardToken, toRead.size(), MAX_PAGES_PER_RUN);
            toRead = toRead.subList(0, MAX_PAGES_PER_RUN);
        }

        int failed = 0;
        int gone = 0;
        for (Listed job : toRead) {
            try {
                HttpFetchClient.HttpResult page = http.getRaw(job.url(), null);
                if (page.status() == 404 || page.status() == 410) {
                    // Sitemaps trail closures by a day or more; a 404 is a
                    // closed job, not a failure.
                    gone++;
                    continue;
                }
                if (!page.isSuccess()) {
                    failed++;
                    continue;
                }
                RawPosting posting = parsePage(page.body(), job);
                if (posting == null) {
                    failed++;
                } else {
                    out.add(posting);
                }
            } catch (FetchException e) {
                log.debug("Skipping {}: {}", job.url(), e.getMessage());
                failed++;
            }
        }
        log.debug("{}: {} job pages, {} shortlisted, {} read ({} closed, {} failed)",
                boardToken, listed.size(), shortlist.size(), toRead.size(), gone, failed);
        // As on Eightfold: a posting missing from the batch reads as closed.
        if (failed > 0 && failed * 2 > toRead.size() - gone) {
            throw new FetchException(failed + " of " + toRead.size()
                    + " job pages could not be read on " + boardToken);
        }
        return new FetchBatch(out, listed.size());
    }

    /** Every {@code <url>} entry, following a sitemap index two levels down. */
    private List<String[]> read(String url, String fixtureName, int depth) throws FetchException {
        String xml = http.get(url, fixtureName);
        List<String[]> entries = new ArrayList<>();
        if (xml.contains("<sitemapindex")) {
            if (depth >= 2) {
                return entries;
            }
            Matcher m = LOC.matcher(xml);
            int children = 0;
            while (m.find() && children++ < MAX_CHILD_SITEMAPS) {
                String child = m.group(1).replace("&amp;", "&");
                if (child.endsWith(".gz")) {
                    continue; // The client reads text; none of the seeded sites needs these.
                }
                try {
                    entries.addAll(read(child, null, depth + 1));
                } catch (FetchException e) {
                    log.debug("Skipping child sitemap {}: {}", child, e.getMessage());
                }
            }
            return entries;
        }
        return entries(xml);
    }

    /** {@code [loc, lastmod]} for each {@code <url>} in one sitemap. */
    static List<String[]> entries(String xml) {
        List<String[]> entries = new ArrayList<>();
        Matcher url = URL_ENTRY.matcher(xml);
        while (url.find()) {
            Matcher loc = LOC.matcher(url.group(1));
            if (loc.find()) {
                Matcher lastmod = LASTMOD.matcher(url.group(1));
                entries.add(new String[] {loc.group(1).replace("&amp;", "&"),
                        lastmod.find() ? lastmod.group(1) : null});
            }
        }
        return entries;
    }

    /** Keeps entries that are job pages, once each. */
    static List<Listed> jobPages(List<Listed> listed) {
        Map<String, Listed> byId = new LinkedHashMap<>();
        for (Listed job : listed) {
            byId.putIfAbsent(job.id(), job);
        }
        return new ArrayList<>(byId.values());
    }

    /** A sitemap entry as a job page, or null when it is not one. */
    static Listed listed(String[] entry) {
        String url = entry[0];
        String path;
        try {
            path = URI.create(url).getRawPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (path == null) {
            return null;
        }
        Matcher m = JOB_PATH.matcher(path);
        if (!m.find()) {
            return null;
        }
        String slug = slugText(m.group(1));
        // "/jobs/" and "/jobs/search" are listings; a job page has a title in it,
        // which is always more than one word.
        if (!slug.contains(" ") || slug.chars().noneMatch(Character::isLetter)) {
            return null;
        }
        return new Listed(idOf(path), url, slug, instant(entry[1]));
    }

    static String slugText(String raw) {
        String decoded;
        try {
            decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            decoded = raw;
        }
        return decoded.replaceAll("[-_/+]+", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * The last long number in the path, which these sites use as the job id;
     * otherwise a hash of the path, since the column holds 128 characters and
     * some of these paths are longer.
     */
    static String idOf(String path) {
        Matcher m = LONG_NUMBER.matcher(path);
        String id = null;
        while (m.find()) {
            id = m.group();
        }
        if (id != null) {
            return id;
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean passesCheapFilters(Listed job) {
        return geoFilter.classify(job.slugText(), null).verdict().accepted()
                && titleFilter.screen(job.slugText()).accepted();
    }

    /** One job page: JSON-LD first, then microdata, with the slug as the last resort for the place. */
    RawPosting parsePage(String html, Listed job) {
        RawPosting fromJsonLd = jsonLd(html, job);
        if (fromJsonLd != null) {
            return fromJsonLd;
        }
        String title = Microdata.text(html, "title");
        if (title == null || title.isBlank()) {
            return null;
        }
        String description = Microdata.html(html, "description");
        StringJoiner place = new StringJoiner(", ");
        for (String part : List.of("addressLocality", "addressRegion", "addressCountry")) {
            String value = Microdata.text(html, part);
            if (value != null && !value.isBlank()) {
                place.add(value.trim());
            }
        }
        return new RawPosting(job.id(), title.trim(),
                place.length() > 0 ? place.toString() : job.slugText(),
                description == null ? "" : plain(description),
                job.url(), date(Microdata.text(html, "datePosted")));
    }

    private RawPosting jsonLd(String html, Listed job) {
        Matcher m = JSON_LD.matcher(html);
        while (m.find()) {
            JsonNode root;
            try {
                root = lenientJson.readValue(m.group(1).trim());
            } catch (Exception e) {
                continue;
            }
            JsonNode posting = findJobPosting(root);
            if (posting == null) {
                continue;
            }
            String title = posting.path("title").asText(null);
            if (title == null || title.isBlank()) {
                continue;
            }
            return new RawPosting(job.id(), Html.toPlainText(title).trim(), place(posting, job),
                    plain(posting.path("description").asText("")), job.url(),
                    date(posting.path("datePosted").asText(null)));
        }
        return null;
    }

    private static JsonNode findJobPosting(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode n : node) {
                JsonNode found = findJobPosting(n);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        JsonNode type = node.path("@type");
        if ("JobPosting".equals(type.asText()) || (type.isArray() && type.toString().contains("\"JobPosting\""))) {
            return node;
        }
        return findJobPosting(node.path("@graph"));
    }

    /** "Bangalore, Karnātaka, India", from one or several jobLocation entries. */
    private static String place(JsonNode posting, Listed job) {
        List<String> places = new ArrayList<>();
        JsonNode locations = posting.path("jobLocation");
        for (JsonNode loc : locations.isArray() ? locations : List.of(locations)) {
            JsonNode address = loc.path("address");
            StringJoiner one = new StringJoiner(", ");
            for (String field : List.of("addressLocality", "addressRegion", "addressCountry")) {
                JsonNode v = address.path(field);
                String text = v.isObject() ? v.path("name").asText(null) : v.asText(null);
                if (text != null && !text.isBlank()) {
                    one.add(text.trim());
                }
            }
            if (one.length() > 0) {
                places.add(one.toString());
            }
        }
        if ("TELECOMMUTE".equalsIgnoreCase(posting.path("jobLocationType").asText(""))) {
            StringJoiner where = new StringJoiner(", ");
            JsonNode req = posting.path("applicantLocationRequirements");
            for (JsonNode r : req.isArray() ? req : List.of(req)) {
                String name = r.path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    where.add(name);
                }
            }
            places.add(where.length() > 0 ? "Remote - " + where : "Remote");
        }
        return places.isEmpty() ? job.slugText() : String.join("; ", places);
    }

    /** HTML to text, including descriptions published with their tags escaped. */
    private static String plain(String html) {
        String once = Html.toPlainText(html);
        return once.contains("<") && once.contains(">") ? Html.toPlainText(once) : once;
    }

    private static final DateTimeFormatter LOOSE_DATE = DateTimeFormatter.ofPattern("yyyy-M-d");

    /** "2026-09-30T12:14:47", "2026-10-1" (Intuit does not pad), or null. */
    static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String day = raw.trim();
        int t = day.indexOf('T');
        if (t > 0) {
            day = day.substring(0, t);
        }
        try {
            return LocalDate.parse(day, LOOSE_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant instant(String lastmod) {
        if (lastmod == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(lastmod).toInstant();
        } catch (DateTimeParseException e) {
            LocalDate day = date(lastmod);
            return day == null ? null : day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        }
    }

    /** Reads schema.org microdata attributes without an HTML parser. */
    static final class Microdata {

        private Microdata() {
        }

        /** The text of the first element with this itemprop, or its {@code content} attribute. */
        static String text(String html, String prop) {
            String inner = html(html, prop);
            return inner == null ? null : Html.toPlainText(inner).trim();
        }

        /** The inner HTML of the first element with this itemprop, balanced on its tag name. */
        static String html(String html, String prop) {
            Matcher start = Pattern.compile(
                    "<([a-zA-Z][a-zA-Z0-9]*)\\b([^>]*\\bitemprop=\"" + Pattern.quote(prop) + "\"[^>]*)>")
                    .matcher(html);
            if (!start.find()) {
                return null;
            }
            String tag = start.group(1).toLowerCase(java.util.Locale.ROOT);
            Matcher content = Pattern.compile("\\bcontent=\"([^\"]*)\"").matcher(start.group(2));
            if (content.find()) {
                return content.group(1);
            }
            if (start.group(2).endsWith("/") || tag.equals("meta")) {
                return null;
            }
            Pattern edge = Pattern.compile("<(/?)" + tag + "\\b[^>]*>", Pattern.CASE_INSENSITIVE);
            Matcher m = edge.matcher(html);
            int depth = 1;
            int from = start.end();
            m.region(from, html.length());
            while (m.find()) {
                depth += m.group(1).isEmpty() ? 1 : -1;
                if (depth == 0) {
                    return html.substring(from, m.start());
                }
            }
            return null;
        }
    }
}
