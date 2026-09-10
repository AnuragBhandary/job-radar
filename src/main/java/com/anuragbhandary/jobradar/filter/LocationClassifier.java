package com.anuragbhandary.jobradar.filter;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.WorkMode;
import com.anuragbhandary.jobradar.strategy.CountryStrategy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads a posting's location text into a {@link LocationProfile}.
 *
 * <p>Built on top of {@link GeoFilter} rather than beside it. The four target
 * city lists, the false-friend list, the remote markers and the whole-word
 * matching all come from the same configuration and the same primitives
 * ({@link LocationText}), because two classifiers that disagree about where
 * Munich is would be worse than the one that could only name five places.
 *
 * <h2>What changed, and what did not</h2>
 * The old filter answered one question - is this one of four countries, or
 * globally remote, or rejected. Everything else in the world was
 * "outside target geographies", which is why 5,336 postings had no country
 * recorded and 1,042 of them were in countries the strategy later wanted.
 *
 * <p>This answers four questions instead, and rejects on only one of them:
 * <ol>
 *   <li><b>Which country?</b> From a vocabulary rather than a reject-list.
 *       Unrecognised is null - "needs classifying", never a guessed code.</li>
 *   <li><b>What work mode?</b> Five kinds of remote, not one.</li>
 *   <li><b>Where may the employee sit?</b> Only when the posting says so.</li>
 *   <li><b>Can this be held at all?</b> The one eligibility question, and the
 *       only thing that still rejects: remote work locked to somewhere the
 *       applicant cannot be. That is the expensive mistake the original filter
 *       existed to prevent and it is preserved exactly.</li>
 * </ol>
 */
@Component
public class LocationClassifier {

    private final AppProperties.Geo geo;
    private final GeoVocabulary vocabulary;
    private final CountryStrategy strategy;

    /** ISO code to every name that identifies it, target countries included. */
    private final Map<String, List<String>> countryNames;

    public LocationClassifier(GeoFilter geoFilter, GeoVocabulary vocabulary,
            CountryStrategy strategy) {
        this.geo = geoFilter.geo();
        this.vocabulary = vocabulary;
        this.strategy = strategy;
        this.countryNames = buildVocabulary(geoFilter.geo(), vocabulary);
    }

    /**
     * The four original target countries plus everything else, in one map.
     *
     * <p>The target lists are read from {@code screening.geo} rather than copied
     * into the new block. A second copy of "which cities are German" would drift,
     * and the drift would be silent - a posting in Dresden filed under no country
     * while {@link GeoFilter} still called it Germany.
     *
     * <p>Keys are normalised through {@link CountryCodes} because Spring's
     * relaxed binding canonicalises map keys: {@code US:} in YAML arrives as
     * {@code us}. The same trap is documented on {@code ApplicantProfile}'s
     * extra-answers, where it silently emptied the map that mattered.
     */
    private static Map<String, List<String>> buildVocabulary(
            AppProperties.Geo geo, GeoVocabulary vocabulary) {

        Map<String, List<String>> names = new LinkedHashMap<>();
        put(names, "IN", geo.indiaCities());
        put(names, "DE", geo.germanyCities());
        put(names, "IE", geo.irelandCities());
        put(names, "NL", geo.netherlandsCities());
        vocabulary.countries().forEach((code, places) -> put(names, code, places));
        return Map.copyOf(names);
    }

    private static void put(Map<String, List<String>> names, String code, List<String> places) {
        String normalised = CountryCodes.normalise(code);
        if (normalised == null || places == null || places.isEmpty()) {
            return;
        }
        List<String> merged = new ArrayList<>(names.getOrDefault(normalised, List.of()));
        merged.addAll(places);
        names.put(normalised, List.copyOf(merged));
    }

    // ------------------------------------------------------------------
    // Remote scope, read out of prose
    // ------------------------------------------------------------------

    /**
     * Phrases that narrow where a remote employee may be.
     *
     * <p>Consulted only when the posting is already remote. The same sentence on
     * an onsite role - "you must be authorised to work in Germany" - is a visa
     * requirement, not a hiring boundary, and reading it as one would file a
     * Berlin office job as country-locked remote.
     */
    private static final List<Pattern> RESTRICTED_TO = List.of(
            compile("(?:candidates?|applicants?|employees?|you)\\s+(?:must|need to|should)\\s+"
                    + "(?:be\\s+)?(?:located|based|residing|reside|living|live)\\s+"
                    + "(?:in|within)\\s+([^.;,\\n)]{2,40})"),
            compile("must\\s+(?:be\\s+)?(?:located|based|residing|reside)\\s+(?:in|within)"
                    + "\\s+([^.;,\\n)]{2,40})"),
            compile("only\\s+(?:open\\s+to|considering|accepting|available\\s+to)\\s+"
                    + "(?:candidates?|applicants?)?\\s*(?:located\\s+|based\\s+)?"
                    + "(?:in|within)\\s+([^.;,\\n)]{2,40})"),
            compile("remote\\s+(?:only\\s+)?within\\s+([^.;,\\n)]{2,40})"),
            compile("must\\s+(?:already\\s+)?(?:be\\s+)?(?:legally\\s+)?"
                    + "(?:authoris|authoriz|eligible)\\w*\\s+to\\s+work\\s+in"
                    + "\\s+([^.;,\\n)]{2,40})"));

