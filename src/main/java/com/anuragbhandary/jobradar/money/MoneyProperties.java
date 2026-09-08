package com.anuragbhandary.jobradar.money;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rates for showing a foreign salary in rupees.
 *
 * <p>Configured, not fetched. The tool is expected to work with the network off,
 * and a conversion that silently fails to a stale cache is worse than one the
 * reader knows the age of. So the rate is a number in a file, the date it was set
 * is next to it, and the UI prints the rate it used rather than only the result.
 *
 * <p>These are for orientation, not for negotiation. Nobody should type a
 * converted figure into a salary box: the number that goes on a German form is
 * euros, and the rupee figure is only there to answer "is that a lot?".
 *
 * @param inrPer  rupees per unit of each currency, keyed by ISO code
 * @param ratesAs the day those rates were last checked, printed alongside them
 */
@ConfigurationProperties(prefix = "job-radar.money")
public record MoneyProperties(Map<String, BigDecimal> inrPer, String ratesAs) {

    public BigDecimal rateFor(String currency) {
        if (currency == null || inrPer == null) {
            return null;
        }
        return inrPer.get(currency.toUpperCase(java.util.Locale.ROOT));
    }

    public String ratesAs() {
        return ratesAs == null || ratesAs.isBlank() ? "not dated" : ratesAs;
    }
}
