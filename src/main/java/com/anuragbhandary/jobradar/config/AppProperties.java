package com.anuragbhandary.jobradar.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything user-specific, bound from {@code application.yml}.
 *
 * <p>No path, key or spreadsheet id is ever hardcoded in Java source. Secrets
 * arrive through environment variables that the YAML references.
 *
 * <p>The screening rules and salary floors live here rather than as constants in
 * the filter classes because they change on a schedule the code does not: the
 * visa thresholds are indexed annually, and the title exclusion list grows every
 * time a board invents a new senior-sounding word. Keeping them in YAML also
 * puts all the rules on one screen, which matters when the question is "why was
 * this rejected?"
 */
@ConfigurationProperties(prefix = "job-radar")
public record AppProperties(
        Google google,
        String outputDir,
        Http http,
        Fixtures fixtures,
        Screening screening,
        SalaryFloors salaryFloors) {

    public record Google(String credentialsPath, String spreadsheetId, String sheetName) {

        /** Sheets config is optional at boot; only the Sheets commands require it. */
        public boolean isConfigured() {
            return spreadsheetId != null && !spreadsheetId.isBlank();
        }
    }

    /**
     * HTTP manners. These endpoints belong to other people and are being used
     * without an agreement, so the delay and user-agent are not optional extras.
     */
    public record Http(
            String userAgent,
            long delayBetweenRequestsMs,
            int timeoutSeconds,
            int maxRetries) {
    }

    /** Raw API responses saved to disk for replay and after-the-fact debugging. */
    public record Fixtures(boolean enabled, String dir) {
    }

    /**
     * Screening rules, applied in order: geography, then title, then years.
     *
     * @param maxMinYears the highest "minimum years" still acceptable. 1, because
     *                    a year of internship reads onto "1-3 years" but not onto
     *                    "2+ years professional".
     */
    public record Screening(
            Geo geo,
            List<String> titleInclude,
            List<String> titleExclude,
            List<String> graduateSignals,
            java.util.Map<String, String> excludedBoards,
            int maxMinYears) {
    }

    /**
     * Place names that count as each target country.
     *
     * <p>{@code excludedLocations} is the important one, and the reason it is
     * long. A posting reading "Remote (Argentina)" or "Junior Software Engineer
     * (Mexico)" is remote <em>within that country</em> - the country name is a
     * hiring restriction, not a perk - and "Remote, London" is no more reachable
     * from Mumbai than plain "London" is. A location matching one of these and
     * none of the target lists is rejected however remote-friendly it reads.
     */
    public record Geo(
            List<String> indiaCities,
            List<String> mumbaiCities,
            List<String> germanyCities,
            List<String> irelandCities,
            List<String> netherlandsCities,
            List<String> remoteMarkers,
            List<String> excludedLocations,
            List<String> falseFriends) {
    }

    /**
     * Minimum acceptable salary per geography.
     *
     * <p>These look wrong without the reasoning. They are set on money left after
     * housing rather than on headline salary, and they assume the home city costs
     * nothing to live in. Relocating anywhere else in India runs to roughly
     * INR 30,000 a month in rent and food, which is why the out-of-city floor is
     * double the home one: INR 10 lakh in Bangalore leaves less than INR 7 lakh
     * at home.
     *
     * <p>The three European figures are legal visa thresholds, not preferences,
     * and every one of them is re-indexed annually - hence {@code verifyBy}.
     *
     * @param mumbaiInr        the home metro, per screening.geo.mumbai-cities,
     *                         or India-remote
     * @param indiaOtherInr    anywhere else in India
     * @param germanyEur       Blue Card shortage-occupation threshold
     * @param irelandEur       Critical Skills Employment Permit, basic salary only
     * @param netherlandsEur   under-30 kennismigrant rate, excl. 8% holiday allowance
     * @param dublinComfortEur below this, Dublin rent leaves about what Mumbai
     *                         INR 10 lakh would; legal, but not actually better
     * @param verifyBy         date after which these must be re-checked at source
     */
    public record SalaryFloors(
            BigDecimal mumbaiInr,
            BigDecimal indiaOtherInr,
            BigDecimal germanyEur,
            BigDecimal irelandEur,
            BigDecimal netherlandsEur,
            BigDecimal dublinComfortEur,
            LocalDate verifyBy) {

        /** True once the visa thresholds are old enough to be worth re-checking. */
        public boolean needsReverification(LocalDate today) {
            return verifyBy == null || !today.isBefore(verifyBy);
        }
    }
}
