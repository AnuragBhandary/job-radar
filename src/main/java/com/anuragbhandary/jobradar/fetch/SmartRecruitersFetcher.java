package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.GeoFilter;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a SmartRecruiters company board.
 *
 * <p>The only fetcher that makes a request per posting, because SmartRecruiters
 * is the only board whose list response carries no description at all - just a
 * title, a location and a link. The requirements live behind
 * {@code /postings/{id}}.
 *
 * <p>So the geography and title filters run <em>here</em>, before the detail
 * fetch, and only survivors are fetched individually. On a board like
 * DeliveryHero that is the difference between four requests and four hundred, of
 * which 99% would be discarded a moment later. It is the one place where a
 * fetcher may reasonably know about the filters, and the reason
 * {@link GeoFilter} and {@link TitleFilter} are injected into it.
 */
@Component
public class SmartRecruitersFetcher implements AtsFetcher {

    private static final Logger log = LoggerFactory.getLogger(SmartRecruitersFetcher.class);

    private static final String LIST_URL =
            "https://api.smartrecruiters.com/v1/companies/%s/postings?limit=%d&offset=%d";
    private static final String DETAIL_URL =
            "https://api.smartrecruiters.com/v1/companies/%s/postings/%s";

    private static final int PAGE_SIZE = 100;

    /** Guards against a pagination bug turning into an unbounded crawl. */
    private static final int MAX_PAGES = 20;

    private final HttpFetchClient http;
    private final ObjectMapper json;
    private final GeoFilter geoFilter;
    private final TitleFilter titleFilter;

    public SmartRecruitersFetcher(
            HttpFetchClient http, ObjectMapper json,
            GeoFilter geoFilter, TitleFilter titleFilter) {
        this.http = http;
        this.json = json;
        this.geoFilter = geoFilter;
        this.titleFilter = titleFilter;
    }

    @Override
    public Source source() {
        return Source.SMARTRECRUITERS;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        List<RawPosting> shortlist = new ArrayList<>();
        int boardTotal = 0;
        int skipped = 0;

        for (int page = 0; page < MAX_PAGES; page++) {
            int offset = page * PAGE_SIZE;
            String body = http.get(
                    LIST_URL.formatted(boardToken, PAGE_SIZE, offset),
                    page == 0 ? "smartrecruiters-" + boardToken : null);

            JsonNode root = read(body, boardToken);
            // The board's own count, so health reflects the board rather than our filters.
            boardTotal = root.path("totalFound").asInt(boardTotal);
            JsonNode content = root.path("content");
            if (!content.isArray()) {
                throw new FetchException(
                        "Board " + boardToken + " returned no content array; token may be wrong");
            }

            for (JsonNode summary : content) {
                RawPosting stub = toStub(summary);
                if (stub == null) {
                    continue;
                }
                if (passesCheapFilters(stub)) {
                    shortlist.add(stub);
                } else {
                    skipped++;
                }
            }

            if (content.size() < PAGE_SIZE) {
                break;
            }
        }

        log.debug("{}: {} postings shortlisted, {} filtered out before detail fetch",
                boardToken, shortlist.size(), skipped);

        List<RawPosting> detailed = new ArrayList<>(shortlist.size());
        for (RawPosting stub : shortlist) {
            detailed.add(withDescription(boardToken, stub));
        }
        return new FetchBatch(detailed, boardTotal);
    }

    private boolean passesCheapFilters(RawPosting stub) {
        return geoFilter.classify(stub.location(), stub.title()).verdict().accepted()
                && titleFilter.screen(stub.title()).accepted();
    }

    /** Fetches the description for one shortlisted posting. */
    private RawPosting withDescription(String boardToken, RawPosting stub) throws FetchException {
        String body = http.get(DETAIL_URL.formatted(boardToken, stub.externalId()), null);
        JsonNode detail = read(body, boardToken);
        return new RawPosting(
                stub.externalId(),
                stub.title(),
                stub.location(),
                describe(detail),
                detail.path("postingUrl").asText(stub.url()),
                stub.postedDate());
    }

    /** Job ad sections concatenated, tags stripped. */
    private static String describe(JsonNode detail) {
        JsonNode sections = detail.path("jobAd").path("sections");
        StringBuilder text = new StringBuilder();
        for (String name : List.of(
                "jobDescription", "qualifications", "additionalInformation")) {
            String html = sections.path(name).path("text").asText("");
            if (!html.isBlank()) {
                text.append(Html.toPlainText(html)).append("\n\n");
            }
        }
        return text.toString().trim();
    }

    private RawPosting toStub(JsonNode summary) {
        String id = summary.path("id").asText(null);
        String title = summary.path("name").asText(null);
        if (id == null || title == null) {
            return null;
        }
        return new RawPosting(id, title.trim(), location(summary.path("location")), null,
                summary.path("ref").asText(null),
                parseDate(summary.path("releasedDate").asText(null)));
    }

    /**
     * Builds a location string the geography filter can read.
     *
     * <p>SmartRecruiters gives a two-letter country code. It is expanded to the
     * country name here, because "in" and "de" match nothing in the filter's
     * word lists while "India" and "Germany" match directly - and, just as
     * importantly, "us" expands to "United States" and is then correctly
     * excluded.
     */
    private static String location(JsonNode location) {
        StringJoiner joined = new StringJoiner(", ");
        for (String field : List.of("city", "region")) {
            String value = location.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                joined.add(value);
            }
        }
        String countryCode = location.path("country").asText(null);
        if (countryCode != null && countryCode.length() == 2) {
            String country = Locale.of("", countryCode.toUpperCase(Locale.ROOT))
                    .getDisplayCountry(Locale.ENGLISH);
            joined.add(country.isBlank() ? countryCode : country);
        }
        if (location.path("remote").asBoolean(false)) {
            joined.add("Remote");
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    private JsonNode read(String body, String boardToken) throws FetchException {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new FetchException("Unparseable response from board " + boardToken, e);
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
