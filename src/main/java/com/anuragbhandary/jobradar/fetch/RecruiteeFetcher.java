package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * Reads a Recruitee job board.
 *
 * <p>One request per board and everything in it - description, requirements,
 * locations and dates - which makes it the cheapest of the six to fetch.
 *
 * <p>Two things are particular to it.
 *
 * <p><b>The description is split in two, like Lever's.</b> {@code description} is
 * the prose about the company and the role; {@code requirements} is the list that
 * says how many years they want. Reading only the first means screening every
 * Recruitee posting on its marketing copy, which is the exact bug the Lever
 * fetcher was written to avoid.
 *
 * <p><b>The remote flag is not a location.</b> A posting can be {@code remote:
 * true} while {@code location} still reads "Utrecht, Netherlands", because
 * Recruitee models remote as an attribute of a job that still belongs to an
 * office. That combination is precisely the "Remote (Argentina)" trap the
 * geography filter exists for, so the flag is appended to the location string
 * rather than replacing it - and the filter decides, as it does everywhere else.
 */
@Component
public class RecruiteeFetcher implements AtsFetcher {

    private static final String BOARD_URL = "https://%s.recruitee.com/api/offers/";

    /** Recruitee timestamps: "2026-09-02 11:36:49 UTC". */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'");

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public RecruiteeFetcher(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Source source() {
        return Source.RECRUITEE;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        return FetchBatch.of(parse(
                http.get(BOARD_URL.formatted(boardToken), "recruitee-" + boardToken),
                boardToken));
    }

    List<RawPosting> parse(String body, String boardToken) throws FetchException {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            throw new FetchException("Unparseable response from board " + boardToken, e);
        }

        // An unknown token answers {"error":"Not Found"}, which is a real
        // distinction worth keeping: unlike SmartRecruiters, an empty board here
        // is genuinely a board with no openings.
        if (root.has("error")) {
            throw new FetchException("Board " + boardToken + " returned: "
                    + root.path("error").asText());
        }
        JsonNode offers = root.path("offers");
        if (!offers.isArray()) {
            throw new FetchException(
                    "Board " + boardToken + " has no offers array; token may be wrong");
        }

        List<RawPosting> postings = new ArrayList<>(offers.size());
        for (JsonNode offer : offers) {
            String id = offer.path("id").asText(null);
            String title = offer.path("title").asText(null);
            if (id == null || title == null || title.isBlank()) {
                continue;
            }
            // Recruitee keeps closed offers in the feed with a status.
            String status = offer.path("status").asText("published");
            if (!"published".equalsIgnoreCase(status)) {
                continue;
            }

            postings.add(new RawPosting(
                    id,
                    title.trim(),
                    locations(offer),
                    description(offer),
                    applyUrl(offer),
                    publishedDate(offer)));
        }
        return postings;
    }

    /** Prose and requirements together, tags stripped. */
    private static String description(JsonNode offer) {
        StringBuilder text = new StringBuilder(
                Html.toPlainText(offer.path("description").asText("")));
        String requirements = offer.path("requirements").asText("");
        if (!requirements.isBlank()) {
            text.append("\n\nRequirements\n").append(Html.toPlainText(requirements));
        }
        return text.toString().trim();
    }

    /**
     * Every office named, plus the remote flag as a word.
     *
     * <p>{@code locations[]} is authoritative when present - a posting open in
     * three cities lists all three there and only the first in {@code location}.
     */
    private static String locations(JsonNode offer) {
        Set<String> all = new LinkedHashSet<>();
        for (JsonNode location : offer.path("locations")) {
            String value = location.path("name").asText(null);
            String country = location.path("country").asText(null);
            if (value != null && !value.isBlank()) {
                all.add(country == null || country.isBlank() ? value : value + ", " + country);
            }
        }
        String primary = offer.path("location").asText(null);
        if (primary != null && !primary.isBlank()) {
            all.add(primary);
        }

        StringJoiner joined = new StringJoiner("; ");
        all.forEach(joined::add);
        // Appended, not substituted. "Remote" beside "Utrecht, Netherlands" is
        // what the board means, and flattening it to "Remote" would hide a
        // country restriction the geography filter is built to catch.
        if (offer.path("remote").asBoolean(false)) {
            joined.add("Remote");
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    /**
     * The apply URL, preferring the one that lands on the form.
     *
     * <p>{@code careers_apply_url} opens the application directly;
     * {@code careers_url} is the description page with an Apply button. The first
     * saves the form filler a click and a page load.
     */
    private static String applyUrl(JsonNode offer) {
        String apply = offer.path("careers_apply_url").asText(null);
        if (apply != null && !apply.isBlank()) {
            return apply;
        }
        return offer.path("careers_url").asText(null);
    }

    private static LocalDate publishedDate(JsonNode offer) {
        for (String field : List.of("published_at", "created_at")) {
            String value = offer.path(field).asText(null);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                return LocalDate.parse(value.trim(), TIMESTAMP);
            } catch (DateTimeParseException e) {
                try {
                    return LocalDate.parse(value.substring(0, 10));
                } catch (RuntimeException ignored) {
                    // Neither shape. An absent date is reported as absent.
                }
            }
        }
        return null;
    }
}
