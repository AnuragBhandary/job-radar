package com.anuragbhandary.jobradar.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CountryCodesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "IN | India",
            "DE | Germany",
            "IE | Ireland",
            "NL | Netherlands",
            "US | United States",
            "CA | Canada",
            "AU | Australia",
            "GB | United Kingdom",
            "SG | Singapore",
            "CH | Switzerland",
    })
    @DisplayName("every code the strategy uses has a readable name")
    void namesTheCodes(String code, String expected) {
        assertThat(CountryCodes.displayName(code)).isEqualTo(expected);
    }

    @Test
    @DisplayName("case and whitespace do not make a different country")
    void normalisesCase() {
        assertThat(CountryCodes.normalise("de")).isEqualTo("DE");
        assertThat(CountryCodes.normalise(" in ")).isEqualTo("IN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"XX", "ZZ", "GER", "D", "remote", "1"})
    @NullAndEmptySource
    @DisplayName("anything that is not a real ISO code becomes null, never a new country")
    void refusesInventedCodes(String code) {
        // The instruction was not to invent country codes. A typo in the strategy
        // configuration must not silently become a country that then answers for
        // every unclassified posting.
        assertThat(CountryCodes.normalise(code)).isNull();
        assertThat(CountryCodes.isValid(code)).isFalse();
    }

    @Test
    @DisplayName("the four old enum values map to their codes")
    void mapsLegacyCountries() {
        assertThat(CountryCodes.fromLegacy(Country.INDIA)).isEqualTo("IN");
        assertThat(CountryCodes.fromLegacy(Country.GERMANY)).isEqualTo("DE");
        assertThat(CountryCodes.fromLegacy(Country.IRELAND)).isEqualTo("IE");
        assertThat(CountryCodes.fromLegacy(Country.NETHERLANDS)).isEqualTo("NL");
    }

    @Test
    @DisplayName("REMOTE and OTHER have no country code, and are not given one")
    void refusesToInventCodesForRemoteAndOther() {
        // REMOTE is a working arrangement wearing a country's clothes and OTHER
        // is fifty countries in a trench coat. Both migrate by re-screening the
        // posting's own location text, not by mapping.
        assertThat(CountryCodes.fromLegacy(Country.REMOTE)).isNull();
        assertThat(CountryCodes.fromLegacy(Country.OTHER)).isNull();
        assertThat(CountryCodes.fromLegacy(null)).isNull();
    }

    @Test
    @DisplayName("the legacy column keeps the value the answer path expects")
    void writesBackTheLegacyEnum() {
        // FieldMapper's sponsorship and authorisation derivations still read
        // Posting.getCountry(). This phase must not change what gets typed into
        // an application form.
        assertThat(CountryCodes.toLegacy("DE", StrategicClass.INTERNATIONAL_RELOCATION))
                .isEqualTo(Country.GERMANY);
        assertThat(CountryCodes.toLegacy("IN", StrategicClass.INDIA_HOME))
                .isEqualTo(Country.INDIA);
        assertThat(CountryCodes.toLegacy("AU", StrategicClass.INTERNATIONAL_RELOCATION))
                .isEqualTo(Country.OTHER);
        assertThat(CountryCodes.toLegacy(null, StrategicClass.UNCLASSIFIED))
                .isEqualTo(Country.OTHER);
    }

    @Test
    @DisplayName("international remote maps to REMOTE whatever the employer's country is")
    void internationalRemoteStaysRemote() {
        // Which is what it meant before: a role worked from Mumbai on no permit.
        // Keeping it keeps both sponsorship answers exactly as they are today.
        assertThat(CountryCodes.toLegacy("US", StrategicClass.INTERNATIONAL_REMOTE))
                .isEqualTo(Country.REMOTE);
        assertThat(CountryCodes.toLegacy("CA", StrategicClass.INTERNATIONAL_REMOTE))
                .isEqualTo(Country.REMOTE);
    }
}
