package com.anuragbhandary.jobradar.strategy;

import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads the country strategy, and answers the two questions that are not the same.
 *
 * <p><b>Is this pursuable?</b> is not asked here. That is screening's job, and it
 * rejects on facts - a senior title, five years required, a remote role locked to
 * a country the applicant cannot legally be in. This class only answers
 * <b>should it be recommended</b>, which is a preference and is allowed to change
 * between one run and the next without anything being re-fetched.
 *
 * <p>Keeping those apart is the whole point of the phase. Before it, a job in
 * Toronto was "rejected: outside target geographies" and came back only by
 * editing a list of place names; now it is a stored, screened, classified
 * opportunity carrying {@link StrategyOutcome#CONSIDER}, and widening the search
 * to Canada is a one-line configuration edit.
 */
@Component
public class CountryStrategy {

    private final StrategyProperties config;
    private final Map<String, CountryPolicy> byCode;

    public CountryStrategy(StrategyProperties config) {
        this.config = config;
        Map<String, CountryPolicy> map = new LinkedHashMap<>();
        for (CountryPolicy policy : config.countries()) {
            String code = CountryCodes.normalise(policy.countryCode());
            // A typo in the configuration must not become a country. normalise()
            // returns null for anything that is not a real ISO code, and a policy
            // keyed on null would answer for every unclassified posting.
            if (code != null) {
                map.put(code, policy);
            }
        }
        this.byCode = Map.copyOf(map);
    }

    public String homeCountry() {
        return config.homeCountry();
    }

    public boolean isHome(String countryCode) {
        return homeCountry().equalsIgnoreCase(CountryCodes.normalise(countryCode));
    }

    /** The policy for a country, or the unknown-country default. Never null. */
    public CountryPolicy policyFor(String countryCode) {
        String code = CountryCodes.normalise(countryCode);
        if (code == null) {
            return CountryPolicy.unknown(null);
        }
        CountryPolicy policy = byCode.get(code);
        return policy != null ? policy : CountryPolicy.unknown(code);
    }

    public RelocationTier tierFor(String countryCode) {
        return policyFor(countryCode).relocationTier();
    }

    /** Every configured policy, in the order the configuration lists them. */
    public List<CountryPolicy> policies() {
        return List.copyOf(byCode.values());
    }

    /** Policies whose world-facing claims are past their check-by date. */
    public List<CountryPolicy> stale(LocalDate today) {
        return byCode.values().stream().filter(policy -> policy.isStale(today)).toList();
    }

    /**
     * Which of several named countries a posting should be filed under.
     *
     * <p>A posting open in "Bangalore, London" is genuinely open in Bangalore, and
     * before this phase the geography filter kept it for exactly that reason. The
     * ordering here preserves that behaviour and generalises it: home country
     * first, then by tier, then alphabetically so the answer is stable across
     * runs and across map iteration order.
     */
    public Comparator<String> preferenceOrder() {
        return Comparator
                .comparingInt((String code) -> isHome(code) ? 0 : 1)
                .thenComparingInt(code -> tierFor(code).rank())
                .thenComparing(Comparator.naturalOrder());
    }

    /**
     * Whether an opportunity belongs on today's list.
     *
     * <p>Note what is <em>not</em> here: nothing consults the salary floor, the
     * score or the posting's age. Those are ranking, and ranking is a later
     * phase. This is only the strategic lane.
     */
    public StrategyOutcome outcomeFor(StrategicClass strategicClass, String countryCode,
            String employerCountryCode) {

        if (strategicClass == null || strategicClass == StrategicClass.UNCLASSIFIED) {
            // Not "excluded" - nobody decided anything about it. It needs
            // classifying, and it stays out of the default feed until it is.
            return StrategyOutcome.UNKNOWN;
        }

        return switch (strategicClass) {
            case INDIA_HOME, INDIA_OTHER -> StrategyOutcome.RECOMMENDED;

            // The employer's country decides this one, not the job's: the whole
            // definition of the lane is a company elsewhere paying into India.
            case INTERNATIONAL_REMOTE -> policyFor(employerCountryCode).allowsRemote()
                    ? StrategyOutcome.RECOMMENDED
                    : StrategyOutcome.EXCLUDED;

            case INTERNATIONAL_RELOCATION -> relocationOutcome(countryCode);

            case UNCLASSIFIED -> StrategyOutcome.UNKNOWN;
        };
    }

    private StrategyOutcome relocationOutcome(String countryCode) {
        CountryPolicy policy = policyFor(countryCode);
        if (!policy.allowsRelocation()) {
            return StrategyOutcome.EXCLUDED;
        }
        return switch (policy.relocationTier()) {
            case PRIMARY, SECONDARY -> StrategyOutcome.RECOMMENDED;
            // Opportunistic and low-priority countries are real options that the
            // user has not asked to be shown every morning. They are one filter
            // away, not one config edit away, which is the improvement.
            case OPPORTUNISTIC, LOW -> StrategyOutcome.CONSIDER;
            case EXCLUDED -> StrategyOutcome.EXCLUDED;
            case UNKNOWN -> StrategyOutcome.CONSIDER;
        };
    }

    /**
     * A 0-100 weight for the ranking layer, derived from the tier.
     *
     * <p>Here rather than in {@code MatchProperties} so that adding a country to
     * the strategy also gives it a sensible weight, instead of silently landing
     * on the 50-point default and never surfacing. The nine-dimension scoring
     * model is a later phase; this is the smallest change that stops the newly
     * admitted countries being invisible the day they are admitted.
     */
    public int preferenceFor(StrategicClass strategicClass, String countryCode) {
        if (strategicClass == null) {
            return 50;
        }
        return switch (strategicClass) {
            // Remote from home is the best of both: international pay, no rent,
            // no permit. It outranks everything, which is what the existing
            // country-preference block already said with REMOTE at 100.
            case INTERNATIONAL_REMOTE -> 100;
            case INDIA_HOME -> 70;
            case INDIA_OTHER -> 55;
            case INTERNATIONAL_RELOCATION -> switch (tierFor(countryCode)) {
                case PRIMARY -> 60;
                case SECONDARY -> 50;
                case OPPORTUNISTIC -> 40;
                case LOW -> 25;
                case EXCLUDED -> 10;
                case UNKNOWN -> 30;
            };
            case UNCLASSIFIED -> 30;
        };
    }
}
