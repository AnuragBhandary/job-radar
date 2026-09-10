package com.anuragbhandary.jobradar.filter;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Place names, and the countries they belong to.
 *
 * <p>This is the list that used to be {@code screening.geo.excluded-locations} -
 * a hundred and fifty place names whose only recorded meaning was "reject this".
 * Turning it into a vocabulary is the substance of this phase: the same string
 * that once discarded a posting now files it under a country, and whether the
 * country is worth pursuing is a separate question answered by
 * {@link com.anuragbhandary.jobradar.strategy.CountryStrategy}.
 *
 * <p>Its own {@code @ConfigurationProperties} root rather than another component
 * of {@code AppProperties}, so that adding it does not change that record's
 * constructor - which the filter tests build by hand.
 *
 * <p>The four original target countries are deliberately <strong>not</strong>
 * repeated here. They stay in {@code screening.geo.*-cities}, and
 * {@link LocationClassifier} reads them from there, so there is exactly one list
 * per country and the classifier cannot disagree with {@link GeoFilter} about
 * where Munich is.
 *
 * @param countries           ISO-3166 alpha-2 code to the place names that
 *                            identify it. Matched as whole words, so "us" does
 *                            not fire inside "Columbus".
 * @param regions             multi-country markers - EMEA, LATAM, the Nordics -
 *                            which are not countries and must not be stored as one
 * @param globalMarkers       words that mean "from anywhere". Matched against the
 *                            location only, never the title: "Global Platform" in
 *                            a job title is a team name, not a hiring policy.
 * @param falseFriendCountries the country a false friend actually belongs to.
 *                            "Dublin, Ohio" is not Ireland, and it is not nowhere
 *                            either - it is the United States, and saying so is
 *                            better than the old "reject and move on".
 */
@ConfigurationProperties(prefix = "job-radar.geo")
public record GeoVocabulary(
        Map<String, List<String>> countries,
        List<Region> regions,
        List<String> globalMarkers,
        Map<String, String> falseFriendCountries) {

    /**
     * A hiring region, which is a set of countries wearing one name.
     *
     * @param includesIndia the only question this record is asked. "Remote, EMEA"
     *                      and "Remote, APAC" are both region-locked; one of them
     *                      is reachable from Mumbai and the other is not, and
     *                      before this phase they were both simply rejected.
     */
    public record Region(String name, List<String> markers, boolean includesIndia) {

        public List<String> markers() {
            return markers == null ? List.of() : markers;
        }
    }

    public Map<String, List<String>> countries() {
        return countries == null ? Map.of() : countries;
    }

    public List<Region> regions() {
        return regions == null ? List.of() : regions;
    }

    public List<String> globalMarkers() {
        return globalMarkers == null ? List.of() : globalMarkers;
    }

    public Map<String, String> falseFriendCountries() {
        return falseFriendCountries == null ? Map.of() : falseFriendCountries;
    }
}
