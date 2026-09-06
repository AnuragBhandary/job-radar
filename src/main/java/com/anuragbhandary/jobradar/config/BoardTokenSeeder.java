package com.anuragbhandary.jobradar.config;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts the known-good board tokens on first run.
 *
 * <p>Every token here was verified by hand against a live board. Discovering
 * them was most of the work of the manual search this tool replaces, so they are
 * checked in rather than rediscovered.
 *
 * <p>Seeding is additive and idempotent: a token already present is left alone,
 * including its active flag, so deactivating a dead board by hand is not undone
 * by the next startup.
 */
@Component
public class BoardTokenSeeder {

    private static final Logger log = LoggerFactory.getLogger(BoardTokenSeeder.class);

    /** Greenhouse token to company label. Ordered so the seeded table reads sensibly. */
    private static final Map<String, String> GREENHOUSE = new LinkedHashMap<>();

    private static final Map<String, String> ASHBY = new LinkedHashMap<>();

    private static final Map<String, String> LEVER = new LinkedHashMap<>();

    private static final Map<String, String> SMARTRECRUITERS = new LinkedHashMap<>();

    /**
     * Amazon has one job site, not one board per company, so the "token" is a
     * country code. All four are swept separately because Dublin runs a graduate
     * requisition line entirely independent of Berlin's - a distinction that
     * cost four days of searching to notice.
     */
    private static final Map<String, String> AMAZON = new LinkedHashMap<>();

    /**
     * Workday boards, keyed {@code tenant/wdN/site}.
     *
     * <p>Three parts because a Workday board is a tenant, the datacentre it lives
     * in and a site, and none of the three is derivable from the company name.
     * Both of these were confirmed against the live API by hand; guessing the
     * triple has a very low hit rate, which is why so few are seeded and why the
     * list is worth growing deliberately rather than by script.
     */
    private static final Map<String, String> WORKDAY = new LinkedHashMap<>();

