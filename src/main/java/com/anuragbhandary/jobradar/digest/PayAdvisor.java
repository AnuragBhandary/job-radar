package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.RoleCategory;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.filter.Internship;
import com.anuragbhandary.jobradar.filter.YearsExtraction;
import com.anuragbhandary.jobradar.strategy.CountryPolicy;
import com.anuragbhandary.jobradar.strategy.CountryStrategy;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The expected pay for a posting and the figure to ask for (2026-10-19).
 *
 * <p>{@link SalaryFloorAdvisor} never estimates, because a guessed number gets
 * quoted back as though the posting said it. He asked for one anyway, so every
 * estimate here says "estimate" on its face, and a stated range always wins.
 *
 * <p>The ask: about 30% up a wide range (top at least 1.5 times the bottom),
 * the midpoint of a narrow one, and never under the relocation floor of a visa
 * country. The bottom of a range is never the ask.
 */
@Component
public class PayAdvisor {

    /** How far up a wide range the ask sits. */
    static final double WIDE_RANGE_POSITION = 0.3;

    /** A range whose top is this many times its bottom counts as wide. */
    static final double WIDE_RANGE_RATIO = 1.5;

    /** Under this an amount is not a year's pay (after monthly figures are scaled). */
    private static final long ANNUAL_MINIMUM = 10_000;

    private static final Pattern AMOUNT = Pattern.compile(
            "(\\d+(?:[.,]\\d{3})*(?:\\.\\d+)?)\\s*(k\\b|lpa\\b|lakhs?\\b|lacs?\\b|l\\b)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MONTHLY = Pattern.compile(
            "per\\s+month|/\\s*month|\\bmonthly\\b|\\bp\\.?m\\.?(?![a-z])|a\\s+month", Pattern.CASE_INSENSITIVE);

    private static final Pattern HOURLY = Pattern.compile(
            "per\\s+hour|/\\s*h(?:ou)?r\\b|\\bhourly\\b|an\\s+hour", Pattern.CASE_INSENSITIVE);

    private final PayProperties table;
    private final CountryStrategy strategy;

    public PayAdvisor(PayProperties table, CountryStrategy strategy) {
        this.table = table;
        this.strategy = strategy;
    }

    /** What to show, or null when there is neither a stated figure nor an estimate. */
    public record Pay(String currency, long low, long high, boolean stated, boolean monthly,
                      String basis, long ask, boolean raisedToFloor) {
    }

    /** The one-line card text, or null when nothing can be said. */
    public String line(Posting posting) {
        Pay pay = payFor(posting);
        return pay == null ? null : format(pay, LocalDate.now());
    }

    public Pay payFor(Posting posting) {
        Pay stated = stated(posting);
        return stated != null ? stated : estimate(posting);
    }

    // ----- stated pay -----

    Pay stated(Posting posting) {
        String text = posting.getSalaryText();
        if (text == null || text.isBlank() || HOURLY.matcher(text).find()) {
            return null;
        }
        String currency = currencyOf(text);
        if (currency == null) {
            return null;
        }
        boolean monthly = MONTHLY.matcher(text).find();
        List<Long> amounts = amounts(text, monthly);
        if (amounts.isEmpty()) {
            return null;
        }
        long low = amounts.get(0);
        long high = amounts.size() > 1 ? Math.max(low, amounts.get(1)) : low;
        low = Math.min(low, amounts.size() > 1 ? amounts.get(1) : low);
        return withAsk(posting, currency, low, high, true, false, "stated");
    }

    static String currencyOf(String text) {
        String t = text.toUpperCase(Locale.ROOT);
        if (t.contains("£") || t.contains("GBP")) {
            return "GBP";
        }
        if (t.contains("€") || t.contains("EUR")) {
            return "EUR";
        }
        if (t.contains("₹") || t.contains("INR") || t.matches("(?s).*\\bRS\\.?\\b.*")
                || t.matches("(?s).*\\b(LPA|LAKHS?|LACS?)\\b.*")) {
            return "INR";
        }
        if (t.contains("AED")) {
            return "AED";
        }
        if (t.contains("$") || t.contains("USD")) {
            return "USD";
        }
        return null;
    }

