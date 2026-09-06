package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.config.BoardTokenSeeder;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.fetch.HttpFetchClient.HttpResult;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Tests a candidate token against all four company-board platforms.
 *
 * <p>This is the discovery half of the tool, and historically the productive
 * one: sweeping the known boards for four days produced nothing new, while one
 * round of token probing found the best match of the search - a company that was
 * on no list.
 *
 * <p>Amazon is not probed. Its "tokens" are country codes, not companies, and all
 * four are already swept on every run.
 */
@Component
public class TokenProber {

    /** Probing is a guess by definition, so each platform gets exactly one request. */
    private static final List<Source> PLATFORMS =
            List.of(Source.GREENHOUSE, Source.ASHBY, Source.LEVER, Source.SMARTRECRUITERS);

    private final HttpFetchClient http;
    private final ObjectMapper json;
    private final BoardTokenRepository boards;

    public TokenProber(HttpFetchClient http, ObjectMapper json, BoardTokenRepository boards) {
        this.http = http;
        this.json = json;
        this.boards = boards;
    }

    public List<ProbeResult> probe(String rawToken) {
        String token = rawToken.trim();
        List<ProbeResult> results = new ArrayList<>(PLATFORMS.size());

        if (BoardTokenSeeder.KNOWN_ABSENT.contains(normalise(token))) {
            // Confirmed absent from all four, twice. Re-testing costs four
            // requests to learn nothing.
            return List.of(ProbeResult.of(null, token, ProbeResult.Outcome.SKIPPED,
                    "on the confirmed-absent list; needs its real ATS found by hand"));
        }

        for (Source platform : PLATFORMS) {
            results.add(probeOne(platform, candidateFor(platform, token)));
        }
        return results;
    }

    private ProbeResult probeOne(Source platform, String token) {
        if (boards.findBySourceAndToken(platform, token).isPresent()) {
            return ProbeResult.of(platform, token, ProbeResult.Outcome.ALREADY_KNOWN,
                    "already a seeded board");
        }
        try {
            HttpResult response = http.getRaw(urlFor(platform, token), null);
            if (response.isAbsent()) {
                return ProbeResult.of(platform, token, ProbeResult.Outcome.ABSENT, "HTTP 404");
            }
            if (!response.isSuccess()) {
                return ProbeResult.of(platform, token, ProbeResult.Outcome.ERROR,
                        "HTTP " + response.status());
            }
            int count = countPostings(platform, response.body());
            return count > 0
                    ? new ProbeResult(platform, token, ProbeResult.Outcome.FOUND, count, null)
                    : new ProbeResult(platform, token, ProbeResult.Outcome.EMPTY, 0,
                            platform == Source.SMARTRECRUITERS
                                    // The distinction this whole class turns on.
                                    ? "answers 200 for unknown companies - not proof of absence"
                                    : "real board, no openings");
        } catch (FetchException e) {
            return ProbeResult.of(platform, token, ProbeResult.Outcome.ERROR, e.getMessage());
        }
    }

    private int countPostings(Source platform, String body) {
        try {
            JsonNode root = json.readTree(body);
            return switch (platform) {
                case GREENHOUSE, ASHBY -> root.path("jobs").size();
                case LEVER -> root.isArray() ? root.size() : 0;
                case SMARTRECRUITERS -> root.path("totalFound").asInt(0);
                case AMAZON -> 0;
            };
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Token conventions differ. Greenhouse, Ashby and Lever use lowercase handles;
     * SmartRecruiters uses the company's own casing, so its token is passed
     * through untouched.
     */
    private static String candidateFor(Source platform, String token) {
        return platform == Source.SMARTRECRUITERS ? token : normalise(token);
    }

    private static String normalise(String token) {
        return token.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String urlFor(Source platform, String token) {
        return switch (platform) {
            case GREENHOUSE ->
                    "https://boards-api.greenhouse.io/v1/boards/%s/jobs".formatted(token);
            case ASHBY -> "https://api.ashbyhq.com/posting-api/job-board/%s".formatted(token);
            case LEVER -> "https://api.lever.co/v0/postings/%s?mode=json".formatted(token);
            case SMARTRECRUITERS ->
                    "https://api.smartrecruiters.com/v1/companies/%s/postings?limit=1"
                            .formatted(token);
            case AMAZON -> throw new IllegalArgumentException("Amazon is not probed by token");
        };
    }
}
