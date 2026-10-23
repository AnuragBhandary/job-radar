package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads Société Générale's careers site, one board.
 *
 * <p>The sitemap lists the English job pages as
 * {@code /en/job-offers/<slug>-<reference>-en} (662 on 2026-10-23, the Bangalore
 * service centre among them), and each page's JSON-LD carries the city and
 * country, the text and a real posting date ("2026/10/23"). The French pages
 * repeat the same jobs and are skipped.
 */
@Component
public class SocieteGeneraleFetcher extends JsonLdSitemapFetcher {

    public static final String BOARD = "societegenerale";

    private static final Pattern JOB_URL = Pattern.compile(
            "(https://careers\\.societegenerale\\.com/en/job-offers/([a-z0-9-]+?)-([0-9A-Z]{6,})-en)");

    public SocieteGeneraleFetcher(HttpFetchClient http, ObjectMapper json, TitleFilter titleFilter,
            StoredPostings stored) {
        super(http, json, titleFilter, stored);
    }

    @Override
    public Source source() {
        return Source.SOCGEN;
    }

    @Override
    protected String sitemapUrl(String boardToken) {
        return "https://careers.societegenerale.com/sitemap.xml";
    }

    @Override
    protected Pattern jobUrl(String boardToken) {
        return JOB_URL;
    }

    @Override
    protected List<String> textFields() {
        return List.of("description");
    }

    @Override
    protected String place(JsonNode posting, String text) {
        JsonNode address = posting.path("jobLocation").path("address");
        String city = address.path("addressLocality").asText("").trim();
        String country = address.path("addressCountry").asText("").trim();
        return city.isEmpty() ? country : country.isEmpty() ? city : city + ", " + country;
    }

    @Override
    protected LocalDate postedDate(JsonNode posting) {
        String value = posting.path("datePosted").asText("");
        try {
            return value.length() >= 10 ? LocalDate.parse(value.substring(0, 10).replace('/', '-')) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
