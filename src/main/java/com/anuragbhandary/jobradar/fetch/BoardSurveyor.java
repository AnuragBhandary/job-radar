package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.WorkMode;
import com.anuragbhandary.jobradar.filter.LocationClassifier;
import com.anuragbhandary.jobradar.filter.LocationProfile;
import com.anuragbhandary.jobradar.filter.TargetPlaces;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * What a board holds, in the terms the keep-or-skip decision needs.
     *
     * @param abroad     jobs per recommended relocation country, by ISO code
     * @param perCountry for platforms whose board is one country of a site, what
     *                   each wanted country's board adds to the token: a Workday
     *                   search ("india") or an Oracle location id
     */
    public record Survey(int total, int india, int relocation, int remote,
            Map<String, Integer> abroad, Map<String, String> perCountry) {

        public Survey(int total, int india, int relocation, int remote) {
            this(total, india, relocation, remote, Map.of(), Map.of());
        }

        public boolean hasTargetRoles() {
            return india + relocation + remote > 0;
        }
    }

    /**
     * What to type into a Workday search to find one country's jobs. Workday's
     * search matches location text, which is how the "/india" boards work.
     */
    public static final Map<String, String> WORKDAY_SEARCH = Map.of(
            "IN", "india", "IE", "ireland", "DE", "germany", "NL", "netherlands",
            "GB", "united kingdom", "AE", "united arab emirates",
            "SA", "saudi arabia", "QA", "qatar");

    private static final String SR_LIST =
            "https://api.smartrecruiters.com/v1/companies/%s/postings?limit=100&offset=0";

    private final Map<Source, AtsFetcher> fetchers = new EnumMap<>(Source.class);
    private final SmartRecruitersFetcher smartRecruiters;
    private final HttpFetchClient http;
    private final ObjectMapper json;
    private final LocationClassifier locations;
    private final TargetPlaces places;
    private final TitleFilter titles;

    public BoardSurveyor(List<AtsFetcher> fetchers, SmartRecruitersFetcher smartRecruiters,
            HttpFetchClient http, ObjectMapper json,
            LocationClassifier locations, TargetPlaces places, TitleFilter titles) {
        fetchers.forEach(f -> this.fetchers.put(f.source(), f));
        this.smartRecruiters = smartRecruiters;
        this.http = http;
        this.json = json;
        this.locations = locations;
        this.places = places;
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
            case EIGHTFOLD -> eightfold(token);
            case ORACLE_HCM -> oracle(token);
            default -> throw new IllegalArgumentException(source + " is not surveyed");
        };
    }

    /** An Eightfold site: the sitemap's URLs carry each job's title and place. */
    private Survey eightfold(String token) throws FetchException {
        EightfoldFetcher.Board board = EightfoldFetcher.Board.parse(token);
        List<EightfoldFetcher.Listed> jobs = EightfoldFetcher.parseSitemap(http.get(board.sitemap(), null));
        if (jobs.isEmpty()) {
            throw new FetchException("No job URLs in the sitemap of " + token);
        }
        List<RawPosting> slugs = jobs.stream()
                .map(j -> new RawPosting(j.id(), j.slugText(), j.slugText(), null, j.url(), null))
                .toList();
        return count(slugs, jobs.size());
    }

    private static final String ORACLE_FACETS = "https://%s/hcmRestApi/resources/latest/"
            + "recruitingCEJobRequisitions?onlyData=true&expand=locationsFacet"
            + "&finder=findReqs;siteNumber=%s,facetsList=LOCATIONS,limit=1";

    /**
     * An Oracle site: its location facet gives jobs per place with the place's id,
     * and a board is one country, so the survey records each wanted country's id.
     * The facet lists the site's largest places only; a country below that cut
     * is missed until it grows.
     */
    private Survey oracle(String token) throws FetchException {
        String[] parts = token.split("/");
        if (parts.length != 2) {
            throw new FetchException("Oracle discovery token must be host/site, got: " + token);
        }
        JsonNode search = read(http.get(ORACLE_FACETS.formatted(parts[0], parts[1]), null), token)
                .path("items").path(0);
        int total = search.path("TotalJobsCount").asInt(0);
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> ids = new HashMap<>();
        for (JsonNode place : search.path("locationsFacet")) {
            String code = places.countryCode(place.path("Name").asText(null));
            if (code == null || !(code.equals("IN") || places.recommendsMovingTo(code))) {
                continue;
            }
            int count = place.path("TotalCount").asInt(0);
            // The country itself carries the most jobs of all its places.
            if (count > counts.getOrDefault(code, -1)) {
                counts.put(code, count);
                ids.put(code, place.path("Id").asText());
            }
        }
        int india = counts.getOrDefault("IN", 0);
        Map<String, Integer> abroad = new HashMap<>(counts);
        abroad.remove("IN");
        Survey counted = survey(total, india, 0, abroad);
        return new Survey(counted.total(), india, counted.relocation(), 0, counted.abroad(), Map.copyOf(ids));
    }

    /**
     * The employer's own name where the platform publishes one, else null. The
     * label becomes the company in the tracker, and Greenhouse board names are
     * often nothing like it: "stage" is KKR, "india" is AQR India.
     */
    public String companyName(Source source, String token) {
        if (source == Source.ORACLE_HCM) {
            try {
                String[] parts = token.split("/");
                JsonNode req = read(http.get(("https://%s/hcmRestApi/resources/latest/recruitingCEJobRequisitions"
                        + "?onlyData=true&finder=findReqs;siteNumber=%s,limit=1").formatted(parts[0], parts[1]),
                        null), token).path("items").path(0).path("requisitionList").path(0);
                String name = req.path("LegalEmployer").asText(null);
                if (name == null || name.isBlank()) {
                    name = req.path("BusinessUnit").asText(null);
                }
                return name == null || name.isBlank() ? null : name.strip();
            } catch (FetchException | RuntimeException e) {
                return null;
            }
        }
        if (source == Source.EIGHTFOLD) {
            // name/host/domain: the domain is the employer's, "paypal.com".
            String domain = token.split("/")[2];
            String name = domain.split("\\.")[0];
            return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
        }
        if (source != Source.GREENHOUSE) {
            return null;
        }
        try {
            String name = read(http.get("https://boards-api.greenhouse.io/v1/boards/" + token, null), token)
                    .path("name").asText(null);
            return name == null || name.isBlank() ? null : name.strip();
        } catch (FetchException e) {
            return null;
        }
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
        int remote = 0;
        Map<String, Integer> abroad = new HashMap<>();
        for (RawPosting p : postings) {
            if (p.title() == null || !titles.screen(p.title()).accepted()) {
                continue;
            }
            LocationProfile where = locations.classify(p.location(), p.title(), null);
            if (!where.verdict().accepted()) {
                continue;
            }
            List<String> codes = where.allCountryCodes() == null ? List.of() : where.allCountryCodes();
            List<String> moveTo = places.recommendedAbroad(where);
            if (where.isIndia() || codes.contains("IN") || where.allowsIndia()) {
                india++;
            } else if (!moveTo.isEmpty()) {
                abroad.merge(moveTo.getFirst(), 1, Integer::sum);
            } else if (where.workMode() == WorkMode.REMOTE_GLOBAL) {
                remote++;
            }
        }
        return survey(total, india, remote, abroad);
    }

    private static Survey survey(int total, int india, int remote, Map<String, Integer> abroad) {
        return new Survey(total, india, abroad.values().stream().mapToInt(Integer::intValue).sum(),
                remote, Map.copyOf(abroad), Map.of());
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
        // Jobs per ISO code: from the country facet where the site has one,
        // else from its place names, each read by the screening classifier.
        Map<String, Integer> byCode = new HashMap<>();
        Map<String, Integer> named = workdayCountries(first);
        if (named == null) {
            named = new HashMap<>();
            collectLocations(first.path("facets"), false, named);
        }
        named.forEach((place, count) -> {
            String code = places.countryCode(place);
            if (code != null) {
                byCode.merge(code, count, Integer::sum);
            }
        });
        if (named.isEmpty()) {
            // No location facet at all: search per country and classify what
            // comes back. The hit count alone is not evidence - Workday's search
            // matches "india" in "Indialantic, FL" and "IN" for Indiana.
            for (Map.Entry<String, String> country : WORKDAY_SEARCH.entrySet()) {
                if (!country.getKey().equals("IN") && !places.recommendsMovingTo(country.getKey())) {
                    continue;
                }
                JsonNode hits = read(http.post(base,
                        "{\"appliedFacets\":{},\"limit\":20,\"offset\":0,\"searchText\":\""
                                + country.getValue() + "\"}", null), token);
                for (JsonNode job : hits.path("jobPostings")) {
                    String code = places.countryCode(job.path("locationsText").asText(null));
                    if (country.getKey().equals(code)) {
                        byCode.merge(code, 1, Integer::sum);
                    }
                }
            }
        }
        int india = byCode.getOrDefault("IN", 0);
        Map<String, Integer> abroad = new HashMap<>();
        byCode.forEach((code, count) -> {
            if (places.recommendsMovingTo(code) && count > 0) {
                abroad.put(code, count);
            }
        });
        Map<String, String> searches = new HashMap<>();
        if (india > 0) {
            searches.put("IN", WORKDAY_SEARCH.get("IN"));
        }
        abroad.keySet().forEach(code -> {
            if (WORKDAY_SEARCH.containsKey(code)) {
                searches.put(code, WORKDAY_SEARCH.get(code));
            }
        });
        Survey counted = survey(total, india, 0, abroad);
        return new Survey(counted.total(), counted.india(), counted.relocation(), 0,
                counted.abroad(), Map.copyOf(searches));
    }

    /** Jobs per place name from a site's location facets ("Bengaluru, India", "Mishawaka, IN"). */
    static void collectLocations(JsonNode facets, boolean underLocation, Map<String, Integer> counts) {
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
                String place = v.path("descriptor").asText(null);
                if (place != null && !place.isBlank()) {
                    counts.merge(place, v.path("count").asInt(0), Integer::sum);
                }
            }
        }
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
