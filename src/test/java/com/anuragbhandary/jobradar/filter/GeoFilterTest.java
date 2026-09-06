package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.Country;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GeoFilterTest {

    private final GeoFilter filter = new GeoFilter(RealConfig.withScreening());

    private GeoFilter.GeoResult classify(String location) {
        return filter.classify(location, "Software Engineer");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Bengaluru, Karnataka, India        | INDIA",
            "Mumbai, Maharashtra                | INDIA",
            "Navi Mumbai                        | INDIA",
            "Gurugram                           | INDIA",
            "Dublin, Ireland (Hybrid)           | IRELAND",
            "Berlin                             | GERMANY",
            "München, Germany                   | GERMANY",
            "Amsterdam, Netherlands             | NETHERLANDS",
            "The Hague                          | NETHERLANDS",
            "Remote                             | REMOTE",
    })
    @DisplayName("target geographies are accepted and classified")
    void acceptsTargetGeographies(String location, Country expected) {
        GeoFilter.GeoResult result = classify(location);
        assertThat(result.verdict().accepted()).isTrue();
        assertThat(result.country()).isEqualTo(expected);
    }

    @Test
    @DisplayName("'Remote (Argentina)' is rejected as country-locked remote")
    void rejectsCountryLockedRemote() {
        // The expensive mistake. The country in the title is a hiring
        // restriction, not a perk, and several applications were made to roles
        // like this before the tool existed.
        GeoFilter.GeoResult result = classify("Remote (Argentina)");
        assertThat(result.verdict().accepted()).isFalse();
        assertThat(result.verdict().reason()).contains("country-locked remote");
        assertThat(result.country()).isEqualTo(Country.OTHER);
    }

    @Test
    @DisplayName("a country named only in the title is still caught")
    void readsCountryFromTitle() {
        GeoFilter.GeoResult result =
                filter.classify("Remote", "Junior Software Engineer (Mexico)");
        assertThat(result.verdict().accepted()).isFalse();
        assertThat(result.verdict().reason()).contains("country-locked remote");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "United States (Remote)",
            "United States - West (Remote)",
            "Remote, EMEA",
            "Remote - LATAM",
    })
    @DisplayName("remote scoped to a region that excludes India is rejected")
    void rejectsRegionLockedRemote(String location) {
        assertThat(classify(location).verdict().accepted()).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "London", "Bucharest", "Toronto", "San Francisco, Seattle, New York",
            "Mexico City, Mexico", "Singapore", "Tel Aviv",
    })
    @DisplayName("non-remote roles outside the four countries are rejected")
    void rejectsOtherGeographies(String location) {
        GeoFilter.GeoResult result = classify(location);
        assertThat(result.verdict().accepted()).isFalse();
        assertThat(result.country()).isEqualTo(Country.OTHER);
    }

    @Test
    @DisplayName("a target city outranks an excluded one in a multi-location posting")
    void targetLocationWins() {
        // The role is genuinely open in Bangalore; London does not disqualify it.
        GeoFilter.GeoResult result = classify("Bangalore, London");
        assertThat(result.verdict().accepted()).isTrue();
        assertThat(result.country()).isEqualTo(Country.INDIA);
    }

    @Test
    @DisplayName("remote into India is accepted")
    void acceptsRemoteIntoIndia() {
        GeoFilter.GeoResult result = classify("Remote - India");
        assertThat(result.verdict().accepted()).isTrue();
        assertThat(result.country()).isEqualTo(Country.INDIA);
    }

    @Test
    @DisplayName("short tokens match whole words only")
    void matchesWholeWordsOnly() {
        // "us" must not fire inside "Columbus", and "uk" must not fire inside
        // "Ukraine" - which would be right for the wrong reason.
        assertThat(classify("Columbus, Ohio").verdict().reason()).doesNotContain("'us'");
    }

    @Test
    @DisplayName("Mumbai is identified for the lower salary floor")
    void identifiesMumbai() {
        assertThat(filter.isMumbai("Mumbai, Maharashtra")).isTrue();
        assertThat(filter.isMumbai("Thane")).isTrue();
        assertThat(filter.isMumbai("Bengaluru")).isFalse();
    }

    @Test
    @DisplayName("'distributed systems' in a title does not make a role remote")
    void distributedSystemsIsNotARemoteMarker() {
        // "distributed" was a remote marker until a Neo4j role in Malmo was
        // classified as globally remote on the strength of its title. A remote
        // marker must be a word about working arrangements, not one that is also
        // core software vocabulary.
        GeoFilter.GeoResult result =
                filter.classify("Malmö", "Software Engineering - Clustering & Distributed Systems");
        assertThat(result.verdict().accepted()).isFalse();
        assertThat(result.country()).isEqualTo(Country.OTHER);
    }

    @Test
    @DisplayName("a posting with no location is rejected, not assumed remote")
    void rejectsMissingLocation() {
        assertThat(filter.classify(null, null).verdict().accepted()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Dublin, Ohio", "Dublin, OH", "Berlin, Connecticut", "Hamburg, New York",
    })
    @DisplayName("American namesakes of target cities are rejected")
    void rejectsFalseFriends(String location) {
        // "dublin ohio" sat in excluded-locations and could never fire: the target
        // list matched "dublin" and returned Ireland one branch earlier.
        assertThat(classify(location).verdict().accepted()).isFalse();
    }

    @Test
    @DisplayName("a genuinely Irish posting that also mentions Ohio is still Ireland")
    void falseFriendsRequireAdjacency() {
        GeoFilter.GeoResult result = classify("Dublin, Ireland; Columbus, Ohio");
        assertThat(result.verdict().accepted()).isTrue();
        assertThat(result.country()).isEqualTo(Country.IRELAND);
    }

}
