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
            int maxRetries,
            java.util.Map<String, Long> siteDelaysMs,
            java.util.Map<String, Integer> operatorLanes) {

        /**
         * How many hosts of one operator may be paced in parallel. Each Oracle
         * cloud tenant is a separate customer's site, but they all share the
         * operator "oraclecloud.com", so with one queue 1,189 Oracle boards took
         * over an hour and a half to get through. N lanes allow at most N requests
         * per gap to that operator in all, each tenant still on the full gap.
         */
        public int lanesFor(String site) {
            Integer lanes = operatorLanes == null ? null : operatorLanes.get(site);
            return lanes == null || lanes < 1 ? 1 : lanes;
        }

        /**
         * The gap for one site ({@code microsoft.com}). A few sites answer 429 to a
         * request every half second but take every one at 1.5 s, and get their own.
         */
        public long delayFor(String site) {
            Long own = siteDelaysMs == null ? null : siteDelaysMs.get(site);
            return own == null ? delayBetweenRequestsMs : Math.max(own, delayBetweenRequestsMs);
        }
    }

    /** Raw API responses saved to disk for replay and after-the-fact debugging. */
    public record Fixtures(boolean enabled, String dir) {
    }

    /**
     * Screening rules, applied in order: geography, then title, then years.
     *
     * @param maxMinYears the highest "minimum years" still acceptable at most
     *                    employers.
     * @param bigTechBoards employers that verify experience formally (board
     *                    tokens, the part of a Workday token before the first
     *                    slash, or a source name such as {@code amazon}). They
     *                    get {@code bigTechMaxMinYears} instead, and a stated
     *                    non-internship or full-time experience requirement
     *                    rejects.
     * @param bigTechMaxMinYears the years cap for {@code bigTechBoards}.
     * @param fullStackExclude title words for full-stack and frontend roles,
     *                    rejected everywhere except Amazon.
     * @param domainExclude title words for finance, risk and operations work
     *                    (tax, KYC, audit), rejected unless the title also has a
     *                    {@code domainKeep} word: "Analyst - TAX" goes,
     *                    "Software Development Engineer, Tax Engine" stays.
     * @param domainKeep  software and data words that keep such a title.
     */
    public record Screening(
            Geo geo,
            List<String> titleInclude,
            List<String> titleExclude,
            List<String> graduateSignals,
            java.util.Map<String, String> excludedBoards,
            int maxMinYears,
            List<String> bigTechBoards,
            int bigTechMaxMinYears,
            List<String> fullStackExclude,
            List<String> domainExclude,
            List<String> domainKeep) {

        /** Whether this posting's employer is on the big-tech list. */
        public boolean isBigTech(com.anuragbhandary.jobradar.domain.Posting posting) {
            if (bigTechBoards == null || bigTechBoards.isEmpty()) {
                return false;
            }
            String token = posting.getBoardToken() == null
                    ? "" : posting.getBoardToken().toLowerCase(java.util.Locale.ROOT);
            int slash = token.indexOf('/');
            String tenant = slash < 0 ? token : token.substring(0, slash);
            // A source name counts only for a source that is one employer. Matching
            // it for every source made "workday" (the company) put every Workday
            // tenant on the list, until 2026-09-28.
            String source = switch (posting.getSource()) {
                case AMAZON -> "amazon";
                case GOOGLE -> "google";
                case APPLE -> "apple";
                case null, default -> "";
            };
            for (String board : bigTechBoards) {
                String b = board.toLowerCase(java.util.Locale.ROOT);
                if (b.equals(token) || b.equals(tenant) || b.equals(source)) {
                    return true;
                }
            }
            return false;
        }
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
     * INR 30,000 a month in rent and food, which is why the out-of-city floor sits
     * above the home one. It is a floor, not an ask, and the gap is kept narrow on
     * purpose so that a reasonable offer in another city is not screened out.
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
