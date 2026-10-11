package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.TargetPlaces;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a careers site on Avature (Siemens, Deloitte's US-India offices).
 *
 * <p>Avature has no public JSON. Its search page is server-rendered HTML, one
 * {@code article--result} per job with the title, a link to
 * {@code .../JobDetail/<slug>/<id>} and a subtitle that carries the place; a
 * "Next" link pages through the rest. The page size is the site's own (Siemens
 * six, Deloitte ten) and ignores the query string, so the crawl follows the
 * link the page offers rather than building offsets. Both sites' sitemaps were
 * checked first (2026-10-11): Siemens lists no job pages, and Deloitte's slugs
 * carry no place.
 *
 * <p>A job page carries a {@code JobPosting} block (Deloitte) or a list of
 * labelled fields (Siemens: "Location(s)", "Posted since", "Experience level",
 * then the unlabelled description). Deloitte's block leaves the address empty,
 * and its list says "Deloitte US – India Offices", so the list's place is the
 * fallback.
 *
 * <p><b>The token</b> is {@code name/host/lang/portal[/search]}:
 * {@code siemens/jobs.siemens.com/en_US/externaljobs/India}. The search goes in
 * the path, as on the site's own links.
 */
@Component
public class AvatureFetcher implements AtsFetcher {

    private static final Logger log = LoggerFactory.getLogger(AvatureFetcher.class);

    /** Siemens India is 782 jobs at six a page. */
    static final int MAX_LIST_PAGES = 250;

    /** Job pages read per board per run; Siemens' are 2.4 MB each. */
    static final int MAX_READS_PER_RUN = 150;

    private static final Pattern ARTICLE = Pattern.compile(
            "<article\\s+class=\"[^\"]*article--result[^\"]*\"[^>]*>(.*?)</article>",
            Pattern.DOTALL);
    private static final Pattern DETAIL_LINK = Pattern.compile(
            "<a\\b[^>]*href=\"(https?://[^\"]+/JobDetail/[^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern SUBTITLE = Pattern.compile(
            "class=\"article__header__text__subtitle\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern ANCHOR = Pattern.compile("<a\\b[^>]*>");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");
    private static final Pattern JOB_ID_TEXT = Pattern.compile("(?i)job\\s+id\\s*:?\\s*\\d+");
    private static final Pattern TRAILING_ID = Pattern.compile("/(\\d+)/?(?:[?#].*)?$");
    private static final Pattern JSON_LD = Pattern.compile(
            "<script[^>]+application/ld\\+json[^>]*>(.*?)</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD_START = Pattern.compile(
            "<div class=\"article__content__view__field[\" ]");
    private static final Pattern FIELD_LABEL = Pattern.compile(
            "class=\"article__content__view__field__label\"[^>]*>(.*?)</div>", Pattern.DOTALL);

    private static final DateTimeFormatter POSTED_SINCE =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final HttpFetchClient http;
    private final ObjectReader lenientJson;
    private final TargetPlaces places;
    private final TitleFilter titleFilter;
    private final StoredPostings stored;

    public AvatureFetcher(HttpFetchClient http, ObjectMapper json, TargetPlaces places,
            TitleFilter titleFilter, StoredPostings stored) {
        this.http = http;
        this.lenientJson = json.readerFor(JsonNode.class)
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
        this.places = places;
        this.titleFilter = titleFilter;
        this.stored = stored;
    }

    @Override
    public Source source() {
        return Source.AVATURE;
    }

    /** {@code name/host/lang/portal[/search]}, as seeded. */
    record Board(String name, String host, String lang, String portal, String search) {

        static Board parse(String token) throws FetchException {
            String[] parts = token == null ? new String[0] : token.split("/");
            if (parts.length < 4 || parts.length > 5) {
                throw new FetchException("Avature token must be name/host/lang/portal[/search], got: " + token);
            }
            for (String part : parts) {
                if (part.isBlank()) {
                    throw new FetchException("Avature token has an empty part: " + token);
                }
            }
            return new Board(parts[0], parts[1], parts[2], parts[3], parts.length == 5 ? parts[4] : "");
        }

