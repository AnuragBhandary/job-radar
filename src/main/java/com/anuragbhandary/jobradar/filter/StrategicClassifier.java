package com.anuragbhandary.jobradar.filter;

import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.WorkMode;
import com.anuragbhandary.jobradar.strategy.CountryStrategy;
import org.springframework.stereotype.Component;

/**
 * Puts a classified location into one of the four lanes of the job search.
 *
 * <p>Derived, never entered. Everything it reads is a structured field somebody
 * else worked out - country code, work mode, remote eligibility, employer
 * country - so the same posting always lands in the same lane and the reasoning
 * can be printed next to it.
 *
 * <h2>The distinction the whole class exists for</h2>
 * A US company hiring remotely from India is not a US relocation. No visa, no
 * rent, no move; the strategy that excludes US relocation has nothing to say
 * about it. Before this phase the two were indistinguishable, because
 * {@code Country.REMOTE} recorded the working arrangement and threw away who the
 * employer was - so the only way to tell them apart was to read the posting.
 */
@Component
public class StrategicClassifier {

    private final CountryStrategy strategy;

    public StrategicClassifier(CountryStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * @param employerCountryCode where the company is, when known. Null is
     *                            common and is handled without guessing: the job
     *                            country decides instead, and the lane falls back
     *                            to international remote only when neither says
     *                            India.
     */
    public StrategicClass classify(LocationProfile location, String employerCountryCode) {
        if (location == null || location.workMode() == null) {
            return StrategicClass.UNCLASSIFIED;
        }

        if (location.workMode().requiresPresence()) {
            return onsite(location);
        }
        if (location.workMode().isRemote()) {
            return remote(location, employerCountryCode);
        }
        return StrategicClass.UNCLASSIFIED;
    }

    /** Presence required: either it is home, or it is a move. */
    private StrategicClass onsite(LocationProfile location) {
        String code = location.countryCode();
        if (code == null) {
            return StrategicClass.UNCLASSIFIED;
        }
        if (strategy.isHome(code)) {
            // The home metro is a different economic proposition from the rest of
            // the country - no rent - which is why the two have different salary
            // floors and have to be different lanes rather than one.
            return location.homeMetro() ? StrategicClass.INDIA_HOME : StrategicClass.INDIA_OTHER;
        }
        return StrategicClass.INTERNATIONAL_RELOCATION;
    }

    /**
     * Remote: the employee sits at home, so the only question is who pays.
     *
     * <p>Only reachable remote postings get this far - a role locked to a country
     * the applicant cannot be in has already been rejected by
     * {@link LocationClassifier}, on eligibility rather than on preference.
     */
    private StrategicClass remote(LocationProfile location, String employerCountryCode) {
        // Remote from somewhere he is not. A role open only to people already in
        // Ireland is an Irish job that happens to have no office attached: he
        // still has to move, so it belongs in the relocation lane and is scored,
        // floored and sponsored like every other Irish job.
        if (location.workMode() == WorkMode.REMOTE_COUNTRY_LOCKED && !location.allowsIndia()) {
            return location.countryCode() == null
                    ? StrategicClass.UNCLASSIFIED : StrategicClass.INTERNATIONAL_RELOCATION;
        }
        // A region names no country, so there is nowhere to say he would move to.
        // Recorded as needing classification rather than guessed at.
        if (location.workMode() == WorkMode.REMOTE_REGIONAL && !location.allowsIndia()) {
            return StrategicClass.UNCLASSIFIED;
        }

        String employer = CountryCodes.normalise(employerCountryCode);
        if (employer != null) {
            return strategy.isHome(employer)
                    // An Indian employer hiring remotely is an Indian job worked
                    // from the home city, whatever the posting calls it.
                    ? StrategicClass.INDIA_HOME
                    : StrategicClass.INTERNATIONAL_REMOTE;
        }

        // No employer on record. The job's own country is the next best evidence:
        // "Remote - India" with an unknown employer is an Indian role.
        String code = location.countryCode();
        if (code != null && strategy.isHome(code) && location.statesRemoteScope()) {
            return StrategicClass.INDIA_HOME;
        }
        if (code != null && !strategy.isHome(code)) {
            return StrategicClass.INTERNATIONAL_REMOTE;
        }

        // Bare "Remote", employer unknown, country unknown. Filed as international
        // remote because that is the lane it needs reviewing in, and because it is
        // what the old model already did with these - Country.REMOTE, in the feed.
        // The uncertainty is not lost: the work mode says REMOTE_UNSPECIFIED and
        // remoteEligibleFrom is empty, so nothing downstream may assert that
        // sitting in Mumbai is permitted.
        return StrategicClass.INTERNATIONAL_REMOTE;
    }
}
