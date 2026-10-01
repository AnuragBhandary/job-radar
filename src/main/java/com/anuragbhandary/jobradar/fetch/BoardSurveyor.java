package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.WorkMode;
import com.anuragbhandary.jobradar.filter.LocationClassifier;
import com.anuragbhandary.jobradar.filter.LocationProfile;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Counts what one board has in the countries this search targets, cheaply.
 *
 * <p>Discovery turns up tens of thousands of boards, and only the ones with roles
 * the applicant could take are worth a place in the daily run. This answers that
 * with as few requests as the platform allows: one for Greenhouse, Lever, Ashby
 * and Recruitee (their normal fetch is one request), one list page for
 * SmartRecruiters, and the country breakdown Workday returns with its first page.
 *
 * <p>A remote role counts only when it is open to India or worldwide. Bare
 * "Remote" is usually American, and counting it would add thousands of US
 * startups to every run for nothing.
 */
@Component
public class BoardSurveyor {

    /** What a board holds, in the terms the keep-or-skip decision needs. */
    public record Survey(int total, int india, int relocation, int remote) {

        public boolean hasTargetRoles() {
            return india + relocation + remote > 0;
        }
    }

    /** Workday's country names for the relocation countries. */
    static final Set<String> RELOCATION = Set.of("ireland", "germany", "netherlands");

    private static final Set<String> RELOCATION_CODES = Set.of("IE", "DE", "NL");

    private static final String SR_LIST =
            "https://api.smartrecruiters.com/v1/companies/%s/postings?limit=100&offset=0";

    private final Map<Source, AtsFetcher> fetchers = new EnumMap<>(Source.class);
    private final SmartRecruitersFetcher smartRecruiters;
    private final HttpFetchClient http;
    private final ObjectMapper json;
    private final LocationClassifier locations;
    private final TitleFilter titles;

    public BoardSurveyor(List<AtsFetcher> fetchers, SmartRecruitersFetcher smartRecruiters,
            HttpFetchClient http, ObjectMapper json,
            LocationClassifier locations, TitleFilter titles) {
        fetchers.forEach(f -> this.fetchers.put(f.source(), f));
        this.smartRecruiters = smartRecruiters;
        this.http = http;
        this.json = json;
        this.locations = locations;
        this.titles = titles;
    }

    public Survey survey(Source source, String token) throws FetchException {
        return switch (source) {
            case GREENHOUSE, LEVER, ASHBY, RECRUITEE -> {
                FetchBatch batch = fetchers.get(source).fetch(token);
                yield count(batch.postings(), batch.boardTotal());
            }
            case SMARTRECRUITERS -> smartRecruiters(token);
            case WORKDAY -> workday(token);
            default -> throw new IllegalArgumentException(source + " is not surveyed");
        };
    }

    private Survey smartRecruiters(String token) throws FetchException {
        JsonNode root = read(http.get(SR_LIST.formatted(token), null), token);
        List<RawPosting> stubs = new ArrayList<>();
        for (JsonNode summary : root.path("content")) {
            RawPosting stub = smartRecruiters.toStub(summary);
            if (stub != null) {
                stubs.add(stub);
            }
        }
        // SmartRecruiters answers 200 with nothing for any name at all, so an
        // empty list here is no evidence of a board.
        return count(stubs, root.path("totalFound").asInt(stubs.size()));
    }

    /** Counts postings whose title passes and whose location is a target. */
    Survey count(List<RawPosting> postings, int total) {
        int india = 0;
        int relocation = 0;
        int remote = 0;
        for (RawPosting p : postings) {
            if (p.title() == null || !titles.screen(p.title()).accepted()) {
                continue;
            }
            LocationProfile where = locations.classify(p.location(), p.title(), null);
            if (!where.verdict().accepted()) {
                continue;
            }
            List<String> codes = where.allCountryCodes() == null ? List.of() : where.allCountryCodes();
            if (where.isIndia() || codes.contains("IN") || where.allowsIndia()) {
                india++;
            } else if ((where.countryCode() != null && RELOCATION_CODES.contains(where.countryCode()))
                    || codes.stream().anyMatch(c -> c != null && RELOCATION_CODES.contains(c))) {
                relocation++;
            } else if (where.workMode() == WorkMode.REMOTE_GLOBAL) {
                remote++;
            }
        }
        return new Survey(total, india, relocation, remote);
    }

