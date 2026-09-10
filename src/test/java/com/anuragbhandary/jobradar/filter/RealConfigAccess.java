package com.anuragbhandary.jobradar.filter;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.strategy.CountryStrategy;

/**
 * Exposes the package-private {@link RealConfig} to tests in other packages, so
 * that the digest tests also run against the shipped screening rules.
 */
public final class RealConfigAccess {

    private RealConfigAccess() {
    }

    public static AppProperties.Screening screening() {
        return RealConfig.screening();
    }

    /** The shipped country strategy, for tests outside this package. */
    public static CountryStrategy countryStrategy() {
        return RealConfig.countryStrategy();
    }

    public static LocationClassifier locations() {
        return RealConfig.locations();
    }

    public static StrategicClassifier lanes() {
        return new StrategicClassifier(RealConfig.countryStrategy());
    }
}
