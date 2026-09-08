package com.anuragbhandary.jobradar.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * What to put in the salary box, worked out once so it is not worked out at
 * two in the morning on a form with a deadline.
 *
 * <p>Carries four numbers because forms ask for all four shapes: a year and a
 * month, in the currency the employer pays in. The rupee figures are for the
 * reader, never for the form.
 *
 * @param currency  the code the employer pays in
 * @param minAnnual the floor. Below this the job does not clear its costs
 * @param askAnnual what to actually write down
 * @param inrRate   rupees per unit used for the conversion, or null when unknown
 */
public record AskingPrice(String currency, BigDecimal minAnnual, BigDecimal askAnnual,
        BigDecimal inrRate, String ratesAs) {

    public BigDecimal minMonthly() {
        return monthly(minAnnual);
    }

    public BigDecimal askMonthly() {
        return monthly(askAnnual);
    }

    private static BigDecimal monthly(BigDecimal annual) {
        return annual == null ? null : annual.divide(BigDecimal.valueOf(12), 0, RoundingMode.HALF_UP);
    }

    public boolean isRupees() {
        return "INR".equalsIgnoreCase(currency);
    }

    /** True when a rupee equivalent can be shown and would tell the reader anything. */
    public boolean convertible() {
        return !isRupees() && inrRate != null && inrRate.signum() > 0;
    }

    /**
     * The annual range, in the employer's currency.
     *
     * <p>Rupee amounts are shown in lakhs rather than with thousands separators.
     * "INR 1,800,000" is Western grouping on an Indian figure and is read wrong
     * at a glance by exactly the person this is for; "Rs 18L" is not.
     */
    public String annualRange() {
        return isRupees() ? lakhRange(minAnnual, askAnnual)
                : currency + " " + plain(minAnnual) + " - " + plain(askAnnual);
    }

    /** The same, per month. */
    public String monthlyRange() {
        return isRupees() ? thousandRange(minMonthly(), askMonthly())
                : currency + " " + plain(minMonthly()) + " - " + plain(askMonthly());
    }

    /** "Rs 55L - 65L" - the annual figure a rupee-thinking reader can weigh. */
    public String annualInRupees() {
        return convertible() ? lakhRange(toInr(minAnnual), toInr(askAnnual))
                : lakhRange(minAnnual, askAnnual);
    }

    public String monthlyInRupees() {
        // Convert the year and then divide, rather than converting an already
        // rounded month. Rounding to the nearest euro first and then multiplying
        // by a hundred turns a 33-rupee rounding into a 3,300-rupee one.
        BigDecimal min = convertible() ? monthly(toInr(minAnnual)) : minMonthly();
        BigDecimal ask = convertible() ? monthly(toInr(askAnnual)) : askMonthly();
        return thousandRange(min, ask);
    }

    /** One "Rs", not two: "Rs 55L - 65L". */
    private static String lakhRange(BigDecimal min, BigDecimal max) {
        return "Rs " + lakhs(min) + " - " + lakhs(max);
    }

    private static String thousandRange(BigDecimal min, BigDecimal max) {
        return "Rs " + thousands(min) + " - " + thousands(max);
    }

    private BigDecimal toInr(BigDecimal amount) {
        return amount == null ? null : amount.multiply(inrRate).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Lakhs, because that is how the number is read here.
     *
     * <p>"Rs 51,70,000" and "Rs 51.7L" are the same figure, and only one of them
     * can be taken in at a glance in a list of forty rows.
     */
    private static String lakhs(BigDecimal amount) {
        if (amount == null) {
            return "?";
        }
        BigDecimal inLakhs = amount.divide(BigDecimal.valueOf(100_000), 1, RoundingMode.HALF_UP);
        // A crore reads better than "180L" once the number gets that big.
        if (inLakhs.compareTo(BigDecimal.valueOf(100)) >= 0) {
            return amount.divide(BigDecimal.valueOf(10_000_000), 2, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString() + "Cr";
        }
        return inLakhs.stripTrailingZeros().toPlainString() + "L";
    }

    /** Indian grouping: 1,80,000 rather than 180,000. */
    private static String thousands(BigDecimal amount) {
        if (amount == null) {
            return "?";
        }
        String digits = String.valueOf(Math.abs(amount.longValue()));
        if (digits.length() <= 3) {
            return digits;
        }
        String last3 = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);
        StringBuilder grouped = new StringBuilder();
        while (rest.length() > 2) {
            grouped.insert(0, "," + rest.substring(rest.length() - 2));
            rest = rest.substring(0, rest.length() - 2);
        }
        return (rest.isEmpty() ? "" : rest) + grouped + "," + last3;
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? "?" : String.format(Locale.ROOT, "%,d", amount.longValue());
    }
}
