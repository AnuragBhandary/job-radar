package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.strategy.StrategyOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The four lanes, and the distinction the whole phase exists for: a US company
 * hiring from Mumbai is not a US relocation.
 */
class StrategicClassifierTest {

    private final LocationClassifier locations = RealConfig.locations();
    private final StrategicClassifier lanes =
            new StrategicClassifier(RealConfig.countryStrategy());
    private final com.anuragbhandary.jobradar.strategy.CountryStrategy strategy =
            RealConfig.countryStrategy();

    private StrategicClass lane(String location, String description, String employerCountry) {
        return lanes.classify(
                locations.classify(location, "Software Engineer", description), employerCountry);
    }

    private StrategicClass lane(String location) {
        return lane(location, null, null);
    }

    private StrategyOutcome outcome(String location, String description, String employer) {
        LocationProfile profile =
                locations.classify(location, "Software Engineer", description);
        StrategicClass lane = lanes.classify(profile, employer);
        return strategy.outcomeFor(lane, profile.countryCode(), employer);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Mumbai is the home lane - the one with no rent")
    void mumbaiIsHome() {
        assertThat(lane("Mumbai, Maharashtra")).isEqualTo(StrategicClass.INDIA_HOME);
        assertThat(lane("Navi Mumbai")).isEqualTo(StrategicClass.INDIA_HOME);
        assertThat(lane("Thane")).isEqualTo(StrategicClass.INDIA_HOME);
    }

    @Test
    @DisplayName("Bengaluru is India, but not home - it needs a premium to be worth it")
    void bengaluruIsIndiaOther() {
        assertThat(lane("Bengaluru, Karnataka, India")).isEqualTo(StrategicClass.INDIA_OTHER);
        assertThat(lane("Gurugram")).isEqualTo(StrategicClass.INDIA_OTHER);
    }

    @Test
    @DisplayName("Berlin onsite is a relocation, and a recommended one")
    void berlinIsRelocation() {
        assertThat(lane("Berlin")).isEqualTo(StrategicClass.INTERNATIONAL_RELOCATION);
        assertThat(outcome("Berlin", null, null)).isEqualTo(StrategyOutcome.RECOMMENDED);
    }

    @Test
    @DisplayName("US onsite is a relocation, excluded by strategy, and still kept")
    void usOnsiteIsExcludedNotDeleted() {
        // Eligible in general; the strategy simply says he would rather not. That
        // is a different thing from an unsupported job, and the posting stays in
        // the database either way.
        assertThat(lane("New York, NY")).isEqualTo(StrategicClass.INTERNATIONAL_RELOCATION);
        assertThat(outcome("New York, NY", null, null)).isEqualTo(StrategyOutcome.EXCLUDED);
    }

    @Test
    @DisplayName("a US employer hiring remotely from India is international remote")
    void usEmployerRemoteFromIndiaIsNotRelocation() {
        // The case section 9 of the brief exists for. No visa, no rent, no move,
        // and nothing the relocation strategy has an opinion about.
        StrategicClass lane = lane("Remote",
                "This role is open to candidates in India.", "US");
        assertThat(lane).isEqualTo(StrategicClass.INTERNATIONAL_REMOTE);
        assertThat(outcome("Remote", "This role is open to candidates in India.", "US"))
                .isEqualTo(StrategyOutcome.RECOMMENDED);
    }

    @Test
    @DisplayName("a Canadian employer hiring remotely from India is international remote")
    void canadaRemoteFromIndia() {
        assertThat(lane("Remote", "We hire from India and Canada.", "CA"))
                .isEqualTo(StrategicClass.INTERNATIONAL_REMOTE);
        assertThat(outcome("Remote", "We hire from India and Canada.", "CA"))
                .isEqualTo(StrategyOutcome.RECOMMENDED);
    }

    @Test
    @DisplayName("Canada onsite is a relocation the strategy will consider, not recommend")
    void canadaOnsiteIsOpportunistic() {
        assertThat(lane("Toronto")).isEqualTo(StrategicClass.INTERNATIONAL_RELOCATION);
        assertThat(outcome("Toronto", null, null)).isEqualTo(StrategyOutcome.CONSIDER);
    }

    @Test
    @DisplayName("an Indian employer hiring remotely is an Indian job worked from home")
    void indianEmployerRemote() {
        assertThat(lane("Remote", null, "IN")).isEqualTo(StrategicClass.INDIA_HOME);
    }

    @Test
    @DisplayName("Remote - India with no employer on record is an Indian role")
    void remoteIndiaWithoutEmployer() {
        assertThat(lane("Remote - India")).isEqualTo(StrategicClass.INDIA_HOME);
    }

    @Test
    @DisplayName("a bare Remote with nothing known is filed for review, not asserted")
    void bareRemoteIsAReviewLane() {
        // Filed as international remote because that is the lane it needs looking
        // at in. The uncertainty is not lost: the work mode says
        // REMOTE_UNSPECIFIED and remoteEligibleFrom is empty, so nothing may
        // conclude that sitting in Mumbai is permitted.
        LocationProfile profile = locations.classify("Remote", "Software Engineer", null);
        assertThat(lanes.classify(profile, null)).isEqualTo(StrategicClass.INTERNATIONAL_REMOTE);
        assertThat(profile.statesRemoteScope()).isFalse();
        assertThat(profile.allowsIndia()).isFalse();
    }

    @Test
    @DisplayName("an unrecognised onsite location is unclassified, not excluded")
    void unknownLocationIsUnclassified() {
        assertThat(lane("Ulaanbaatar")).isEqualTo(StrategicClass.UNCLASSIFIED);
        assertThat(outcome("Ulaanbaatar", null, null)).isEqualTo(StrategyOutcome.UNKNOWN);
    }

    @Test
    @DisplayName("remote within Ireland is a relocation lane, not a remote one")
    void remoteWithinIrelandIsRelocation() {
        // He still has to move to Ireland; the job just has no office. It should
        // be scored, floored and sponsored like every other Irish job.
        assertThat(lane("Republic of Ireland (Remote)"))
                .isEqualTo(StrategicClass.INTERNATIONAL_RELOCATION);
        assertThat(outcome("Republic of Ireland (Remote)", null, null))
                .isEqualTo(StrategyOutcome.RECOMMENDED);
    }

    @Test
    @DisplayName("Zurich is eligible and low priority, which is neither recommended nor excluded")
    void switzerlandIsLowPriority() {
        assertThat(lane("Zurich")).isEqualTo(StrategicClass.INTERNATIONAL_RELOCATION);
        assertThat(outcome("Zurich", null, null)).isEqualTo(StrategyOutcome.CONSIDER);
    }
}
