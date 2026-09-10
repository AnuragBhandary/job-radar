package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.filter.GeoFilter;
import com.anuragbhandary.jobradar.strategy.CountryPolicy;
import com.anuragbhandary.jobradar.strategy.CountryStrategy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Says which salary floor applies to a posting, and why.
 *
 * <p>No salary is ever estimated here. Most postings state none, and a guessed
 * number in a digest is worse than a blank one - it would be quoted back later
 * as though it came from the posting. The floor is stated so a human can compare
 * against it once they know the actual figure.
 *
 * <p>The figures and the sentences that justify them now come from
 * {@link CountryPolicy} rather than from a switch statement in here. That switch
 * knew that Germany meant a Blue Card and Ireland meant a CSEP, which is exactly
 * the kind of immigration assumption that goes stale without anything appearing
 * to break - and which nothing in Java should be asserting. Now a country either
 * has a verified figure with its basis and a check-by date, or it says it has
 * none.
 */
@Component
public class SalaryFloorAdvisor {

    private static final NumberFormat GROUPED = NumberFormat.getInstance(Locale.GERMANY);

    private final AppProperties.SalaryFloors floors;
    private final CountryStrategy strategy;
    private final GeoFilter geo;

    public SalaryFloorAdvisor(AppProperties properties, CountryStrategy strategy, GeoFilter geo) {
        this.floors = properties.salaryFloors();
        this.strategy = strategy;
        this.geo = geo;
    }

    /** A one-line statement of the floor and the reason it is that number. */
    public String floorFor(Posting posting) {
        return floorFor(posting, LocalDate.now());
    }

    /** Today is a parameter so the staleness note is testable. */
    String floorFor(Posting posting, LocalDate today) {
        StrategicClass lane = posting.getStrategicClass();
        if (lane == null) {
            // Screened before the country columns existed. The old answer is
            // still the right one for a row that has not been re-screened yet.
            return legacyFloorFor(posting);
        }

        return switch (lane) {
            // Not read from the country policy: this is a rule about the home
            // country's own geography rather than about a country, and the home
            // policy carries the home-metro figure.
            case INDIA_OTHER -> inr(floors.indiaOtherInr())
                    + " (relocation: ~Rs 30k/month rent and food)";
            case INDIA_HOME -> homeFloor(today, "home metro");
            case INTERNATIONAL_REMOTE -> homeFloor(today, "remote into India - no rent");
            case INTERNATIONAL_RELOCATION -> relocationFloor(posting.getCountryCode(), today);
            case UNCLASSIFIED -> "floor unknown - country not classified";
        };
    }

    /** The home-country floor, which is what a job worked from home has to clear. */
    private String homeFloor(LocalDate today, String note) {
        CountryPolicy home = strategy.policyFor(strategy.homeCountry());
        if (!home.hasSalaryFloor()) {
            return "no floor recorded for " + home.displayName();
        }
        return amount(home) + " (" + note + ")" + staleness(home, today);
    }

    private String relocationFloor(String countryCode, LocalDate today) {
        CountryPolicy policy = strategy.policyFor(countryCode);
        if (!policy.hasSalaryFloor()) {
            // Unknown, said out loud. A country with no verified threshold is not
            // a country with a threshold of zero, and it is not "outside target
            // geographies" either - that answer conflated eight different things.
            return "no floor established for " + policy.displayName();
        }
        String basis = policy.salaryFloorBasis() == null || policy.salaryFloorBasis().isBlank()
                ? policy.displayName() : policy.salaryFloorBasis();
        return amount(policy) + " (" + basis + ")" + staleness(policy, today);
    }

    /** Says so when the figure is past its own check-by date, rather than hiding it. */
    private static String staleness(CountryPolicy policy, LocalDate today) {
        return policy.isStale(today) ? " [re-verify: due " + policy.verifyBy() + "]" : "";
    }

    private static String amount(CountryPolicy policy) {
        return "INR".equalsIgnoreCase(policy.currency())
                ? inr(policy.salaryFloor())
                : policy.currency() + " " + GROUPED.format(policy.salaryFloor());
    }

    /**
     * The pre-migration answer, kept for rows that have not been re-screened.
     *
     * <p>Deleting it would make every posting in an un-migrated database report
     * "floor unknown" - a silent regression in the one place the digest is read.
     * It goes when the last row has a strategic class.
     */
    private String legacyFloorFor(Posting posting) {
        Country country = posting.getCountry();
        if (country == null) {
            return "floor unknown";
        }
        return switch (country) {
            case INDIA -> geo.isMumbai(posting.getLocation())
                    // Living at home covers housing, so nearly all of take-home
                    // can service the loan. That is what makes 7L in Mumbai
                    // better than 10L elsewhere, and why the floors differ.
                    ? inr(floors.mumbaiInr()) + " (Mumbai - no rent)"
                    : inr(floors.indiaOtherInr()) + " (relocation: ~Rs 30k/month rent and food)";
            case REMOTE -> inr(floors.mumbaiInr()) + " (remote into India - no rent)";
            case GERMANY -> eur(floors.germanyEur()) + " (Blue Card shortage-occupation threshold)";
            case IRELAND -> eur(floors.irelandEur()) + " (CSEP basic salary; below "
                    + eur(floors.dublinComfortEur()) + " Dublin rent makes it ~Mumbai 10L)";
            case NETHERLANDS -> eur(floors.netherlandsEur())
                    + " (under-30 kennismigrant, excl. 8% holiday allowance)";
            case OTHER -> "outside target geographies";
        };
    }

    /** True when this posting's country has a figure that needs re-checking. */
    public boolean isStale(Posting posting, LocalDate today) {
        String code = posting.getCountryCode() != null
                ? posting.getCountryCode() : CountryCodes.fromLegacy(posting.getCountry());
        return strategy.policyFor(code).isStale(today);
    }

    private static String inr(BigDecimal amount) {
        return amount == null ? "?" : "Rs " + indianGrouping(amount);
    }

    /**
     * Formats 700000 as 7,00,000.
     *
     * <p>Written by hand because neither route from the JDK works: the en-IN
     * locale returned 700,000 here, and {@code DecimalFormat} only honours the
     * rightmost grouping interval of a pattern, so "#,##,##0" also produces
     * 700,000. The lakh grouping is the whole reason for spelling the number
     * out - "Rs 700,000" invites a misread as seven million.
     */
    public static String indianGrouping(BigDecimal amount) {
        String digits = amount.setScale(0, RoundingMode.HALF_UP).abs().toPlainString();
        String sign = amount.signum() < 0 ? "-" : "";
        if (digits.length() <= 3) {
            return sign + digits;
        }
        String lastThree = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);

        // Everything above the last three digits groups in twos, from the right.
        StringBuilder grouped = new StringBuilder(lastThree);
        int cursor = rest.length();
        while (cursor > 2) {
            grouped.insert(0, rest.substring(cursor - 2, cursor) + ",");
            cursor -= 2;
        }
        grouped.insert(0, rest.substring(0, cursor) + ",");
        return sign + grouped;
    }

    private static String eur(BigDecimal amount) {
        return amount == null ? "?" : "EUR " + GROUPED.format(amount);
    }
}