    /** Phrases that say somewhere is allowed, which widens rather than narrows. */
    private static final List<Pattern> ALLOWED_FROM = List.of(
            compile("open\\s+to\\s+(?:candidates?|applicants?|employees?|people|hiring)?\\s*"
                    + "(?:located\\s+|based\\s+)?(?:in|from)\\s+([^.;,\\n)]{2,40})"),
            compile("(?:hire|hiring|employ|recruit)\\s+(?:remotely\\s+)?(?:from|in)"
                    + "\\s+([^.;,\\n)]{2,40})"),
            compile("(?:eligible|able|free)\\s+to\\s+work\\s+(?:remotely\\s+)?from"
                    + "\\s+([^.;,\\n)]{2,40})"),
            compile("(?:work|remote)\\s+from\\s+(?:anywhere\\s+in\\s+)?([^.;,\\n)]{2,40})"));

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    // ------------------------------------------------------------------

    /**
     * @param description scanned only for remote scope, and only when the posting
     *                   is remote. May be null - most callers outside screening
     *                   have nothing to pass.
     */
    public LocationProfile classify(String location, String title, String description) {
        String haystack = LocationText.haystack(location, title);
        if (haystack.isBlank()) {
            return unknown(FilterVerdict.reject("no location given"));
        }

        boolean homeMetro = LocationText.containsAny(haystack, geo.mumbaiCities());

        // Before anything else, exactly as GeoFilter does it: the target lists
        // would otherwise claim "Dublin, Ohio" for Ireland and return one branch
        // earlier, which is how the old excluded-locations entry for it could
        // never fire. Unlike the old filter this now says which country it
        // really is instead of only saying it is not Ireland.
        String falseFriend = LocationText.firstPhrase(
                LocationText.flatten(haystack), geo.falseFriends());
        if (falseFriend != null) {
            String code = CountryCodes.normalise(
                    vocabulary.falseFriendCountries().get(falseFriend));
            return new LocationProfile(code, codes(code), WorkMode.ONSITE,
                    List.of(), null, false, FilterVerdict.accept());
        }

        List<String> named = countriesNamedIn(haystack);
        boolean remote = LocationText.containsAny(haystack, geo.remoteMarkers());

        if (!remote) {
            WorkMode mode = LocationText.matches(haystack, "hybrid")
                    ? WorkMode.HYBRID : WorkMode.ONSITE;
            // An onsite role in a country the vocabulary does not know is not
            // rejected any more. It is stored with no country and lands outside
            // the default feed until somebody classifies it - which is what the
            // brief means by "unknown rather than inventing a country".
            return new LocationProfile(best(named), named, mode,
                    List.of(), null, homeMetro, FilterVerdict.accept());
        }

        return remoteProfile(location, haystack, description, named, homeMetro);
    }