    /**
     * Workday reports jobs per country with its first page of results, so one
     * request answers the question for a site of any size. Titles are not
     * screened: a Workday site with India desks is worth fetching, and the
     * fetcher screens titles itself.
     */
    private Survey workday(String token) throws FetchException {
        String[] parts = token.split("/");
        if (parts.length < 3) {
            throw new FetchException("Workday token must be tenant/wdN/site, got: " + token);
        }
        String base = "https://%s.%s.myworkdayjobs.com/wday/cxs/%s/%s/jobs"
                .formatted(parts[0], parts[1], parts[0], parts[2]);
        JsonNode first = read(http.post(base,
                "{\"appliedFacets\":{},\"limit\":1,\"offset\":0,\"searchText\":\"\"}", null), token);
        int total = first.path("total").asInt(0);
        if (total == 0) {
            return new Survey(0, 0, 0, 0);
        }
        Map<String, Integer> countries = workdayCountries(first);
        if (countries == null) {
            countries = classifiedLocations(first);
        }
        if (countries.isEmpty()) {
            // No location facet at all: search per country and classify what
            // comes back. The hit count alone is not evidence - Workday's search
            // matches "india" in "Indialantic, FL" and "IN" for Indiana.
            for (String country : List.of("india", "ireland", "germany", "netherlands")) {
                JsonNode hits = read(http.post(base,
                        "{\"appliedFacets\":{},\"limit\":20,\"offset\":0,\"searchText\":\"" + country + "\"}",
                        null), token);
                for (JsonNode job : hits.path("jobPostings")) {
                    String place = countryOf(job.path("locationsText").asText(null));
                    if (place != null) {
                        countries.merge(place, 1, Integer::sum);
                    }
                }
            }
        }
        int india = 0;
        int relocation = 0;
        for (Map.Entry<String, Integer> c : countries.entrySet()) {
            String name = c.getKey();
            if (name.equals("india")) {
                india += c.getValue();
            } else if (RELOCATION.stream().anyMatch(name::startsWith)) {
                // "Netherlands" and "Netherlands, Kingdom of the" both; never
                // "Northern Ireland", which is the United Kingdom.
                relocation += c.getValue();
            }
        }
        return new Survey(total, india, relocation, 0);
    }

    /**
     * Jobs per target country from a site's location facets ("Bengaluru, India",
     * "Mishawaka, IN"), each read by the same classifier screening uses.
     */
    Map<String, Integer> classifiedLocations(JsonNode response) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        collectLocations(response.path("facets"), false, counts);
        return counts;
    }

    private void collectLocations(JsonNode facets, boolean underLocation, Map<String, Integer> counts) {
        for (JsonNode facet : facets) {
            boolean isLocation = underLocation
                    || facet.path("facetParameter").asText("").toLowerCase(Locale.ROOT).contains("location");
            JsonNode values = facet.path("values");
            if (values.isArray() && values.size() > 0 && values.get(0).has("values")) {
                collectLocations(values, isLocation, counts);
                continue;
            }
            if (!isLocation) {
                continue;
            }
            for (JsonNode v : values) {
                String place = countryOf(v.path("descriptor").asText(null));
                if (place != null) {
                    counts.merge(place, v.path("count").asInt(0), Integer::sum);
                }
            }
        }
    }

    /** "india", "ireland", "germany" or "netherlands" for a location string, else null. */
    String countryOf(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        LocationProfile where = locations.classify(location, null, null);
        if (!where.verdict().accepted() || where.countryCode() == null) {
            return null;
        }
        return switch (where.countryCode()) {
            case "IN" -> "india";
            case "IE" -> "ireland";
            case "DE" -> "germany";
            case "NL" -> "netherlands";
            default -> null;
        };
    }

    /** Jobs per lowercased country name, or null when the site has no country facet. */
    static Map<String, Integer> workdayCountries(JsonNode response) {
        for (JsonNode facet : response.path("facets")) {
            List<JsonNode> groups = new ArrayList<>();
            if ("locationCountry".equals(facet.path("facetParameter").asText())) {
                groups.add(facet);
            }
            for (JsonNode sub : facet.path("values")) {
                if ("locationCountry".equals(sub.path("facetParameter").asText())) {
                    groups.add(sub);
                }
            }
            for (JsonNode group : groups) {
                Map<String, Integer> counts = new java.util.HashMap<>();
                for (JsonNode v : group.path("values")) {
                    counts.merge(v.path("descriptor").asText("").toLowerCase(Locale.ROOT),
                            v.path("count").asInt(0), Integer::sum);
                }
                return counts;
            }
        }
        return null;
    }

    private JsonNode read(String body, String token) throws FetchException {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new FetchException("Unreadable response from " + token, e);
        }
    }
}