    /**
     * Annual amounts in the order written. A lakh suffix on the second number of
     * "8-12 LPA" applies to the first too.
     */
    static List<Long> amounts(String text, boolean monthly) {
        List<double[]> raw = new ArrayList<>();
        Matcher m = AMOUNT.matcher(text);
        String lastSuffix = null;
        while (m.find()) {
            String number = m.group(1);
            // "55.000" and "70,300" are thousands; "70,300.00" keeps its cents.
            String digits = number.matches("\\d{1,3}(?:\\.\\d{3})+") ? number.replace(".", "")
                    : number.replace(",", "");
            double value;
            try {
                value = Double.parseDouble(digits);
            } catch (NumberFormatException e) {
                continue;
            }
            String suffix = m.group(2) == null ? null : m.group(2).toLowerCase(Locale.ROOT);
            raw.add(new double[] {value, multiplier(suffix)});
            if (suffix != null) {
                lastSuffix = suffix;
            }
        }
        List<Long> amounts = new ArrayList<>();
        for (double[] r : raw) {
            double mult = r[1];
            // A bare small number beside a lakh range ("8-12 LPA").
            if (mult == 1 && lastSuffix != null && r[0] < 1000) {
                mult = multiplier(lastSuffix);
            }
            long annual = Math.round(r[0] * mult * (monthly ? 12 : 1));
            if (annual >= ANNUAL_MINIMUM) {
                amounts.add(annual);
            }
            if (amounts.size() == 2) {
                break;
            }
        }
        return amounts;
    }

    private static double multiplier(String suffix) {
        if (suffix == null) {
            return 1;
        }
        return suffix.equals("k") ? 1_000 : 100_000;
    }

    // ----- estimates -----

    Pay estimate(Posting posting) {
        if (table == null || table.countries() == null) {
            return null;
        }
        String key = posting.getStrategicClass() == StrategicClass.INTERNATIONAL_REMOTE
                ? "REMOTE" : posting.getCountryCode();
        PayProperties.Country country = key == null ? null : table.countries().get(key);
        if (country == null) {
            return null;
        }
        String tier = tier(posting);
        boolean intern = Internship.isInternship(posting.getTitle());
        List<Long> band;
        if (intern) {
            band = tier.equals("big tech") && country.internMonthlyBigTech() != null
                    ? country.internMonthlyBigTech() : country.internMonthly();
        } else {
            band = switch (tier) {
                case "big tech" -> country.bigTech();
                case "bank" -> country.bank();
                case "service" -> country.service();
                default -> null;
            };
            if (band == null) {
                band = country.standard();
                tier = "standard";
            }
        }
        if (band == null || band.size() != 2) {
            return null;
        }
        RoleCategory category = RoleCategory.of(posting.getTitle());
        double factor = 1.0;
        // Data scientists share the analytics category but not analysts' pay.
        boolean scientist = posting.getTitle() != null
                && posting.getTitle().toLowerCase(Locale.ROOT).contains("data scien");
        if (!scientist && table.categoryFactor() != null
                && table.categoryFactor().containsKey(category.name())) {
            factor *= table.categoryFactor().get(category.name());
        }
        String city = city(posting);
        if (city != null) {
            factor *= table.cityFactor().get(city);
        }
        Integer years = posting.getMinYears();
        boolean experienced = years != null && years != YearsExtraction.NONE_STATED && years >= 1;
        if (!intern && experienced && table.experiencedFactor() != null) {
            factor *= table.experiencedFactor();
        }
        long low = Math.round(band.get(0) * factor);
        long high = Math.round(band.get(1) * factor);
        String level = intern ? "intern" : experienced ? years + "+ yrs" : "entry";
        String basis = "estimate: " + tier + ", " + level + ", " + category.label()
                + (city == null ? "" : ", " + city);
        return withAsk(posting, country.currency(), low, high, false, intern, basis);
    }

    /** The configured city named in the location, or null. */
    private String city(Posting posting) {
        if (table.cityFactor() == null || posting.getLocation() == null) {
            return null;
        }
        String location = posting.getLocation().toLowerCase(Locale.ROOT);
        for (String city : table.cityFactor().keySet()) {
            if (Pattern.compile("\\b" + Pattern.quote(city) + "\\b").matcher(location).find()) {
                return city;
            }
        }
        return null;
    }

    /** big tech, bank, service or standard. */
    String tier(Posting posting) {
        if (matches(posting, table.bigTechEmployers())) {
            return "big tech";
        }
        if (matches(posting, table.bankEmployers())) {
            return "bank";
        }
        if (matches(posting, table.serviceEmployers())) {
            return "service";
        }
        return "standard";
    }

    /**
     * The board tenant exactly, a single-employer source (Amazon, Google, Apple),
     * or a whole word of an aggregator's "Role @ Company".
     */
    static boolean matches(Posting posting, List<String> employers) {
        if (employers == null) {
            return false;
        }
        String token = posting.getBoardToken() == null
                ? "" : posting.getBoardToken().toLowerCase(Locale.ROOT);
        int slash = token.indexOf('/');
        String tenant = slash < 0 ? token : token.substring(0, slash);
        String source = posting.getSource() == null ? "" : posting.getSource().name().toLowerCase(Locale.ROOT);
        String title = posting.getTitle() == null ? "" : posting.getTitle().toLowerCase(Locale.ROOT);
        int at = title.lastIndexOf(" @ ");
        String company = at < 0 ? "" : title.substring(at + 3);
        for (String e : employers) {
            String k = e.toLowerCase(Locale.ROOT);
            if (tenant.equals(k) || source.equals(k)
                    || (!company.isEmpty() && Pattern.compile("\\b" + Pattern.quote(k) + "\\b")
                    .matcher(company).find())) {
                return true;
            }
        }
        return false;
    }

