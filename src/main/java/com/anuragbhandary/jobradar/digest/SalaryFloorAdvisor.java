package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.filter.GeoFilter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Says which salary floor applies to a posting, and why.
 *
 * <p>No salary is ever estimated here. Most postings state none, and a guessed
 * number in a digest is worse than a blank one - it would be quoted back later
 * as though it came from the posting. The floor is stated so a human can compare
 * against it once they know the actual figure.
 */
@Component
public class SalaryFloorAdvisor {

    private static final NumberFormat EUR = NumberFormat.getInstance(Locale.GERMANY);

    private final AppProperties.SalaryFloors floors;
    private final GeoFilter geo;

    public SalaryFloorAdvisor(AppProperties properties, GeoFilter geo) {
        this.floors = properties.salaryFloors();
        this.geo = geo;
    }

    /** A one-line statement of the floor and the reason it is that number. */
    public String floorFor(Posting posting) {
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
    static String indianGrouping(BigDecimal amount) {
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
        return amount == null ? "?" : "EUR " + EUR.format(amount);
    }
}