    static {
        WORKDAY.put("philips/wd3/jobs-and-careers", "Philips");
        WORKDAY.put("nxp/wd3/careers", "NXP Semiconductors");

        GREENHOUSE.put("stripe", "Stripe");
        GREENHOUSE.put("intercom", "Intercom");
        GREENHOUSE.put("celonis", "Celonis");
        GREENHOUSE.put("n26", "N26");
        GREENHOUSE.put("razorpaysoftwareprivatelimited", "Razorpay");
        GREENHOUSE.put("groww", "Groww");
        GREENHOUSE.put("postman", "Postman");
        GREENHOUSE.put("traderepublic", "Trade Republic");
        GREENHOUSE.put("getyourguide", "GetYourGuide");
        GREENHOUSE.put("contentful", "Contentful");
        GREENHOUSE.put("hellofresh", "HelloFresh");
        GREENHOUSE.put("databricks", "Databricks");
        GREENHOUSE.put("mongodb", "MongoDB");
        GREENHOUSE.put("gitlab", "GitLab");
        GREENHOUSE.put("arcesiumllc", "Arcesium");
        GREENHOUSE.put("grafanalabs", "Grafana Labs");
        GREENHOUSE.put("slice", "Slice");
        GREENHOUSE.put("scaleai", "Scale AI");
        GREENHOUSE.put("observeai", "Observe.AI");
        GREENHOUSE.put("elastic", "Elastic");
        GREENHOUSE.put("datadog", "Datadog");
        GREENHOUSE.put("newrelic", "New Relic");
        GREENHOUSE.put("fivetran", "Fivetran");
        GREENHOUSE.put("starburst", "Starburst");
        GREENHOUSE.put("imply", "Imply");
        GREENHOUSE.put("tigergraph", "TigerGraph");
        GREENHOUSE.put("neo4j", "Neo4j");
        GREENHOUSE.put("yugabyte", "Yugabyte");
        GREENHOUSE.put("cockroachlabs", "Cockroach Labs");
        GREENHOUSE.put("parloa", "Parloa");
        GREENHOUSE.put("solarisbank", "Solaris");
        GREENHOUSE.put("raisin", "Raisin");
        GREENHOUSE.put("flix", "Flix");
        GREENHOUSE.put("helsing", "Helsing");
        GREENHOUSE.put("doctolib", "Doctolib");
        GREENHOUSE.put("dataiku", "Dataiku");
        GREENHOUSE.put("algolia", "Algolia");
        GREENHOUSE.put("wise", "Wise");
        GREENHOUSE.put("adyen", "Adyen");
        GREENHOUSE.put("flowtraders", "Flow Traders");
        GREENHOUSE.put("devrev", "DevRev");
        GREENHOUSE.put("netradyne", "Netradyne");
        GREENHOUSE.put("squarespace", "Squarespace");
        GREENHOUSE.put("tines", "Tines");
        GREENHOUSE.put("udemy", "Udemy");
        GREENHOUSE.put("flipdish", "Flipdish");
        GREENHOUSE.put("inmobi", "InMobi");


        ASHBY.put("confluent", "Confluent");
        ASHBY.put("notion", "Notion");
        ASHBY.put("openai", "OpenAI");
        ASHBY.put("sarvam", "Sarvam AI");
        ASHBY.put("tekion", "Tekion");
        ASHBY.put("ramp", "Ramp");
        ASHBY.put("plaid", "Plaid");
        ASHBY.put("supabase", "Supabase");
        ASHBY.put("linear", "Linear");
        ASHBY.put("deepl", "DeepL");
        ASHBY.put("camunda", "Camunda");
        ASHBY.put("forto", "Forto");
        ASHBY.put("choco", "Choco");
        ASHBY.put("langfuse", "Langfuse");
        ASHBY.put("enpal", "Enpal");
        ASHBY.put("qonto", "Qonto");
        ASHBY.put("atlan", "Atlan");
        ASHBY.put("mollie", "Mollie");
        ASHBY.put("wayflyer", "Wayflyer");
        ASHBY.put("miro", "Miro");

        LEVER.put("zeta", "Zeta");
        LEVER.put("meesho", "Meesho");
        LEVER.put("cred", "CRED");
        LEVER.put("mindtickle", "Mindtickle");
        LEVER.put("sonarsource", "SonarSource");
        LEVER.put("contentsquare", "Contentsquare");
        LEVER.put("aircall", "Aircall");
        LEVER.put("qonto", "Qonto");
        LEVER.put("porter", "Porter");

        SMARTRECRUITERS.put("PHONEPELIMITED", "PhonePe");
        SMARTRECRUITERS.put("DeliveryHero", "Delivery Hero");
        SMARTRECRUITERS.put("Personio", "Personio");
        SMARTRECRUITERS.put("Siemens", "Siemens");
        SMARTRECRUITERS.put("Bosch", "Bosch");
        SMARTRECRUITERS.put("Contentful", "Contentful");
        // Bosch's real SmartRecruiters identifier. The seeded "Bosch" returns
        // totalFound: 0, which on this platform is indistinguishable from a
        // company that exists and is not hiring - so it read as a healthy, quiet
        // board for as long as it was wrong. "BoschGroup" returns 4,812.
        SMARTRECRUITERS.put("BoschGroup", "Bosch");
        SMARTRECRUITERS.put("N26", "N26");
        SMARTRECRUITERS.put("TradeRepublic", "Trade Republic");
        SMARTRECRUITERS.put("GetYourGuide", "GetYourGuide");
        SMARTRECRUITERS.put("Zalando", "Zalando");
        SMARTRECRUITERS.put("Coolblue", "Coolblue");
        SMARTRECRUITERS.put("Freshworks", "Freshworks");
        SMARTRECRUITERS.put("Swiggy", "Swiggy");
        SMARTRECRUITERS.put("Picnic", "Picnic");

        AMAZON.put("IND", "Amazon India");
        AMAZON.put("DEU", "Amazon Germany");
        AMAZON.put("IRL", "Amazon Ireland");
        AMAZON.put("NLD", "Amazon Netherlands");
    }

    private final BoardTokenRepository boards;

    public BoardTokenSeeder(BoardTokenRepository boards) {
        this.boards = boards;
    }

