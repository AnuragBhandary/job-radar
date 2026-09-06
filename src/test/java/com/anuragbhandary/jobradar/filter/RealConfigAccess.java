package com.anuragbhandary.jobradar.filter;

import com.anuragbhandary.jobradar.config.AppProperties;

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
}
