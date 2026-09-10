package com.anuragbhandary.jobradar.filter;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.strategy.CountryStrategy;
import com.anuragbhandary.jobradar.strategy.StrategyProperties;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads the screening rules from the real {@code application.yml}.
 *
 * <p>The filter tests run against the shipped configuration rather than against
 * lists invented in the test. The rules are config precisely so they can be
 * edited, and a test that carried its own copy would keep passing while the
 * shipped rules were broken - which is the same mistake as Milestone 1's
 * create-drop tests, in a different place.
 *
 * <p>Only the {@code screening} subtree is bound, because the surrounding
 * sections contain {@code ${ENV_VAR}} placeholders that a plain Binder has no
 * environment to resolve.
 */
final class RealConfig {

    private RealConfig() {
    }

    static AppProperties.Screening screening() {
        return bind("job-radar.screening", AppProperties.Screening.class);
    }

    static AppProperties withScreening() {
        return new AppProperties(null, null, null, null, screening(), null);
    }

    static GeoVocabulary geo() {
        return bind("job-radar.geo", GeoVocabulary.class);
    }

    static StrategyProperties strategy() {
        return bind("job-radar.strategy", StrategyProperties.class);
    }

    static CountryStrategy countryStrategy() {
        return new CountryStrategy(strategy());
    }

    /** The classifier, wired the way Spring wires it, against the shipped rules. */
    static LocationClassifier locations() {
        return new LocationClassifier(
                new GeoFilter(withScreening()), geo(), countryStrategy());
    }

    private static <T> T bind(String path, Class<T> type) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("/application.yml"));
            StandardEnvironment environment = new StandardEnvironment();
            sources.forEach(s -> environment.getPropertySources().addFirst(s));
            return Binder.get(environment).bind(path, Bindable.of(type)).orElseThrow(
                    () -> new IllegalStateException("No configuration at " + path));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read application.yml", e);
        }
    }
}