    /**
     * Companies confirmed absent from all four applicant tracking systems.
     *
     * <p>Kept so that {@code probe} never re-tests them. This is the tier most
     * likely to hire at entry level in India, and its boards are simply not
     * reachable this way - each needs its real ATS identified by hand. Token
     * guessing has already failed twice.
     *
     * <p>Two traps recorded here rather than rediscovered, because a probe finds
     * a live board without checking whose it is:
     * <ul>
     *   <li>The Ashby token {@code navi} belongs to a San Francisco aviation
     *       startup, not to Navi the Indian fintech. Every posting is in SF.</li>
     *   <li>The Greenhouse token {@code bird} belongs to Bird the e-scooter
     *       company in New Jersey, not to Bird (formerly MessageBird), the
     *       Amsterdam CPaaS on the IND recognised-sponsor register.</li>
     * </ul>
     */
    public static final Set<String> KNOWN_ABSENT = Set.of(
            "hasura", "zepto", "navi", "juspay", "zerodha", "browserstack", "sprinklr",
            "rippling", "nutanix", "atlassian", "whatfix", "darwinbox", "chargebee",
            "clevertap", "harness", "leadsquared", "locus", "innovaccer", "uniphore",
            "icertis", "gupshup", "yellowai", "jupiter", "setu", "m2p", "perfios",
            "signzy", "yubi", "acko", "zetwerk", "delhivery", "medianet", "dream11",
            "games24x7", "ather", "physicswallah", "cars24", "lenskart", "urbancompany",
            "bizongo", "moglix", "glean", "booking", "hubspot", "optiver", "backbase",
            "bunq", "justeattakeaway", "imc");


    /**
     * Seeded tokens since confirmed dead, and why.
     *
     * <p>Deactivated rather than deleted: the reason is worth keeping next to the
     * board, and a token that quietly vanished from the seed list would be
     * re-added by the next person who thought the company was missing.
     *
     * <p>All eight were SmartRecruiters boards reporting zero postings, and all
     * eight were German or Dutch employers - the tier this search most depends on.
     * The platform answers HTTP 200 with {@code totalFound: 0} for any string at
     * all, so none of them ever looked broken. They looked quiet.
     */
    private static final Map<String, String> RETIRED = new LinkedHashMap<>();

    static {
        RETIRED.put("Bosch",
                "wrong identifier - the live board is BoschGroup, seeded separately");
        RETIRED.put("Contentful", "moved to Greenhouse; seeded there as 'contentful'");
        RETIRED.put("GetYourGuide", "already covered on Greenhouse as 'getyourguide'");
        RETIRED.put("N26", "already covered on Greenhouse as 'n26'");
        RETIRED.put("TradeRepublic", "already covered on Greenhouse as 'traderepublic'");
        RETIRED.put("Zalando",
                "not on Greenhouse, Ashby, Lever or SmartRecruiters - board unidentified");
        RETIRED.put("Siemens",
                "not on Greenhouse, Ashby, Lever or SmartRecruiters - board unidentified");
        RETIRED.put("Personio",
                "not on Greenhouse, Ashby, Lever or SmartRecruiters - runs its own product");
    }

    /** Adds any missing seed tokens. Safe to call on every startup. */
    @Transactional
    public void seed() {
        int added = seedSource(Source.GREENHOUSE, GREENHOUSE)
                + seedSource(Source.ASHBY, ASHBY)
                + seedSource(Source.LEVER, LEVER)
                + seedSource(Source.SMARTRECRUITERS, SMARTRECRUITERS)
                + seedSource(Source.AMAZON, AMAZON)
                + seedSource(Source.WORKDAY, WORKDAY);
        if (added > 0) {
            log.info("Seeded {} new board tokens ({} total)", added, boards.count());
        }
        retire();
    }

    /**
     * Switches off the boards known to be dead, recording why on the row.
     *
     * <p>Idempotent, and it deliberately does not undo a manual reactivation of
     * anything outside {@link #RETIRED} - only these eight are touched, and only
     * while they are still active.
     */
    private void retire() {
        int retired = 0;
        for (Map.Entry<String, String> entry : RETIRED.entrySet()) {
            var board = boards.findBySourceAndToken(Source.SMARTRECRUITERS, entry.getKey());
            if (board.isPresent() && board.get().isActive()) {
                board.get().setActive(false);
                board.get().setLastError("retired: " + entry.getValue());
                boards.save(board.get());
                retired++;
            }
        }
        if (retired > 0) {
            log.info("Retired {} board token(s) confirmed dead", retired);
        }
    }

    private int seedSource(Source source, Map<String, String> tokens) {
        int added = 0;
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            if (boards.findBySourceAndToken(source, entry.getKey()).isEmpty()) {
                boards.save(new BoardToken(source, entry.getKey(), entry.getValue()));
                added++;
            }
        }
        return added;
    }
}