    /** The five-way remote decision, in the order the evidence deserves. */
    private LocationProfile remoteProfile(String location, String haystack, String description,
            List<String> named, boolean homeMetro) {

        String scopeText = ((location == null ? "" : location) + " "
                + (description == null ? "" : description)).toLowerCase(Locale.ROOT);

        List<String> restricted = capture(scopeText, RESTRICTED_TO);
        List<String> allowed = capture(scopeText, ALLOWED_FROM);
        // Global markers are read from the location alone. "Global Platform" in a
        // job title names a team, and matching it in the title would turn every
        // such role into a worldwide hire.
        boolean global = LocationText.containsAny(
                (location == null ? "" : location).toLowerCase(Locale.ROOT),
                vocabulary.globalMarkers());
        GeoVocabulary.Region region = firstRegion(haystack);

        if (!restricted.isEmpty()) {
            return locked(restricted, homeMetro, "stated location requirement");
        }
        if (!named.isEmpty() && !global) {
            // "Remote (Argentina)", "United States (Remote)", "Remote - India".
            // The country beside the word remote is a hiring restriction, and
            // reading it as a perk is the mistake this whole filter exists for.
            return locked(named, homeMetro, "country named beside the remote marker");
        }
        if (region != null && !global) {
            String primary = region.includesIndia() ? CountryCodes.INDIA : null;
            return new LocationProfile(primary, codes(primary), WorkMode.REMOTE_REGIONAL,
                    region.includesIndia() ? List.of(CountryCodes.INDIA) : List.of(),
                    region.name(), homeMetro,
                    region.includesIndia() ? FilterVerdict.accept()
                            : FilterVerdict.reject("region-locked remote: '"
                                    + region.name().toLowerCase(Locale.ROOT)
                                    + "' does not include India"));
        }
        if (global || !allowed.isEmpty()) {
            // Positive evidence of a wide hire: a global marker, or somebody
            // named as allowed. Either is enough; neither is the bare word.
            List<String> eligible = allowed.isEmpty() ? List.of() : allowed;
            String primary = eligible.contains(CountryCodes.INDIA)
                    ? CountryCodes.INDIA : best(named);
            return new LocationProfile(primary, named.isEmpty() ? codes(primary) : named,
                    WorkMode.REMOTE_GLOBAL, eligible, null, homeMetro, FilterVerdict.accept());
        }

        // Bare "Remote". Kept - it has always been kept, and eleven of the
        // fifty-six current candidates read exactly like this - but recorded as
        // unspecified rather than promoted to global. A later resolver asking
        // "may he sit in Mumbai?" must be able to see that nobody said.
        return new LocationProfile(best(named), named, WorkMode.REMOTE_UNSPECIFIED,
                List.of(), null, homeMetro, FilterVerdict.accept());
    }

    /**
     * Remote, but only from named countries.
     *
     * <p>Being remote does not make a job reachable - it makes it a relocation
     * without a commute. "Republic of Ireland (Remote)" means living in Ireland,
     * which is a thing the strategy actively wants; "Remote (Argentina)" means
     * living in Argentina, which it has never said anything about. So the
     * question is not "does this include India" but "could he be in <em>any</em>
     * of these places", and the strategy answers it.
     *
     * <p>Getting that wrong in the other direction is not hypothetical: the first
     * cut of this method rejected everything that did not name India, and threw
     * away 48 German, 50 British and 13 Irish remote roles - the exact postings
     * the whole widening was for.
     */
    private LocationProfile locked(List<String> eligible, boolean homeMetro, String why) {
        String reachable = eligible.stream()
                .filter(code -> strategy.isHome(code)
                        || strategy.policyFor(code).allowsRelocation())
                .findFirst()
                .orElse(null);
        return new LocationProfile(
                reachable != null ? reachable : best(eligible),
                eligible, WorkMode.REMOTE_COUNTRY_LOCKED, eligible, null, homeMetro,
                reachable != null ? FilterVerdict.accept()
                        // The expensive mistake, still guarded: the country beside
                        // the word "remote" is a hiring restriction, and this one
                        // restricts it to somewhere he has not agreed to live.
                        : FilterVerdict.reject("country-locked remote: '"
                                + String.join("/", eligible) + "' (" + why + ")"));
    }

    // ------------------------------------------------------------------

    /** Every country the text names, best for the applicant first. */
    private List<String> countriesNamedIn(String haystack) {
        Set<String> found = new LinkedHashSet<>();
        countryNames.forEach((code, places) -> {
            if (LocationText.containsAny(haystack, places)) {
                found.add(code);
            }
        });
        return found.stream().sorted(strategy.preferenceOrder()).toList();
    }

    /**
     * The country a posting is filed under when it names several.
     *
     * <p>"Bangalore, London" is genuinely open in Bangalore, and the old filter
     * kept it for exactly that reason. Ordering by strategy preference
     * generalises the same rule to a hundred and fifty places instead of four.
     */
    private String best(List<String> codes) {
        return codes.isEmpty() ? null : codes.getFirst();
    }

    private GeoVocabulary.Region firstRegion(String haystack) {
        for (GeoVocabulary.Region region : vocabulary.regions()) {
            if (LocationText.containsAny(haystack, region.markers())) {
                return region;
            }
        }
        return null;
    }

    /**
     * Country codes named inside a captured phrase.
     *
     * <p>A capture that resolves to nothing is discarded rather than recorded.
     * "open to candidates in a fast-moving environment" matches the shape of an
     * allowance and names no country, and the honest reading of it is silence.
     */
    private List<String> capture(String text, List<Pattern> patterns) {
        Set<String> found = new LinkedHashSet<>();
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                found.addAll(countriesNamedIn(matcher.group(1).toLowerCase(Locale.ROOT)));
            }
        }
        return found.stream().sorted(strategy.preferenceOrder()).toList();
    }

    private static List<String> codes(String code) {
        return code == null ? List.of() : List.of(code);
    }

    private static LocationProfile unknown(FilterVerdict verdict) {
        return new LocationProfile(null, List.of(), WorkMode.UNKNOWN,
                List.of(), null, false, verdict);
    }
}
