package com.anuragbhandary.jobradar.digest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The pay estimate table ({@code job-radar.pay}). Claims about the market, so
 * they live in configuration with a check-by date, beside the salary floors.
 *
 * @param verifyBy          when the figures are due a re-check
 * @param inrPer            rupees per unit of each foreign currency
 * @param countries         per country code, or REMOTE for a foreign employer
 *                          worked from India
 * @param bigTechEmployers  tenants, sources or company words paid on the big-tech tier
 * @param bankEmployers     tenants or company words paid on the bank tier
 * @param serviceEmployers  tenants or company words paid on the service tier
 * @param categoryFactor    RoleCategory name to a multiplier; absent is 1.0
 * @param experiencedFactor multiplier for a stated minimum of 1-2 years
 * @param cityFactor        a location word (london) to a multiplier on its country's row
 */
@ConfigurationProperties(prefix = "job-radar.pay")
public record PayProperties(
        LocalDate verifyBy,
        Map<String, BigDecimal> inrPer,
        Map<String, Country> countries,
        List<String> bigTechEmployers,
        List<String> bankEmployers,
        List<String> serviceEmployers,
        Map<String, Double> categoryFactor,
        Double experiencedFactor,
        Map<String, Double> cityFactor) {

    /** Annual [low, high] per tier at 0 years; intern figures are monthly. */
    public record Country(
            String currency,
            List<Long> standard,
            List<Long> service,
            List<Long> bank,
            List<Long> bigTech,
            List<Long> internMonthly,
            List<Long> internMonthlyBigTech) {
    }
}