        String firstPage() {
            return "https://%s/%s/%s/SearchJobs/%s".formatted(host, lang, portal, search);
        }
    }

    /** One row of the search list. */
    record Listed(String id, String url, String title, String place) {
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);

        Map<String, Listed> listed = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        String page = board.firstPage();
        for (int i = 0; i < MAX_LIST_PAGES && page != null && visited.add(page); i++) {
            String html = http.get(page, i == 0 ? "avature-" + board.name() : null);
            List<Listed> rows = rows(html);
            if (rows.isEmpty()) {
                break;
            }
            rows.forEach(row -> listed.putIfAbsent(row.id(), row));
            page = nextPage(html);
        }
        if (listed.isEmpty()) {
            throw new FetchException("No jobs in the Avature list of " + boardToken);
        }

        List<Listed> shortlist = listed.values().stream()
                .filter(job -> places.wanted(job.place(), job.title())
                        && titleFilter.screen(job.title()).accepted())
                .toList();

        StoredPostings.Known known = stored.open(Source.AVATURE, boardToken);
        List<RawPosting> out = new ArrayList<>(shortlist.size());
        int reads = 0;
        int failed = 0;
        // The list is newest first, so a capped run reads the latest.
        for (Listed job : shortlist) {
            RawPosting posting = known.reuse(job.id());
            if (posting == null && reads >= MAX_READS_PER_RUN) {
                posting = known.stored(job.id());
            } else if (posting == null) {
                reads++;
                posting = detail(job);
                if (posting == null) {
                    failed++;
                    posting = known.stored(job.id());
                }
            }
            if (posting != null) {
                out.add(posting);
            }
        }
        log.debug("{}: {} listed, {} shortlisted, {} pages read ({} failed)",
                boardToken, listed.size(), shortlist.size(), reads, failed);
        // A missing posting reads as closed; a site that stopped answering
        // halfway must not close half the board.
        if (failed > 0 && failed * 2 > reads) {
            throw new FetchException(failed + " of " + reads + " job pages failed on " + boardToken);
        }
        return new FetchBatch(out, listed.size());
    }

    /** The jobs on one search page. */
    static List<Listed> rows(String html) {
        List<Listed> rows = new ArrayList<>();
        Matcher article = ARTICLE.matcher(html);
        while (article.find()) {
            String body = article.group(1);
            Matcher link = DETAIL_LINK.matcher(body);
            if (!link.find()) {
                continue;
            }
            String url = Html.unescape(link.group(1));
            Matcher id = TRAILING_ID.matcher(url);
            if (!id.find()) {
                continue;
            }
            String title = Html.toPlainText(link.group(2)).trim();
            Matcher subtitle = SUBTITLE.matcher(body);
            String place = subtitle.find() ? place(subtitle.group(1)) : null;
            rows.add(new Listed(id.group(1), url, title, place));
        }
        return rows;
    }

    /** The subtitle as a place: its parts, without the job id and the bullets between them. */
    static String place(String subtitleHtml) {
        String text = JOB_ID_TEXT.matcher(Html.toPlainText(subtitleHtml)).replaceAll(" ");
        StringJoiner parts = new StringJoiner(" | ");
        for (String part : text.split("[|•]")) {
            String clean = part.replaceAll("\\s+", " ").replaceAll("\\s+,", ",").trim();
            if (!clean.isEmpty()) {
                parts.add(clean);
            }
        }
        return parts.length() == 0 ? null : parts.toString();
    }

    /**
     * The "Next" link's target, or null on the last page. The class sits on the
     * link itself at Deloitte and on the {@code <li>} around it at Siemens, so
     * the first link that starts at or after the class is the one.
     */
    static String nextPage(String html) {
        int marker = html.indexOf("paginationNextLink");
        if (marker < 0) {
            return null;
        }
        Matcher anchor = ANCHOR.matcher(html);
        int tagStart = html.lastIndexOf('<', marker);
        if (anchor.find(Math.max(tagStart, 0)) && anchor.start() - marker < 500) {
            Matcher href = HREF.matcher(anchor.group());
            return href.find() ? Html.unescape(href.group(1)) : null;
        }
        return null;
    }

    private RawPosting detail(Listed job) {
        try {
            HttpFetchClient.HttpResult page = http.getRaw(job.url(), null);
            if (!page.isSuccess()) {
                return null;
            }
            return parseDetail(page.body(), job);
        } catch (FetchException e) {
            log.debug("Skipping {}: {}", job.url(), e.getMessage());
            return null;
        }
    }

    /** One job page: the JSON-LD block, else the labelled fields; the list's place as the fallback. */
    RawPosting parseDetail(String html, Listed job) {
        Matcher m = JSON_LD.matcher(html);
        while (m.find()) {
            JsonNode node;
            try {
                node = lenientJson.readValue(m.group(1).trim());
            } catch (Exception e) {
                continue;
            }
            if (!"JobPosting".equals(node.path("@type").asText())) {
                continue;
            }
            String description = Html.toPlainText(node.path("description").asText(""));
            if (description.isBlank()) {
                continue;
            }
            String place = jsonLdPlace(node.path("jobLocation"));
            return new RawPosting(job.id(), job.title(), place != null ? place : job.place(),
                    description, job.url(), isoDate(node.path("datePosted").asText(null)));
        }

        Map<String, String> fields = fields(html);
        String description = fields.get("");
        if (description == null || description.isBlank()) {
            return null;
        }
        // Siemens states a level rather than years; screening reads the text.
        String level = fields.get("Experience level");
        if (level != null) {
            description = "Experience level: " + level + "\n\n" + description;
        }
        String place = fields.get("Location(s)");
        return new RawPosting(job.id(), job.title(),
                place != null ? place.replace(" - ", ", ") : job.place(),
                description, job.url(), postedSince(fields.get("Posted since")));
    }

    /** Label to text for each field of a Siemens-style page; the unlabelled one (the description) under "". */
    static Map<String, String> fields(String html) {
        Map<String, String> fields = new LinkedHashMap<>();
        List<Integer> starts = new ArrayList<>();
        Matcher start = FIELD_START.matcher(html);
        while (start.find()) {
            starts.add(start.start());
        }
        for (int i = 0; i < starts.size(); i++) {
            // A field ends at the next one or at the end of its article; Siemens
            // puts the description alone in a second article.
            int end = i + 1 < starts.size() ? starts.get(i + 1) : html.length();
            int articleEnd = html.indexOf("</article>", starts.get(i));
            if (articleEnd >= 0 && articleEnd < end) {
                end = articleEnd;
            }
            String field = html.substring(starts.get(i), end);
            Matcher label = FIELD_LABEL.matcher(field);
            String name = "";
            String value = Html.toPlainText(field).trim();
            if (label.find()) {
                name = Html.toPlainText(label.group(1)).trim();
                value = Html.toPlainText(field.substring(label.end())).trim();
            }
            if (!value.isEmpty()) {
                // The longest unlabelled block is the description.
                if (name.isEmpty() && fields.containsKey("") && fields.get("").length() >= value.length()) {
                    continue;
                }
                fields.put(name, value);
            }
        }
        return fields;
    }

    private static String jsonLdPlace(JsonNode jobLocation) {
        JsonNode first = jobLocation.isArray() ? jobLocation.path(0) : jobLocation;
        JsonNode address = first.path("address");
        StringJoiner place = new StringJoiner(", ");
        for (String part : List.of("addressLocality", "addressRegion", "addressCountry")) {
            JsonNode value = address.path(part);
            String text = value.isObject() ? value.path("name").asText("") : value.asText("");
            if (!text.isBlank() && !"null".equals(text)) {
                place.add(text.trim());
            }
        }
        return place.length() == 0 ? null : place.toString();
    }

    private static LocalDate isoDate(String raw) {
        if (raw == null || raw.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate postedSince(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), POSTED_SINCE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
