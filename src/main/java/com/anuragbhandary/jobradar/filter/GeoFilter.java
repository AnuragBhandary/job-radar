package com.anuragbhandary.jobradar.filter;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Country;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Decides whether a posting is somewhere worth applying to.
 *
 * <p>Target geographies are India, Germany, Ireland, the Netherlands, and remote
 * roles genuinely hireable into India.
 *
 * <p>The order matters. A target location is looked for <em>first</em>, so that a
 * multi-location posting such as "Bangalore, London" is kept on the strength of
 * Bangalore. Only if no target location appears does the exclusion list get a
 * say - which is what stops "Remote (Argentina)" being read as remote-friendly
 * when it is in fact a hiring restriction.
 */
@Component
public class GeoFilter {

    /** Resolved location plus the verdict, since screening needs both. */
    public record GeoResult(Country country, FilterVerdict verdict) {
    }

    private final AppProperties.Geo geo;

    public GeoFilter(AppProperties properties) {
        this.geo = properties.screening().geo();
    }

    /**
     * @param location the raw location string from the board
     * @param title    also searched, because several boards put the country only
     *                 in the title - "Junior Software Engineer (Mexico)"
     */
    public GeoResult classify(String location, String title) {
        String haystack = LocationText.haystack(location, title);

        if (haystack.isBlank()) {
            return new GeoResult(Country.OTHER, FilterVerdict.reject("no location given"));
        }

        // Checked before anything else, because the target lists would otherwise
        // claim these first and no later rule would get a say. "Dublin, Ohio"
        // matches ireland-cities on "dublin" and is accepted as Ireland; the
        // "dublin ohio" entry that used to sit in excluded-locations could never
        // fire, because acceptance had already happened one branch earlier.
        String falseFriend = LocationText.firstPhrase(
                LocationText.flatten(haystack), geo.falseFriends());
        if (falseFriend != null) {
            return new GeoResult(Country.OTHER, FilterVerdict.reject(
                    "reads as a target city but is not: '" + falseFriend + "'"));
        }

        boolean remote = containsAny(haystack, geo.remoteMarkers());

        // Target locations win over the exclusion list, so a posting open in both
        // Bangalore and London is kept for Bangalore.
        Country country = targetCountry(haystack);
        if (country != null) {
            return new GeoResult(country, FilterVerdict.accept());
        }

        String excluded = firstMatch(haystack, geo.excludedLocations());
        if (excluded != null) {
            return new GeoResult(Country.OTHER, FilterVerdict.reject(remote
                    // The expensive mistake this tool exists to prevent.
                    ? "country-locked remote: '" + excluded + "' in \"" + location + "\""
                    : "outside target geographies: '" + excluded + "'"));
        }

        if (remote) {
            // Remote with nothing tying it to another country. Still worth a
            // human look - "remote" sometimes means remote within a country the
            // posting never names.
            return new GeoResult(Country.REMOTE, FilterVerdict.accept());
        }

        return new GeoResult(Country.OTHER,
                FilterVerdict.reject("outside target geographies: \"" + location + "\""));
    }

    private Country targetCountry(String haystack) {
        if (containsAny(haystack, geo.indiaCities())) {
            return Country.INDIA;
        }
        if (containsAny(haystack, geo.germanyCities())) {
            return Country.GERMANY;
        }
        if (containsAny(haystack, geo.irelandCities())) {
            return Country.IRELAND;
        }
        if (containsAny(haystack, geo.netherlandsCities())) {
            return Country.NETHERLANDS;
        }
        return null;
    }

    /** True when the location is in the Mumbai metropolitan area - the INR 7 lakh floor. */
    public boolean isMumbai(String location) {
        return location != null
                && LocationText.containsAny(
                        LocationText.haystack(location, null), geo.mumbaiCities());
    }

    /** The screening rules this filter was built from, shared with the classifier. */
    AppProperties.Geo geo() {
        return geo;
    }

    private boolean containsAny(String haystack, List<String> needles) {
        return LocationText.containsAny(haystack, needles);
    }

    private String firstMatch(String haystack, List<String> needles) {
        return LocationText.firstMatch(haystack, needles);
    }

}
