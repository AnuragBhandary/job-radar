package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Source;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One employer on Oracle's recruiting cloud, seen through all its candidate sites.
 *
 * <p>An Oracle tenant runs several candidate sites over one set of requisitions:
 * an external site, an alumni site, a veterans site, an internal one. Discovery
 * added each site as its own board and labelled it with the site's name, so one
 * American Express requisition arrived twice ("Candidate Experience site" and
 * "American Express") and one Cummins requisition five times. On 2026-10-01 that
 * was about 400 of 2,533 review rows. Tenants also expose test and development
 * copies of themselves ({@code ecyq-test}, {@code fa-ewjt-dev1-saasfaprod1}),
 * which carry the same requisitions again.
 *
 * <p>So the tenant is the unit: a requisition is the same job on every site of
 * the tenant and its copies, and a site whose name says nothing ("Candidate
 * Experience site", "Alumni", the tenant code itself) borrows the employer's name
 * from a sibling site that has one.
 */
public final class OracleTenants {

    private OracleTenants() {
    }

    /** A pod suffix or infix that marks a copy, not the live site. */
    private static final Pattern NON_PRODUCTION = Pattern.compile("-(?:test|dev)\\d*(?=-|$)");

    /**
     * The tenant's own code: {@code fa-ewjt-dev1-saasfaprod1.fa.ocs.oraclecloud.com}
     * and {@code fa-ewjt-saasfaprod1...} are both {@code ewjt}; {@code ecyq-test.fa.em2...}
     * and {@code ecyq.fa.em2...} are both {@code ecyq}.
     */
    public static String tenant(String token) {
        String host = host(token);
        String pod = host.split("\\.")[0].toLowerCase(Locale.ROOT);
        pod = NON_PRODUCTION.matcher(pod).replaceAll("");
        return pod.replaceFirst("^fa-", "").replaceFirst("-saasfa\\w*$", "");
    }

    /** Whether the token points at a test or development copy of a tenant. */
    public static boolean isNonProduction(String token) {
        return NON_PRODUCTION.matcher(host(token).split("\\.")[0].toLowerCase(Locale.ROOT)).find();
    }

    /** {@code name/host/site/locationId}; the host is the second part. */
    static String host(String token) {
        String[] parts = token == null ? new String[0] : token.split("/");
        return parts.length > 1 ? parts[1] : (token == null ? "" : token);
    }

    /** {@code host/site}, so a site's copy on a test pod can be matched to the live one. */
    static String siteKey(String token) {
        String[] parts = token.split("/");
        return tenant(token) + "/" + (parts.length > 2 ? parts[2] : "") + "/"
                + (parts.length > 3 ? parts[3] : "");
    }

    /**
     * The same requisition on any site of the tenant: {@code oracle|ewjt|19829}.
     * Empty for other sources.
     */
    public static Optional<String> requisitionKey(Source source, String token, String externalId) {
        if (source != Source.ORACLE_HCM || token == null || externalId == null) {
            return Optional.empty();
        }
        return Optional.of("oracle|" + tenant(token) + "|" + externalId);
    }

    /**
     * Words that name the site's purpose or audience rather than the employer:
     * "Cummins Veterans", "Opportunities at Ipsos", "Chase- Candidate Experience
     * page", "WM Old Version", "EXL Talent Acquisition Team".
     */
    private static final Pattern SITE_WORDS = Pattern.compile(
            "(?i)\\b(?:candidate\\s+experience(?:\\s+(?:site|page))?|experience\\s+site"
                    + "|talent\\s+acquisition(?:\\s+team)?|recruitment\\s+team|all\\s+functions"
                    + "|veterans|alumni|lateral|casual|referral|campus|early(?:\\s+(?:talent|careers))?"
                    + "|old\\s+version|classic|inactive|recommended|contingent\\s+worker"
                    + "|opportunities(?:\\s+at)?|listings(?:\\s+at)?|careers?(?:\\s+at)?|jobs?(?:\\s+at)?"
                    + "|external|internal|site|portal|page|-\\s*v\\d+)\\b");

    /** Names that are not an employer at all. */
    private static final Pattern NOT_A_NAME = Pattern.compile(
            "(?i)saasfa|\\bdev\\s?\\d*\\b|\\btest\\b|https?:|do\\s+not\\s+use|template|^\\W*$");

    /** The label with site words and stray punctuation taken out. */
    public static String clean(String label) {
        if (label == null) {
            return "";
        }
        String out = SITE_WORDS.matcher(label).replaceAll(" ");
        out = out.replaceFirst("(?i)^\\s*at\\s+", "");
        return out.replaceAll("\\s+", " ")
                .replaceAll("^[\\s\\-:|,.]+|[\\s\\-:|,.]+$", "")
                .strip();
    }

    /**
     * Whether a cleaned label names an employer. The tenant code itself
     * ("Ebdt", "Fa Ewjt Saasfaprod1") is what discovery falls back to when a site
     * has no name, so it says nothing either.
     */
    static boolean isName(String cleaned, String token) {
        if (cleaned.length() < 2 || NOT_A_NAME.matcher(cleaned).find()) {
            return false;
        }
        String compact = cleaned.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String pod = host(token).split("\\.")[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return !compact.equals(tenant(token).replaceAll("[^a-z0-9]", "")) && !compact.equals(pod);
    }

    /** What to change on the Oracle boards: new labels, and copies to retire. */
    public record Plan(Map<BoardToken, String> relabel, Map<BoardToken, String> retire) {
    }

    /**
     * Labels every Oracle board by its employer and picks out the test and
     * development copies of sites that are also fetched live.
     *
     * <p>A site keeps its own name when it has one ("Cummins RePower" stays,
     * cleaned to drop nothing); a site without one takes the tenant's most common
     * cleaned name, shortest first on a tie. A tenant with no named site at all
     * keeps what it has: there is nothing better to put there.
     */
    public static Plan plan(Collection<BoardToken> boards) {
        return plan(boards, Map.of());
    }

    /**
     * As above, with the labels written by hand in the seed list. A seeded board
     * keeps its label, and every other site of its tenant takes the shortest
     * seeded name: JPMorgan's sites call themselves "Chase" more often than
     * anything else, but the seed says "JPMorgan Chase", and one employer under
     * two names would miss the already-applied flag.
     */
    public static Plan plan(Collection<BoardToken> boards, Map<String, String> seeded) {
        List<BoardToken> oracle = boards.stream()
                .filter(b -> b.getSource() == Source.ORACLE_HCM)
                .toList();
        Map<String, List<BoardToken>> byTenant = oracle.stream()
                .collect(Collectors.groupingBy(b -> tenant(b.getToken()), LinkedHashMap::new,
                        Collectors.toList()));

        Map<BoardToken, String> relabel = new LinkedHashMap<>();
        Map<BoardToken, String> retire = new LinkedHashMap<>();
        for (List<BoardToken> sites : byTenant.values()) {
            Map<String, Long> names = sites.stream()
                    .map(b -> clean(b.getLabel()))
                    .filter(n -> isName(n, sites.get(0).getToken()))
                    .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new,
                            Collectors.counting()));
            String seededName = sites.stream()
                    .map(b -> seeded.get(b.getToken()))
                    .filter(Objects::nonNull)
                    .min(Comparator.comparingInt(String::length))
                    .orElse(null);
            String tenantName = seededName != null ? seededName : names.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                            .thenComparing(e -> e.getKey().length()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);

            for (BoardToken board : sites) {
                if (seeded.containsKey(board.getToken())) {
                    if (!seeded.get(board.getToken()).equals(board.getLabel())) {
                        relabel.put(board, seeded.get(board.getToken()));
                    }
                    continue;
                }
                String cleaned = clean(board.getLabel());
                String label = seededName == null && isName(cleaned, board.getToken()) ? cleaned : tenantName;
                if (label != null && !label.equals(board.getLabel())) {
                    relabel.put(board, label);
                }
            }

            // A copy is retired only when the live site is fetched too; a copy that
            // is the only way in to a tenant stays.
            List<String> live = sites.stream()
                    .filter(b -> b.isActive() && !isNonProduction(b.getToken()))
                    .map(b -> siteKey(b.getToken()))
                    .toList();
            for (BoardToken board : sites) {
                if (board.isActive() && isNonProduction(board.getToken())
                        && live.contains(siteKey(board.getToken()))) {
                    retire.put(board, "Oracle " + (board.getToken().contains("-dev") ? "development" : "test")
                            + " copy of a site fetched live");
                }
            }
        }
        return new Plan(relabel, retire);
    }
}
