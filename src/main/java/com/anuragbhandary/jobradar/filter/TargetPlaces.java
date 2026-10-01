package com.anuragbhandary.jobradar.filter;

import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.strategy.CountryStrategy;
import com.anuragbhandary.jobradar.strategy.StrategyOutcome;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Whether a place is worth fetching a job's details for, before screening.
 *
 * <p>The list-then-detail fetchers (Workday, SmartRecruiters, Eightfold, Oracle,
 * sitemaps) and discovery's survey decide this on the location alone. They used
 * {@link GeoFilter}, whose targets are India and the three original relocation
 * countries, so a country added to the strategy later (the UK and the UAE on
 * 2026-10-01) was dropped before screening ever saw it. This asks the strategy
 * instead: any country whose relocation tier screening would recommend.
 *
 * <p>GeoFilter's answer is still taken first, so nothing it accepted before is
 * refused now, bare "Remote" included.
 */
@Component
public class TargetPlaces {

    private final GeoFilter geoFilter;
    private final LocationClassifier locations;
    private final CountryStrategy strategy;

    public TargetPlaces(GeoFilter geoFilter, LocationClassifier locations, CountryStrategy strategy) {
        this.geoFilter = geoFilter;
        this.locations = locations;
        this.strategy = strategy;
    }

    public boolean wanted(String location, String title) {
        if (geoFilter.classify(location, title).verdict().accepted()) {
            return true;
        }
        LocationProfile where = locations.classify(location, title, null);
        return where.verdict().accepted() && !recommendedAbroad(where).isEmpty();
    }

    /** The countries in this profile, other than home, that the strategy recommends moving to. */
    public List<String> recommendedAbroad(LocationProfile where) {
        List<String> codes = new ArrayList<>();
        if (where.countryCode() != null) {
            codes.add(where.countryCode());
        }
        if (where.allCountryCodes() != null) {
            codes.addAll(where.allCountryCodes());
        }
        List<String> out = new ArrayList<>();
        for (String code : codes) {
            if (code != null && !strategy.isHome(code) && !out.contains(code)
                    && strategy.outcomeFor(StrategicClass.INTERNATIONAL_RELOCATION, code, null)
                            == StrategyOutcome.RECOMMENDED) {
                out.add(code);
            }
        }
        return out;
    }

    /** The ISO code of a place name ("United Kingdom", "Bengaluru, India"), or null. */
    public String countryCode(String place) {
        if (place == null || place.isBlank()) {
            return null;
        }
        LocationProfile where = locations.classify(place, null, null);
        return where.verdict().accepted() ? where.countryCode() : null;
    }

    public boolean isHome(String countryCode) {
        return countryCode != null && strategy.isHome(countryCode);
    }

    /** Whether a country (not home) is one screening would recommend moving to. */
    public boolean recommendsMovingTo(String countryCode) {
        return countryCode != null && !strategy.isHome(countryCode)
                && strategy.outcomeFor(StrategicClass.INTERNATIONAL_RELOCATION, countryCode, null)
                        == StrategyOutcome.RECOMMENDED;
    }
}
