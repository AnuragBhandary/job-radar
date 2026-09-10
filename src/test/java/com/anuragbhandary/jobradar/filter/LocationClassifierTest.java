package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.WorkMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs against the shipped vocabulary in {@code application.yml}, like the rest
 * of the filter tests. A test carrying its own copy of the place names would keep
 * passing while the real ones were broken.
 */
class LocationClassifierTest {

    private final LocationClassifier classifier = RealConfig.locations();

    private LocationProfile at(String location) {
        return classifier.classify(location, "Software Engineer", null);
    }

    private LocationProfile at(String location, String description) {
        return classifier.classify(location, "Software Engineer", description);
    }

    // ------------------------------------------------------------------
    // Country
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Mumbai, Maharashtra          | IN",
            "Bengaluru, Karnataka, India  | IN",
            "Berlin                       | DE",
            "Dublin, Ireland (Hybrid)     | IE",
            "Amsterdam, Netherlands       | NL",
            "Toronto                      | CA",
            "London                       | GB",
            "Sydney                       | AU",
            "Singapore                    | SG",
            "Zurich                       | CH",
            "New York, NY                 | US",
            "Helsinki                     | FI",
            "Vienna                       | AT",
            "Auckland                     | NZ",
            "Stockholm                    | SE",
            "Paris                        | FR",
    })
    @DisplayName("place names resolve to countries instead of being rejected")
    void classifiesCountries(String location, String expected) {
        // The whole point of the phase. Every one of these below the fourth row
        // used to be "REJECTED - outside target geographies" with no country
        // recorded at all.
        LocationProfile profile = at(location);
        assertThat(profile.countryCode()).isEqualTo(expected);
        assertThat(profile.verdict().accepted()).isTrue();
    }

    @Test
    @DisplayName("an unrecognised place is unknown, not a guessed country")
    void unknownRatherThanGuessed() {
        LocationProfile profile = at("Ulaanbaatar");
        assertThat(profile.countryCode()).isNull();
        // Still eligible. Not knowing where a job is is a reason to classify it
        // later, not a reason to throw it away.
        assertThat(profile.verdict().accepted()).isTrue();
        assertThat(profile.workMode()).isEqualTo(WorkMode.ONSITE);
    }

    @Test
    @DisplayName("no location at all is still rejected")
    void rejectsMissingLocation() {
        assertThat(classifier.classify(null, null, null).verdict().accepted()).isFalse();
        assertThat(classifier.classify(null, null, null).workMode()).isEqualTo(WorkMode.UNKNOWN);
    }

    // ------------------------------------------------------------------
    // Work mode
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Remote - United States only is country-locked to the US")
    void remoteUsOnly() {
        LocationProfile profile = at("Remote - United States only");
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_COUNTRY_LOCKED);
        assertThat(profile.remoteEligibleFrom()).containsExactly("US");
        // Still ineligible, and now for a reason the strategy states rather than
        // a hardcoded one: US relocation is switched off, so "you must be in the
        // US" is a requirement he has not agreed to meet.
        assertThat(profile.verdict().accepted()).isFalse();
        assertThat(profile.verdict().reason()).contains("country-locked remote");
    }

    @Test
    @DisplayName("Remote - India is country-locked to India, and reachable")
    void remoteIndia() {
        LocationProfile profile = at("Remote - India");
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_COUNTRY_LOCKED);
        assertThat(profile.remoteEligibleFrom()).containsExactly("IN");
        assertThat(profile.countryCode()).isEqualTo("IN");
        assertThat(profile.verdict().accepted()).isTrue();
    }

    @Test
    @DisplayName("Remote - Europe is regional, and Europe does not include India")
    void remoteEurope() {
        LocationProfile profile = at("Remote - Europe");
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_REGIONAL);
        assertThat(profile.region()).isEqualTo("EUROPE");
        assertThat(profile.verdict().accepted()).isFalse();
        assertThat(profile.verdict().reason()).contains("region-locked remote");
    }

    @Test
    @DisplayName("Remote - APAC is regional and does include India")
    void remoteApac() {
        // The distinction the old filter could not make: it rejected EMEA and
        // APAC identically, and one of them is reachable from Mumbai.
        LocationProfile profile = at("Remote - APAC");
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_REGIONAL);
        assertThat(profile.region()).isEqualTo("APAC");
        assertThat(profile.verdict().accepted()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Remote - worldwide", "Remote, Global", "Work from anywhere"})
    @DisplayName("an explicit global marker is REMOTE_GLOBAL")
    void remoteWorldwide(String location) {
        LocationProfile profile = at(location);
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_GLOBAL);
        assertThat(profile.verdict().accepted()).isTrue();
    }

    @Test
    @DisplayName("a bare Remote is unspecified, never promoted to global")
    void bareRemoteIsUnspecified() {
        // Section 8 of the brief: do not infer international-remote eligibility
        // merely from the word "remote". The posting is kept - eleven of the
        // fifty-six candidates read exactly like this - but nothing downstream is
        // allowed to conclude that sitting in Mumbai is permitted.
        LocationProfile profile = at("Remote");
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_UNSPECIFIED);
        assertThat(profile.remoteEligibleFrom()).isEmpty();
        assertThat(profile.statesRemoteScope()).isFalse();
        assertThat(profile.verdict().accepted()).isTrue();
    }

    @Test
    @DisplayName("hybrid is presence, not remote")
    void hybridRequiresPresence() {
        LocationProfile profile = at("Berlin (Hybrid)");
        assertThat(profile.workMode()).isEqualTo(WorkMode.HYBRID);
        assertThat(profile.workMode().requiresPresence()).isTrue();
    }

    // ------------------------------------------------------------------
    // Remote eligibility read out of prose
    // ------------------------------------------------------------------

    @Test
    @DisplayName("'candidates must be located in India' is a lock, and a reachable one")
    void restrictionNamingIndia() {
        LocationProfile profile = at("Remote",
                "This is a remote role. Candidates must be located in India.");
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_COUNTRY_LOCKED);
        assertThat(profile.remoteEligibleFrom()).containsExactly("IN");
        assertThat(profile.verdict().accepted()).isTrue();
    }

    @Test
    @DisplayName("'must be located in Germany' is a relocation, not a dead end")
    void restrictionNamingGermany() {
        // He would move to Germany. A role open only to people already there is
        // an offer to do exactly that, minus the commute.
        LocationProfile profile = at("Remote",
                "Fully remote. You must be located in Germany for this position.");
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_COUNTRY_LOCKED);
        assertThat(profile.remoteEligibleFrom()).containsExactly("DE");
        assertThat(profile.verdict().accepted()).isTrue();
        assertThat(profile.allowsIndia()).isFalse();
    }

    @Test
    @DisplayName("remote within a primary target country is kept")
    void remoteWithinIreland() {
        // The regression this rule was written for. The first cut rejected
        // everything that did not name India and threw away 48 German, 50 British
        // and 13 Irish remote roles - the postings the widening was for.
        LocationProfile profile = at("Republic of Ireland (Remote)");
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_COUNTRY_LOCKED);
        assertThat(profile.countryCode()).isEqualTo("IE");
        assertThat(profile.verdict().accepted()).isTrue();
    }

    @Test
    @DisplayName("a multi-country remote role is filed under the best of them")
    void remoteAcrossSeveralCountries() {
        LocationProfile profile = at(
                "France, Remote; Germany, Remote; Netherlands, Remote; "
                        + "Spain, Remote; United Kingdom, Remote");
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_COUNTRY_LOCKED);
        assertThat(profile.countryCode()).isEqualTo("DE");
        assertThat(profile.remoteEligibleFrom()).contains("DE", "NL", "GB", "FR", "ES");
        assertThat(profile.verdict().accepted()).isTrue();
    }

    @Test
    @DisplayName("'open to employees in India' is an allowance, so the role is global")
    void allowanceNamingIndia() {
        LocationProfile profile = at("Remote",
                "We are open to candidates in India for this role.");
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_GLOBAL);
        assertThat(profile.remoteEligibleFrom()).contains("IN");
        assertThat(profile.allowsIndia()).isTrue();
    }

    @Test
    @DisplayName("an allowance naming nothing recognisable stays silent")
    void unresolvableAllowanceIsIgnored() {
        // "open to candidates in a fast-moving environment" has the shape of an
        // allowance and names no country. The honest reading of it is silence.
        LocationProfile profile = at("Remote",
                "We are open to candidates in a fast-moving environment.");
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_UNSPECIFIED);
        assertThat(profile.remoteEligibleFrom()).isEmpty();
    }

    @Test
    @DisplayName("scope prose is only read for remote roles")
    void scopeProseIgnoredForOnsite() {
        // "You must be authorised to work in Germany" on a Berlin office job is a
        // visa requirement, not a hiring boundary. Reading it as one would file an
        // onsite role as country-locked remote.
        LocationProfile profile = at("Berlin",
                "You must already be authorised to work in Germany.");
        assertThat(profile.workMode()).isEqualTo(WorkMode.ONSITE);
        assertThat(profile.remoteEligibleFrom()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Edge cases the old filter paid for in real applications
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Remote (Argentina) is still country-locked, not remote-friendly")
    void argentinaIsStillATrap() {
        // Argentina is not in the strategy, so it is not somewhere he has agreed
        // to live, so a role requiring him to live there is not reachable. The
        // expensive mistake, still guarded - now by a configured preference
        // rather than by a hardcoded list of place names.
        LocationProfile profile = at("Remote (Argentina)");
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_COUNTRY_LOCKED);
        assertThat(profile.remoteEligibleFrom()).containsExactly("AR");
        assertThat(profile.verdict().accepted()).isFalse();
    }

    @Test
    @DisplayName("a country named only in the title is still caught")
    void readsCountryFromTitle() {
        LocationProfile profile = classifier.classify(
                "Remote", "Junior Software Engineer (Mexico)", null);
        assertThat(profile.workMode()).isEqualTo(WorkMode.REMOTE_COUNTRY_LOCKED);
        assertThat(profile.verdict().accepted()).isFalse();
    }

    @Test
    @DisplayName("a target city outranks a lower-tier one in a multi-location posting")
    void targetLocationWins() {
        // The role is genuinely open in Bangalore; London does not disqualify it.
        LocationProfile profile = at("Bangalore, London");
        assertThat(profile.countryCode()).isEqualTo("IN");
        assertThat(profile.allCountryCodes()).containsExactly("IN", "GB");
    }

    @Test
    @DisplayName("Dublin, Ohio is the United States, not Ireland and not nowhere")
    void falseFriendsResolveToTheirRealCountry() {
        LocationProfile profile = at("Dublin, Ohio");
        assertThat(profile.countryCode()).isEqualTo("US");
        // Eligible, and excluded by strategy rather than deleted - which is the
        // improvement over the old answer of simply rejecting it.
        assertThat(profile.verdict().accepted()).isTrue();
    }

    @Test
    @DisplayName("a genuinely Irish posting that also mentions Ohio is still Ireland")
    void falseFriendsRequireAdjacency() {
        LocationProfile profile = at("Dublin, Ireland; Columbus, Ohio");
        assertThat(profile.countryCode()).isEqualTo("IE");
    }

    @Test
    @DisplayName("'distributed systems' in a title does not make a role remote")
    void distributedSystemsIsNotARemoteMarker() {
        // "distributed" was a remote marker until a Neo4j role in Malmo was
        // classified as globally remote on the strength of its title.
        LocationProfile profile = classifier.classify(
                "Malmö", "Software Engineering - Clustering & Distributed Systems", null);
        assertThat(profile.workMode()).isEqualTo(WorkMode.ONSITE);
        assertThat(profile.countryCode()).isEqualTo("SE");
    }

    @Test
    @DisplayName("short tokens match whole words only")
    void matchesWholeWordsOnly() {
        // "us" must not fire inside "Columbus", and "uk" must not fire inside
        // "Ukraine" - which would be right for the wrong reason.
        assertThat(at("Kyiv, Ukraine").countryCode()).isEqualTo("UA");
    }
}