    // ----- the ask -----

    private Pay withAsk(Posting posting, String currency, long low, long high,
                        boolean stated, boolean monthly, String basis) {
        long ask = ask(low, high, currency);
        boolean raised = false;
        BigDecimal floor = visaFloor(posting, currency);
        if (!monthly && floor != null && ask < floor.longValue()) {
            ask = roundUp(floor.longValue(), currency);
            raised = true;
        }
        return new Pay(currency, low, high, stated, monthly, basis, ask, raised);
    }

    /** About 30% up a wide range, the middle of a narrow one, rounded. */
    static long ask(long low, long high, String currency) {
        double raw = high >= low * WIDE_RANGE_RATIO
                ? low + WIDE_RANGE_POSITION * (high - low)
                : (low + high) / 2.0;
        return round(Math.round(raw), currency);
    }

    private static long step(long amount, String currency) {
        if ("INR".equals(currency)) {
            return amount >= 200_000 ? 50_000 : 1_000;
        }
        return 1_000;
    }

    private static long round(long amount, String currency) {
        long step = step(amount, currency);
        return Math.round((double) amount / step) * step;
    }

    private static long roundUp(long amount, String currency) {
        long step = step(amount, currency);
        return (amount + step - 1) / step * step;
    }

    /** The relocation floor, when it is in the same currency; otherwise null. */
    private BigDecimal visaFloor(Posting posting, String currency) {
        if (strategy == null || posting.getStrategicClass() != StrategicClass.INTERNATIONAL_RELOCATION) {
            return null;
        }
        CountryPolicy policy = strategy.policyFor(posting.getCountryCode());
        if (!policy.hasSalaryFloor() || !currency.equalsIgnoreCase(policy.currency())) {
            return null;
        }
        return policy.salaryFloor();
    }

    // ----- text -----

    String format(Pay pay, LocalDate today) {
        StringBuilder out = new StringBuilder();
        out.append(range(pay)).append(" (").append(pay.basis());
        if (!pay.stated()) {
            out.append(", +-25%");
        }
        out.append(")");
        out.append(" · ask ").append(amount(pay.currency(), pay.ask(), pay.monthly()));
        String inr = inrPerMonth(pay.currency(), pay.ask(), pay.monthly());
        if (inr != null) {
            out.append(" (about ").append(inr).append(")");
        }
        if (pay.raisedToFloor()) {
            out.append(", raised to the visa floor");
        }
        if (!pay.stated() && table.verifyBy() != null && today.isAfter(table.verifyBy())) {
            out.append(" [re-verify pay table: due ").append(table.verifyBy()).append("]");
        }
        return out.toString();
    }

    private static String range(Pay pay) {
        if (pay.low() == pay.high()) {
            return amount(pay.currency(), pay.low(), pay.monthly());
        }
        if ("INR".equals(pay.currency()) && !pay.monthly()) {
            return "Rs " + lakhs(pay.low()) + "-" + lakhs(pay.high()) + " LPA";
        }
        String unit = pay.monthly() ? "/month" : "/yr";
        return prefix(pay.currency()) + grouped(pay.low(), pay.currency()) + "-"
                + grouped(pay.high(), pay.currency()) + unit;
    }

    private static String amount(String currency, long value, boolean monthly) {
        if ("INR".equals(currency) && !monthly) {
            return "Rs " + lakhs(value) + " LPA";
        }
        return prefix(currency) + grouped(value, currency) + (monthly ? "/month" : "/yr");
    }

    /** "Rs 3.8L/month" for a foreign annual figure; null for rupees. */
    private String inrPerMonth(String currency, long value, boolean monthly) {
        if ("INR".equals(currency) || table.inrPer() == null || !table.inrPer().containsKey(currency)) {
            return null;
        }
        double perMonth = value * table.inrPer().get(currency).doubleValue() / (monthly ? 1 : 12);
        return "Rs " + lakhs(Math.round(perMonth)) + "L/month";
    }

    /** 1250000 as "12.5", 900000 as "9". */
    static String lakhs(long rupees) {
        double l = Math.round(rupees / 10_000.0) / 10.0;
        return l == Math.floor(l) ? String.valueOf((long) l) : String.valueOf(l);
    }

    private static String prefix(String currency) {
        return switch (currency) {
            case "INR" -> "Rs ";
            default -> currency + " ";
        };
    }

    private static String grouped(long value, String currency) {
        if ("INR".equals(currency)) {
            return SalaryFloorAdvisor.indianGrouping(BigDecimal.valueOf(value));
        }
        return NumberFormat.getIntegerInstance(Locale.UK).format(value);
    }
}
