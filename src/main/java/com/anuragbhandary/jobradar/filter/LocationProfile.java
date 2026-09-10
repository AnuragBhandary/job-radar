package com.anuragbhandary.jobradar.filter;

import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.WorkMode;
import java.util.List;

/**
 * Everything a posting's location text can be made to say, without guessing.
 *
 * <p>Replaces {@link GeoFilter.GeoResult}, which could only answer "which of five
 * enum values is this, and should it be thrown away". The questions a later
 * knowledge resolver has to ask - where is the job, where may the employee sit,
 * is this a move or not - had no field to be answered from.
 *
 * @param countryCode        the country the job is filed under, or null when the
 *                           text names none this vocabulary knows. Null is a real
 *                           answer and means "needs classifying", never "nowhere".
 * @param allCountryCodes    every country named, in preference order. A posting
 *                           open in Bangalore and London is one posting.
 * @param workMode           see {@link WorkMode}; never inferred from the bare
 *                           word "remote"
 * @param remoteEligibleFrom ISO codes the employee may sit in, when the posting
 *                           says. <strong>Empty means not stated</strong>, not
 *                           "anywhere" - the distinction section 8 of the brief
 *                           turns on.
 * @param region             the hiring region's name when the scope is regional
 * @param homeMetro          the location is in the home metro, which is what makes
 *                           the lower salary floor apply
 * @param verdict            whether the job can physically be held from India, or
 *                           by moving. Eligibility only - never preference.
 */
public record LocationProfile(
        String countryCode,
        List<String> allCountryCodes,
        WorkMode workMode,
        List<String> remoteEligibleFrom,
        String region,
        boolean homeMetro,
        FilterVerdict verdict) {

    public boolean isIndia() {
        return CountryCodes.isIndia(countryCode);
    }

    /** True when the posting stated where a remote employee may be. */
    public boolean statesRemoteScope() {
        return remoteEligibleFrom != null && !remoteEligibleFrom.isEmpty();
    }

    /**
     * True only when the posting explicitly said an employee may sit in India.
     *
     * <p>False for a posting that merely says "Remote". That is the inference
     * this whole record exists to stop being made silently.
     */
    public boolean allowsIndia() {
        return remoteEligibleFrom != null
                && remoteEligibleFrom.contains(CountryCodes.INDIA);
    }

    /** "IN,DE" - the storage form. Null rather than an empty string when unstated. */
    public String remoteEligibleFromCsv() {
        return statesRemoteScope() ? String.join(",", remoteEligibleFrom) : null;
    }
}
