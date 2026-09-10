package com.anuragbhandary.jobradar.strategy;

import com.anuragbhandary.jobradar.domain.CountryCodes;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the user's strategy says about one country, and when it was last checked.
 *
 * <p>Two different kinds of thing live in this record and they are deliberately
 * kept apart in the reader's mind:
 *
 * <ul>
 *   <li><b>Preference</b> - {@link #relocationTier}, {@link #relocationEnabled},
 *       {@link #remoteEnabled}. The user's own choices. Free to change, never
 *       stale, no verification required.</li>
 *   <li><b>Claims about the world</b> - {@link #salaryFloor},
 *       {@link #salaryFloorBasis}, {@link #monthlyLivingCost}. These can go
 *       out of date without anything appearing to break, which is why each one
 *       carries {@link #lastVerified} and {@link #verifyBy}.</li>
 * </ul>
 *
 * <p>The three European floors already in {@code job-radar.salary-floors} are
 * visa thresholds re-indexed every 1 January, and the comment there records that
 * an earlier verify-by date would have run two months of digests on stale
 * numbers. That is the failure this record is shaped to make visible rather than
 * to prevent - a threshold cannot be prevented from changing, only noticed.
 *
 * <p><strong>Unknown is a value.</strong> A null floor means no figure has been
 * established, and it is rendered as "not established". Nothing here invents a
 * number, and no immigration rule is encoded in Java: this record holds a figure
 * and the sentence that says where the figure came from, and it does not know
 * what a Blue Card is.
 *
 * @param countryCode       ISO-3166 alpha-2. The identity of the row.
 * @param displayName       optional override; the JDK's own name is used when blank
 * @param relocationTier    how much the user wants to move there
 * @param relocationEnabled false to keep the country out of the relocation lane
 *                          entirely while leaving remote work available - which is
 *                          exactly the United States' configuration
 * @param remoteEnabled     whether an employer here is worth working for remotely
 * @param salaryFloor       the least this country is worth taking for, in
 *                          {@link #currency}. Null when not established.
 * @param salaryFloorBasis  where the figure came from, in words a person can check
 * @param monthlyLivingCost rough cost of living for one person, same currency.
 *                          An estimate, always displayed as one.
 * @param lastVerified      when a human last checked the claims above at source
 * @param verifyBy          when they must be checked again
 */
public record CountryPolicy(
        String countryCode,
        String displayName,
        RelocationTier relocationTier,
        Boolean relocationEnabled,
        Boolean remoteEnabled,
        BigDecimal salaryFloor,
        String currency,
        String salaryFloorBasis,
        BigDecimal monthlyLivingCost,
        LocalDate lastVerified,
        LocalDate verifyBy) {

    /**
     * The policy applied to a country nobody has written one for.
     *
     * <p>Relocation is off and remote is on, and the asymmetry is deliberate.
     *
     * <p>Relocation off does not delete anything - an onsite role in an unlisted
     * country is still stored, still classified and still eligible; it simply
     * resolves to {@link StrategyOutcome#EXCLUDED} and stays out of the default
     * feed. What it does do is stop a <em>remote role locked to that country</em>
     * being treated as reachable. "Remote (Argentina)" only becomes holdable by
     * moving to Argentina, and the strategy has never said he would; reading it
     * as reachable is the single expensive mistake this tool was written to
     * prevent.
     *
     * <p>Remote stays on because an employer in an unlisted country hiring into
     * India costs nothing and requires nothing. Where the company is has no
     * bearing on a job worked from Mumbai.
     */
    public static CountryPolicy unknown(String code) {
        return new CountryPolicy(
                CountryCodes.normalise(code), null, RelocationTier.UNKNOWN,
                Boolean.FALSE, Boolean.TRUE,
                null, null, null, null, null, null);
    }

    public RelocationTier relocationTier() {
        return relocationTier == null ? RelocationTier.UNKNOWN : relocationTier;
    }

    /**
     * Defaults to true: a country in the list is available unless it says
     * otherwise.
     *
     * <p>Named apart from the record component because a record accessor has to
     * return the component's own type, and the component is a {@code Boolean} so
     * that "absent from the YAML" and "explicitly false" stay different things.
     */
    public boolean allowsRelocation() {
        return relocationEnabled == null || relocationEnabled;
    }

    /**
     * Defaults to true, and stays true for the United States.
     *
     * <p>Excluding a country for relocation says nothing about working for a
     * company based there from Mumbai. Conflating the two would discard the
     * highest-value lane in the whole strategy.
     */
    public boolean allowsRemote() {
        return remoteEnabled == null || remoteEnabled;
    }

    public String displayName() {
        return displayName == null || displayName.isBlank()
                ? CountryCodes.displayName(countryCode) : displayName;
    }

    /** True when a figure exists at all. Callers must not treat null as zero. */
    public boolean hasSalaryFloor() {
        return salaryFloor != null && currency != null && !currency.isBlank();
    }

    /** True when the world-facing claims are past their check-by date. */
    public boolean isStale(LocalDate today) {
        return verifyBy != null && today != null && !today.isBefore(verifyBy);
    }
}
