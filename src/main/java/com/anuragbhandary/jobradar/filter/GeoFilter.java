package com.anuragbhandary.jobradar.filter;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Country;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<String, Pattern> wordPatterns = new ConcurrentHashMap<>();

    public GeoFilter(AppProperties properties) {
        this.geo = properties.screening().geo();
    }

    /**
     * @param location the raw location string from the board
     * @param title    also searched, because several boards put the country only
     *                 in the title - "Junior Software Engineer (Mexico)"
     */
    public GeoResult classify(String location, String title) {
        String haystack = ((location == null ? "" : location) + " " + (title == null ? "" : title))
                .toLowerCase(Locale.ROOT);

        if (haystack.isBlank()) {
            return new GeoResult(Country.OTHER, FilterVerdict.reject("no location given"));
        }

        // Checked before anything else, because the target lists would otherwise
        // claim these first and no later rule would get a say. "Dublin, Ohio"
        // matches ireland-cities on "dublin" and is accepted as Ireland; the
        // "dublin ohio" entry that used to sit in excluded-locations could never
        // fire, because acceptance had already happened one branch earlier.
        String falseFriend = firstPhrase(flatten(haystack), geo.falseFriends());
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
                && containsAny(location.toLowerCase(Locale.ROOT), geo.mumbaiCities());
    }

    /**
     * Punctuation to single spaces, so "Dublin, Ohio" reads as the contiguous
     * phrase "dublin ohio".
     *
     * <p>Adjacency is the whole point. A posting open in "Dublin, Ireland;
     * Columbus, Ohio" contains both words and is genuinely Irish, so a false
     * friend has to be the city and the qualifier sitting next to each other -
     * not merely present in the same string.
     */
    private static String flatten(String haystack) {
        return haystack.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** First phrase occurring literally in the already-flattened haystack. */
    private static String firstPhrase(String flattened, List<String> phrases) {
        if (phrases == null) {
            return null;
        }
        for (String phrase : phrases) {
            if (flattened.contains(phrase)) {
                return phrase;
            }
        }
        return null;
    }

    private boolean containsAny(String haystack, List<String> needles) {
        return firstMatch(haystack, needles) != null;
    }

    /**
     * Word-boundary matching, not substring. "us" must not fire inside
     * "Columbus", and "uk" must not fire inside "Ukraine".
     */
    private String firstMatch(String haystack, List<String> needles) {
        if (needles == null) {
            return null;
        }
        for (String needle : needles) {
            if (wordPattern(needle).matcher(haystack).find()) {
                return needle;
            }
        }
        return null;
    }

    private Pattern wordPattern(String needle) {
        return wordPatterns.computeIfAbsent(needle, GeoFilter::compileWordPattern);
    }

    /**
     * A word boundary is only added at an end that is actually a word character.
     * "u.s." ends in a full stop, and {@code \b} after a non-word character would
     * require a word character to follow it - so the token would never match at
     * the end of a string.
     */
    private static Pattern compileWordPattern(String needle) {
        String prefix = Character.isLetterOrDigit(needle.charAt(0)) ? "\\b" : "";
        String suffix = Character.isLetterOrDigit(needle.charAt(needle.length() - 1)) ? "\\b" : "";
        return Pattern.compile(prefix + Pattern.quote(needle) + suffix, Pattern.CASE_INSENSITIVE);
    }
}
