package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An employer's own careers site whose sitemap lists its job pages and whose job
 * pages carry a JSON-LD {@code JobPosting}, read one site per board.
 *
 * <p>{@link SitemapFetcher} covers the sites whose job URLs contain
 * {@code /job/} and the place. This covers the ones that do not: each subclass
 * names its sitemap, the pattern of its job URLs and how it finds the place.
 * Titles are screened from the URL slug before any page is read, and a job
 * already stored is reused, so a run reads only new jobs.
 */
public abstract class JsonLdSitemapFetcher implements AtsFetcher {

    private static final Pattern JSON_LD = Pattern.compile(
            "<script[^>]+application/ld\\+json[^>]*>(.*?)</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** One job page as the sitemap lists it; the slug's words stand in for the title. */
    public record Listed(String id, String url, String slugText) {
    }

    private final HttpFetchClient http;
    private final ObjectReader lenientJson;
    private final TitleFilter titleFilter;
    private final StoredPostings stored;

    protected JsonLdSitemapFetcher(HttpFetchClient http, ObjectMapper json, TitleFilter titleFilter,
            StoredPostings stored) {
        this.http = http;
        // Job text pasted from word processors leaves raw control characters in
        // JSON strings, as on the sites SitemapFetcher reads.
        this.lenientJson = json.readerFor(JsonNode.class)
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
        this.titleFilter = titleFilter;
        this.stored = stored;
    }

    protected abstract String sitemapUrl();

    /**
     * Matches a job page inside {@code <loc>}: group 1 the URL, group 2 the slug
     * whose words stand for the title, group 3 the job id.
     */
    protected abstract Pattern jobUrl();

    /** Where the job is, from the JSON-LD posting or the job text. */
    protected abstract String place(JsonNode posting, String text);

    /** The JSON-LD fields whose text makes the description, in order. */
    protected List<String> textFields() {
        return List.of("description", "responsibilities", "skills");
    }

    /** The posted date, where the site's datePosted means one. None by default. */
    protected LocalDate postedDate(JsonNode posting) {
        return null;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        StoredPostings.Known known = stored.open(source(), boardToken);
        List<RawPosting> postings = new ArrayList<>();
        for (Listed job : listed(http.get(sitemapUrl(), "sitemap-" + boardToken))) {
            if (!titleFilter.screen(TitleFilter.slugWithRanges(job.slugText())).accepted()) {
                continue;
            }
            RawPosting reused = known.reuse(job.id());
            if (reused != null) {
                postings.add(reused);
                continue;
            }
            RawPosting read = parsePage(http.get(job.url(), null), job);
            if (read != null) {
                postings.add(read);
            }
        }
        return FetchBatch.of(postings);
    }

    /** The job pages in the sitemap, without the site's other pages, each once. */
    public List<Listed> listed(String sitemap) {
        List<Listed> jobs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = Pattern.compile("<loc>\\s*" + jobUrl().pattern() + "\\s*</loc>").matcher(sitemap);
        while (m.find()) {
            if (seen.add(m.group(3))) {
                jobs.add(new Listed(m.group(3), m.group(1), m.group(2).replace('-', ' ')));
            }
        }
        return jobs;
    }

    public RawPosting parsePage(String html, Listed job) {
        Matcher m = JSON_LD.matcher(html);
        while (m.find()) {
            JsonNode posting;
            try {
                posting = lenientJson.readValue(m.group(1).trim());
            } catch (Exception e) {
                continue;
            }
            if (!"JobPosting".equals(posting.path("@type").asText())) {
                continue;
            }
            String title = posting.path("title").asText(posting.path("name").asText(null));
            if (title == null || title.isBlank()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (String field : textFields()) {
                String part = Html.toPlainText(posting.path(field).asText(""));
                if (!part.isBlank()) {
                    text.append(text.isEmpty() ? "" : "\n\n").append(part);
                }
            }
            return new RawPosting(job.id(), Html.toPlainText(title).trim(),
                    place(posting, text.toString()), text.toString(), job.url(), postedDate(posting));
        }
        return null;
    }
}
