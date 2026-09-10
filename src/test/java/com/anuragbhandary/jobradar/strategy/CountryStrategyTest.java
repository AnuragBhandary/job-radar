package com.anuragbhandary.jobradar.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.filter.RealConfigAccess;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The shipped strategy, read the way the classifier reads it. */
class CountryStrategyTest {

    private final CountryStrategy strategy = RealConfigAccess.countryStrategy();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "DE | PRIMARY",
            "IE | PRIMARY",
            "NL | PRIMARY",
            "AU | SECONDARY",
            "GB | SECONDARY",
            "FI | SECONDARY",
            "AT | OPPORTUNISTIC",
            "CA | OPPORTUNISTIC",
            "NZ | OPPORTUNISTIC",
            "SG | OPPORTUNISTIC",
            "SE | OPPORTUNISTIC",
            "FR | OPPORTUNISTIC",
            "CH | LOW",
            "US | EXCLUDED",
    })
    @DisplayName("the configured tiers are the ones the brief asked for")
    void tiersMatchTheStrategy(String code, RelocationTier expected) {
        assertThat(strategy.tierFor(code)).isEqualTo(expected);
    }

    @Test
    @DisplayName("an unlisted country is unknown, and is not somewhere he has agreed to move")
    void unlistedCountriesAreUnknown() {
        assertThat(strategy.tierFor("BR")).isEqualTo(RelocationTier.UNKNOWN);
        // Relocation off, so "you must live in Brazil" is not a satisfiable
        // requirement. Remote on, because where a company is has no bearing on a
        // job worked from Mumbai.
        assertThat(strategy.policyFor("BR").allowsRelocation()).isFalse();
        assertThat(strategy.policyFor("BR").allowsRemote()).isTrue();
        // Still kept: an onsite Brazilian role is eligible and simply not
        // recommended. Excluded is a decision about priority, never a deletion.
        assertThat(strategy.outcomeFor(StrategicClass.INTERNATIONAL_RELOCATION, "BR", null))
                .isEqualTo(StrategyOutcome.EXCLUDED);
        assertThat(strategy.outcomeFor(StrategicClass.INTERNATIONAL_REMOTE, "BR", "BR"))
                .isEqualTo(StrategyOutcome.RECOMMENDED);
    }

    @Test
    @DisplayName("the United States is excluded for relocation and open for remote")
    void unitedStatesIsTheWholePoint() {
        CountryPolicy us = strategy.policyFor("US");
        assertThat(us.relocationTier()).isEqualTo(RelocationTier.EXCLUDED);
        assertThat(us.allowsRelocation()).isFalse();
        // The lane that was previously unreachable, and the most valuable one in
        // the strategy: international pay, no immigration, no rent.
        assertThat(us.allowsRemote()).isTrue();

        assertThat(strategy.outcomeFor(StrategicClass.INTERNATIONAL_RELOCATION, "US", null))
                .isEqualTo(StrategyOutcome.EXCLUDED);
        assertThat(strategy.outcomeFor(StrategicClass.INTERNATIONAL_REMOTE, "US", "US"))
                .isEqualTo(StrategyOutcome.RECOMMENDED);
    }

    @Test
    @DisplayName("Canada is selective for relocation and fully open for remote")
    void canadaIsSelective() {
        assertThat(strategy.outcomeFor(StrategicClass.INTERNATIONAL_RELOCATION, "CA", null))
                .isEqualTo(StrategyOutcome.CONSIDER);
        assertThat(strategy.outcomeFor(StrategicClass.INTERNATIONAL_REMOTE, "CA", "CA"))
                .isEqualTo(StrategyOutcome.RECOMMENDED);
    }

    @Test
    @DisplayName("a tier is a priority, never an eligibility")
    void tierIsNotEligibility() {
        // The rule this phase is built on. Everything below is a real job the
        // applicant could take; the strategy only decides what to show first.
        assertThat(strategy.outcomeFor(StrategicClass.INTERNATIONAL_RELOCATION, "DE", null))
                .isEqualTo(StrategyOutcome.RECOMMENDED);
        assertThat(strategy.outcomeFor(StrategicClass.INTERNATIONAL_RELOCATION, "CH", null))
                .isEqualTo(StrategyOutcome.CONSIDER);
        assertThat(strategy.outcomeFor(StrategicClass.INTERNATIONAL_RELOCATION, "US", null))
                .isEqualTo(StrategyOutcome.EXCLUDED);
    }

    @Test
    @DisplayName("India is always recommended, home or not")
    void indiaIsAlwaysRecommended() {
        assertThat(strategy.outcomeFor(StrategicClass.INDIA_HOME, "IN", null))
                .isEqualTo(StrategyOutcome.RECOMMENDED);
        assertThat(strategy.outcomeFor(StrategicClass.INDIA_OTHER, "IN", null))
                .isEqualTo(StrategyOutcome.RECOMMENDED);
    }

    @Test
    @DisplayName("multi-country postings are filed under the best country for him")
    void preferenceOrderPutsHomeFirst() {
        assertThat(java.util.stream.Stream.of("GB", "IN", "US", "DE")
                .sorted(strategy.preferenceOrder()).toList())
                .containsExactly("IN", "DE", "GB", "US");
    }

    @Test
    @DisplayName("salary floors carry their basis, and unknown is stored as unknown")
    void floorsAreDataWithProvenance() {
        CountryPolicy germany = strategy.policyFor("DE");
        assertThat(germany.hasSalaryFloor()).isTrue();
        assertThat(germany.currency()).isEqualTo("EUR");
        assertThat(germany.salaryFloorBasis()).contains("Blue Card");
        assertThat(germany.verifyBy()).isEqualTo(LocalDate.of(2027, 1, 1));

        // No threshold has been established for Australia, and none is invented.
        // A fabricated figure would be quoted back later as though it had been
        // checked at source.
        assertThat(strategy.policyFor("AU").hasSalaryFloor()).isFalse();
        assertThat(strategy.policyFor("AU").salaryFloor()).isNull();
    }

    @Test
    @DisplayName("a policy past its verify-by date reports itself as stale")
    void staleDatesAreVisible() {
        CountryPolicy germany = strategy.policyFor("DE");
        assertThat(germany.isStale(LocalDate.of(2026, 12, 31))).isFalse();
        assertThat(germany.isStale(LocalDate.of(2027, 1, 1))).isTrue();
        // A policy with no date makes no claim and cannot go stale.
        assertThat(strategy.policyFor("AU").isStale(LocalDate.of(2030, 1, 1))).isFalse();
    }

    @Test
    @DisplayName("international remote outranks every relocation in the feed weighting")
    void remoteOutranksRelocation() {
        // The lane with international pay, no rent and no permit should not sit
        // below a relocation that costs all three.
        int remote = strategy.preferenceFor(StrategicClass.INTERNATIONAL_REMOTE, "US");
        int primary = strategy.preferenceFor(StrategicClass.INTERNATIONAL_RELOCATION, "DE");
        int excluded = strategy.preferenceFor(StrategicClass.INTERNATIONAL_RELOCATION, "US");
        assertThat(remote).isGreaterThan(primary);
        assertThat(primary).isGreaterThan(excluded);
    }
}
