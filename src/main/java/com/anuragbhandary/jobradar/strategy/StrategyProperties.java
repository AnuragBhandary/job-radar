package com.anuragbhandary.jobradar.strategy;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The country strategy, as configuration.
 *
 * <p>In {@code application.yml} beside the screening rules rather than in the
 * personal profile, for the reason that split was made in the first place: where
 * the user is willing to move is search strategy, and the repository is public
 * while a home address is not. It is also the block most worth arguing with, and
 * an argument is easier when the whole list is on one screen.
 *
 * @param homeCountry the one country needing no permit, and the baseline every
 *                    cost comparison is made against. Configured rather than
 *                    constant because the whole point of the search is that it
 *                    may one day be a different country.
 * @param countries   one {@link CountryPolicy} per country the strategy has an
 *                    opinion about. A country absent from this list is not
 *                    rejected - it resolves to {@link CountryPolicy#unknown} and
 *                    lands outside the default feed with a stated reason.
 */
@ConfigurationProperties(prefix = "job-radar.strategy")
public record StrategyProperties(String homeCountry, List<CountryPolicy> countries) {

    public String homeCountry() {
        return homeCountry == null || homeCountry.isBlank() ? "IN" : homeCountry;
    }

    public List<CountryPolicy> countries() {
        return countries == null ? List.of() : countries;
    }
}
