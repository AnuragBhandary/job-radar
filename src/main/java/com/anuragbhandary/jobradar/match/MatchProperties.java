package com.anuragbhandary.jobradar.match;

import com.anuragbhandary.jobradar.domain.Country;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The weights behind the score.
 *
 * <p>In {@code application.yml} rather than in the personal profile, because these
 * are job-search strategy rather than personal data - the same reasoning that
 * keeps the screening rules public and the address private. They are also the
 * numbers most worth arguing with, and an argument is easier when they are in one
 * readable block instead of scattered through a class.
 *
 * @param countryPreference 0-100 per geography. Not a filter: screening has
 *                          already decided what is eligible, and this only decides
 *                          what floats to the top of what survived.
 * @param comfortableYears  years of experience he can answer for without
 *                          stretching. A posting asking for this or less scores
 *                          full marks on experience.
 */
@ConfigurationProperties(prefix = "job-radar.match")
public record MatchProperties(
        Map<Country, Integer> countryPreference,
        int comfortableYears,
        int freshDays,
        int staleDays) {

    public int preferenceFor(Country country) {
        if (country == null || countryPreference == null) {
            return 50;
        }
        return countryPreference.getOrDefault(country, 50);
    }

    public int comfortableYears() {
        return comfortableYears <= 0 ? 2 : comfortableYears;
    }

    /** Below this many days old, a posting is fresh enough to score full marks. */
    public int freshDays() {
        return freshDays <= 0 ? 7 : freshDays;
    }

    /** Past this, the requisition has usually been filled or forgotten. */
    public int staleDays() {
        return staleDays <= 0 ? 45 : staleDays;
    }
}
