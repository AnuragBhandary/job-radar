package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads careers sites built on Phenom, one locale's sitemap per board.
 *
 * <p>A Phenom site splits its jobs into numbered sitemaps per locale
 * ({@code /in/en/sitemap1.xml}) with job URLs {@code /<locale>/job/<id>/<Title>},
 * and each job page carries a JSON-LD {@code JobPosting} with the place and a
 * real posting date. Lowe's India has its own locale with 46 jobs (2026-10-23),
 * where the US locale has some 20,000 store jobs across nine sitemaps.
 *
 * <p><b>The token</b> is {@code name/host/path-to-sitemap}:
 * {@code lowes/talent.lowes.com/in/en/sitemap1.xml}. The locale is the path
 * before the sitemap's file name.
 */
@Component
public class PhenomFetcher extends JsonLdSitemapFetcher {

    public PhenomFetcher(HttpFetchClient http, ObjectMapper json, TitleFilter titleFilter,
            StoredPostings stored) {
        super(http, json, titleFilter, stored);
    }

    @Override
    public Source source() {
        return Source.PHENOM;
    }

    /** {@code name/host/locale.../sitemapN.xml}, as seeded. */
    record Board(String name, String host, String locale, String sitemapPath) {

        static Board parse(String token) throws FetchException {
            String[] parts = token == null ? new String[0] : token.split("/", 3);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank()
                    || !parts[2].endsWith(".xml") || !parts[2].contains("/")) {
                throw new FetchException("Phenom token must be name/host/locale/sitemap.xml, got: " + token);
            }
            String path = parts[2];
            return new Board(parts[0], parts[1], path.substring(0, path.lastIndexOf('/')), path);
        }
    }

    @Override
    protected String sitemapUrl(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);
        return "https://" + board.host() + "/" + board.sitemapPath();
    }

    @Override
    protected Pattern jobUrl(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);
        // The id comes before the title in a Phenom URL; the lookahead captures the
        // title first, as group 2, so the groups keep the base class's order.
        return Pattern.compile("(https://" + Pattern.quote(board.host()) + "/" + Pattern.quote(board.locale())
                + "/job/(?=[^/<]+/([^/<?]+))([^/<]+)/[^/<?]+)");
    }

    @Override
    protected List<String> textFields() {
        return List.of("description", "responsibilities", "qualifications");
    }

    @Override
    protected String place(JsonNode posting, String text) {
        JsonNode locations = posting.path("jobLocation");
        List<String> places = new ArrayList<>();
        for (JsonNode location : locations.isArray() ? locations : List.of(locations)) {
            JsonNode address = location.path("address");
            String city = address.path("addressLocality").asText("").trim();
            String country = address.path("addressCountry").asText("").trim();
            String place = city.isEmpty() ? country : country.isEmpty() ? city : city + ", " + country;
            if (!place.isEmpty()) {
                places.add(place);
            }
        }
        return String.join("; ", places);
    }

    @Override
    protected LocalDate postedDate(JsonNode posting) {
        String value = posting.path("datePosted").asText("");
        try {
            return value.length() >= 10 ? LocalDate.parse(value.substring(0, 10)) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
