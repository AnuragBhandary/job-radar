package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads D. E. Shaw India's careers site, one board.
 *
 * <p>The sitemap lists every opening as {@code /careers/<slug>-<id>}, and each
 * job page carries a JSON-LD {@code JobPosting} with the description,
 * responsibilities and skills. {@link SitemapFetcher} cannot take it: the URLs
 * have no {@code /job/} segment and no place, and the JSON-LD address is empty.
 * Every opening is in India, so the place is the offices the text names
 * (Hyderabad, Bengaluru, Gurugram), or India when it names none.
 *
 * <p>Titles are screened from the slug before any page is read, and a job
 * already stored is reused, as for Apple, so a run reads only new jobs.
 */
@Component
public class DeShawIndiaFetcher implements AtsFetcher {

    public static final String BOARD = "deshawindia";

    private static final String SITEMAP = "https://www.deshawindia.com/sitemap.xml";

    private static final Pattern JOB_URL = Pattern.compile(
            "<loc>\\s*(https://www\\.deshawindia\\.com/careers/([A-Za-z0-9-]+?)-(\\d+))\\s*</loc>");

    private static final Pattern JSON_LD = Pattern.compile(
            "<script[^>]+application/ld\\+json[^>]*>(.*?)</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern OFFICE = Pattern.compile(
            "\\b(Hyderabad|Bengaluru|Bangalore|Gurugram|Gurgaon|Mumbai)\\b");

    private final HttpFetchClient http;
    private final ObjectReader lenientJson;
    private final TitleFilter titleFilter;
    private final StoredPostings stored;

    public DeShawIndiaFetcher(HttpFetchClient http, ObjectMapper json, TitleFilter titleFilter,
            StoredPostings stored) {
        this.http = http;
        this.lenientJson = json.readerFor(JsonNode.class)
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
        this.titleFilter = titleFilter;
        this.stored = stored;
    }

    @Override
    public Source source() {
        return Source.DESHAW;
    }

    /** One job page as the sitemap lists it. */
    record Listed(String id, String url, String slugText) {
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        StoredPostings.Known known = stored.open(Source.DESHAW, boardToken);
        List<RawPosting> postings = new ArrayList<>();
        for (Listed job : listed(http.get(SITEMAP, "deshaw-sitemap"))) {
            if (!titleFilter.screen(job.slugText()).accepted()) {
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

    /** The job pages in the sitemap, without the site's other careers pages. */
    static List<Listed> listed(String sitemap) {
        List<Listed> jobs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = JOB_URL.matcher(sitemap);
        while (m.find()) {
            if (seen.add(m.group(3))) {
                jobs.add(new Listed(m.group(3), m.group(1), m.group(2).replace('-', ' ')));
            }
        }
        return jobs;
    }

    RawPosting parsePage(String html, Listed job) {
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
            for (String field : List.of("description", "responsibilities", "skills")) {
                String part = Html.toPlainText(posting.path(field).asText(""));
                if (!part.isBlank()) {
                    text.append(text.isEmpty() ? "" : "\n\n").append(part);
                }
            }
            // datePosted is the time the page was served, not when the job opened.
            return new RawPosting(job.id(), Html.toPlainText(title).trim(), place(text.toString()),
                    text.toString(), job.url(), null);
        }
        return null;
    }

    /** The offices the text names, each with India, or India alone. */
    static String place(String text) {
        Set<String> offices = new LinkedHashSet<>();
        Matcher m = OFFICE.matcher(text);
        while (m.find()) {
            offices.add(m.group(1) + ", India");
        }
        return offices.isEmpty() ? "India" : String.join("; ", offices);
    }
}
