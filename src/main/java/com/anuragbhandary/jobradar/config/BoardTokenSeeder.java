package com.anuragbhandary.jobradar.config;

import com.anuragbhandary.jobradar.fetch.HackerNewsFetcher;
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
    private static final Map<String, String> RECRUITEE = new LinkedHashMap<>();

    /**
     * Amazon has one job site, not one board per company, so the "token" is a
     * country code. All four are swept separately because Dublin runs a graduate
     * requisition line entirely independent of Berlin's - a distinction that
     * cost four days of searching to notice.
     */
    private static final Map<String, String> AMAZON = new LinkedHashMap<>();

    /**
     * Workday boards, keyed {@code tenant/wdN/site}, with an optional fourth
     * part that becomes the site search.
     *
     * <p>Three parts because a Workday board is a tenant, the datacentre it lives
     * in and a site, and none of the three is derivable from the company name.
     * Every one was confirmed against the live API by hand; guessing the
     * triple has a very low hit rate, which is why so few are seeded and why the
     * list is worth growing deliberately rather than by script.
     */
    private static final Map<String, String> WORKDAY = new LinkedHashMap<>();

    /**
     * Eightfold sites, keyed {@code name/host/domain}. The name is what the
     * big-tech list matches; host and domain were read off each sitemap on
     * 2026-10-01 (Microsoft 2,361 jobs, Qualcomm 2,027, Netflix 469).
     */
    private static final Map<String, String> EIGHTFOLD = new LinkedHashMap<>();

    /** Google's search, one country per token, as Amazon's. */
    private static final Map<String, String> GOOGLE = new LinkedHashMap<>();

    /**
     * Oracle recruiting cloud sites, keyed {@code name/host/site/locationId}; the
     * location id is the country in that site's own geography.
     */
    private static final Map<String, String> ORACLE_HCM = new LinkedHashMap<>();

    /**
     * Careers sites read through their sitemap, keyed {@code name/host/path}. Each
     * was checked on 2026-10-01 for job URLs that carry the place, and for job data
     * on the page (Intuit JSON-LD; EY full microdata; Standard Chartered and Wipro
     * title and description only).
     */
    private static final Map<String, String> SITEMAP = new LinkedHashMap<>();

    /** One board: the current month's "Ask HN: Who is hiring?" thread. */
    private static final Map<String, String> HACKER_NEWS =
            Map.of(HackerNewsFetcher.BOARD, "HN Who is hiring");

    /** Aggregators with public feeds: one board each. */
    private static final Map<String, String> JOBICY =
            Map.of(com.anuragbhandary.jobradar.fetch.JobicyFetcher.BOARD, "Jobicy");
    private static final Map<String, String> WE_WORK_REMOTELY =
            Map.of(com.anuragbhandary.jobradar.fetch.WeWorkRemotelyFetcher.BOARD, "We Work Remotely");
    private static final Map<String, String> ARBEITNOW =
            Map.of(com.anuragbhandary.jobradar.fetch.ArbeitnowFetcher.BOARD, "Arbeitnow");

    static {
        WORKDAY.put("philips/wd3/jobs-and-careers", "Philips");
        WORKDAY.put("nxp/wd3/careers", "NXP Semiconductors");
        // India's GCCs (2026-09-22). US companies running one global site each,
        // read through Workday's own search so the crawl limit is spent on the
        // India desks. All seven answered the public API that day; Walmart's
        // WalmartExternal site did not and is left out until its real site is known.
        WORKDAY.put("mastercard/wd1/CorporateCareers/india", "Mastercard");
        WORKDAY.put("target/wd5/targetcareers/india", "Target");
        WORKDAY.put("wf/wd1/WellsFargoJobs/india", "Wells Fargo");
        WORKDAY.put("adobe/wd5/external_experienced/india", "Adobe");
        WORKDAY.put("paypal/wd1/jobs/india", "PayPal");
        WORKDAY.put("nvidia/wd5/NVIDIAExternalCareerSite/india", "NVIDIA");
        WORKDAY.put("intel/wd1/External/india", "Intel");
        // 2026-09-26: tech centres in India with junior-to-mid software roles.
        WORKDAY.put("salesforce/wd12/External_Career_Site/india", "Salesforce");
        WORKDAY.put("hp/wd5/ExternalCareerSite/india", "HP");
        WORKDAY.put("workday/wd5/Workday/india", "Workday");
        WORKDAY.put("motorolasolutions/wd5/Careers/india", "Motorola Solutions");
        WORKDAY.put("citi/wd5/2/india", "Citi");
        // 2026-09-28: India had supplied 12 new postings in two days against
        // Arbeitnow's 850. Each of these answered the public API that day with
        // India desks in its "india" search; the first five are mostly Mumbai.
        WORKDAY.put("morningstar/wd5/Americas/india", "Morningstar");
        WORKDAY.put("blackrock/wd1/BlackRock_Professional/india", "BlackRock");
        WORKDAY.put("ms/wd5/External/india", "Morgan Stanley");
        WORKDAY.put("db/wd3/DBWebSite/india", "Deutsche Bank");
        WORKDAY.put("barclays/wd3/External_Career_Site_Barclays/india", "Barclays");
        WORKDAY.put("statestreet/wd1/Global/india", "State Street");
        WORKDAY.put("autodesk/wd1/Ext/india", "Autodesk");
        WORKDAY.put("cisco/wd5/Cisco_Careers/india", "Cisco");
        WORKDAY.put("lseg/wd3/Careers/india", "LSEG");
        WORKDAY.put("equifax/wd5/External/india", "Equifax");
        WORKDAY.put("crowdstrike/wd5/crowdstrikecareers/india", "CrowdStrike");
        WORKDAY.put("amat/wd1/External/india", "Applied Materials");
        WORKDAY.put("kla/wd1/Search/india", "KLA");
        WORKDAY.put("fmr/wd1/FidelityCareers/india", "Fidelity");
        // 2026-10-01: big tech on Workday; the "India" search answered 4 and 26.
        // Walmart, Qualcomm, Intuit, SAP, Oracle, JPMC and Goldman did not
        // answer on a guessed tenant/site.
        WORKDAY.put("broadcom/wd1/External_Career/india", "Broadcom");
        WORKDAY.put("expedia/wd108/search/india", "Expedia");

        // 2026-09-28: probed and counted; each had India engineering roles that day
        // (Okta 73, Zscaler 41, PubMatic 29, Commvault 9, Twilio 8, ChargePoint 6,
        // Sigmoid 5).
        // 2026-09-28: companies with India engineering roles on this platform,
        // found by searching the platform itself (brand names rarely match the
        // board token) and counted that day.
        GREENHOUSE.put("blenheimchalcotindia", "Blenheim Chalcot India");
        GREENHOUSE.put("gleanwork", "Glean");
        GREENHOUSE.put("instawork", "Instawork");
        GREENHOUSE.put("dialpad", "Dialpad");
        GREENHOUSE.put("cloudflare", "Cloudflare");
        GREENHOUSE.put("okta", "Okta");
        GREENHOUSE.put("zscaler", "Zscaler");
        GREENHOUSE.put("pubmatic", "PubMatic");
        GREENHOUSE.put("commvault", "Commvault");
        GREENHOUSE.put("twilio", "Twilio");
        GREENHOUSE.put("chargepoint", "ChargePoint");
        GREENHOUSE.put("sigmoid", "Sigmoid");
        GREENHOUSE.put("stripe", "Stripe");
        GREENHOUSE.put("intercom", "Intercom");
        GREENHOUSE.put("celonis", "Celonis");
        GREENHOUSE.put("n26", "N26");
        GREENHOUSE.put("razorpaysoftwareprivatelimited", "Razorpay");
        GREENHOUSE.put("groww", "Groww");
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


        // 2026-09-28: companies with India engineering roles on this platform,
        // found by searching the platform itself (brand names rarely match the
        // board token) and counted that day.
        ASHBY.put("signoz", "SigNoz");
        ASHBY.put("granica", "Granica");
        ASHBY.put("aiprise", "AiPrise");
        ASHBY.put("broccoli", "Broccoli AI");
        ASHBY.put("bidgely-inc", "Bidgely");
        ASHBY.put("certifyos", "CertifyOS");
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
        // Langfuse's own board emptied when ClickHouse bought it (2026-09-29); its
        // roles are listed on ClickHouse's board with a "Langfuse -" prefix.
        ASHBY.put("clickhouse", "ClickHouse");
        ASHBY.put("enpal", "Enpal");
        ASHBY.put("qonto", "Qonto");
        ASHBY.put("atlan", "Atlan");
        ASHBY.put("mollie", "Mollie");
        ASHBY.put("wayflyer", "Wayflyer");
        ASHBY.put("miro", "Miro");

        // 2026-09-28: companies with India engineering roles on this platform,
        // found by searching the platform itself (brand names rarely match the
        // board token) and counted that day.
        LEVER.put("nium", "Nium");
        LEVER.put("acceldata", "Acceldata");
        LEVER.put("saviynt", "Saviynt");
        LEVER.put("safe", "Safe Security");
        LEVER.put("neuron7", "Neuron7");
        LEVER.put("zimperium", "Zimperium");
        LEVER.put("kobie", "Kobie");
        LEVER.put("stable-money1", "Stable Money");
        LEVER.put("portcast", "Portcast");
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
        // 2026-09-28: 69 postings in India that day, most in Hyderabad.
        SMARTRECRUITERS.put("shipsy", "Shipsy");
        SMARTRECRUITERS.put("servicenow", "ServiceNow");
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

        // Recruitee is heavily Dutch, which is why the seed list is. Every one of
        // these was verified as returning offers rather than {"error":"Not Found"} -
        // the platform 404s an unknown subdomain, so a seeded token that is wrong
        // shows up as a failing board rather than as a quiet one.
        RECRUITEE.put("channable", "Channable");
        RECRUITEE.put("vandebron", "Vandebron");
        RECRUITEE.put("nmbrs", "Nmbrs");

        // Added 2026-09-19 for the lanes the list above barely reached: the Gulf,
        // remote roles open worldwide, and Indian employers with Mumbai offices.
        // Each was probed and its locations checked before it went in.
        GREENHOUSE.put("careem", "Careem");
        GREENHOUSE.put("tamara", "Tamara");
        ASHBY.put("ziina", "Ziina");
        RECRUITEE.put("unifonic", "Unifonic");
        GREENHOUSE.put("canonical", "Canonical");
        GREENHOUSE.put("wikimedia", "Wikimedia Foundation");
        GREENHOUSE.put("netlify", "Netlify");
        GREENHOUSE.put("druva", "Druva");
        LEVER.put("epifi", "Epifi (Fi)");
        LEVER.put("fampay", "FamPay");
        LEVER.put("paytm", "Paytm");
        SMARTRECRUITERS.put("ixigo", "ixigo");
        // India, added the same day after a 160-company probe. Most Indian
        // companies, and nearly every Mumbai one tried, are on none of the
        // platforms this tool reads. Unacademy and InterviewBit answered on
        // SmartRecruiters too, but with placeholder postings ("lorem ipsum"), so
        // they are not boards.
        GREENHOUSE.put("glance", "Glance");
        GREENHOUSE.put("highradius", "HighRadius");
        GREENHOUSE.put("hackerrank", "HackerRank");
        SMARTRECRUITERS.put("Lendingkart", "Lendingkart");

        AMAZON.put("IND", "Amazon India");
        AMAZON.put("DEU", "Amazon Germany");
        AMAZON.put("IRL", "Amazon Ireland");
        AMAZON.put("NLD", "Amazon Netherlands");
        // The UK and the UAE joined the strategy on 2026-10-01: 38 software
        // roles in the UK that day, none in the UAE.
        AMAZON.put("GBR", "Amazon UK");
        AMAZON.put("ARE", "Amazon UAE");

        EIGHTFOLD.put("microsoft/apply.careers.microsoft.com/microsoft.com", "Microsoft");
        EIGHTFOLD.put("netflix/explore.jobs.netflix.net/netflix.com", "Netflix");
        EIGHTFOLD.put("qualcomm/careers.qualcomm.com/qualcomm.com", "Qualcomm");
        EIGHTFOLD.put("hsbc/portal.careers.hsbc.com/hsbc.com", "HSBC");

        // India pages in each sitemap on 2026-10-01: EY 1,747, Standard
        // Chartered 263, Intuit 27; Wipro lists 5,425 jobs, most in India.
        SITEMAP.put("intuit/jobs.intuit.com/sitemap.xml", "Intuit");
        SITEMAP.put("ey/careers.ey.com/sitemap.xml", "EY");
        SITEMAP.put("stanchart/jobs.standardchartered.com/sitemap.xml", "Standard Chartered");
        SITEMAP.put("wipro/careers.wipro.com/sitemap.xml", "Wipro");

        GOOGLE.put("India", "Google India");
        GOOGLE.put("Ireland", "Google Ireland");
        GOOGLE.put("Germany", "Google Germany");
        GOOGLE.put("United Kingdom", "Google UK");
        GOOGLE.put("United Arab Emirates", "Google UAE");

        // India ids read from each site's location facet on 2026-10-01: JPMorgan
        // 323 India requisitions, Oracle 13.
        ORACLE_HCM.put("jpmc/jpmc.fa.oraclecloud.com/CX_1001/300000000289360", "JPMorgan Chase");
        ORACLE_HCM.put("oracle/eeho.fa.us2.oraclecloud.com/CX_45001/300000000106947", "Oracle");
        // 681 UK requisitions on 2026-10-01.
        ORACLE_HCM.put("jpmc/jpmc.fa.oraclecloud.com/CX_1001/300000000289276", "JPMorgan Chase UK");
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

    /** Oracle boards named by hand, token to label: these names beat any site's own. */
    public static Map<String, String> oracleLabels() {
        return java.util.Collections.unmodifiableMap(ORACLE_HCM);
    }

    /** Adds any missing seed tokens. Safe to call on every startup. */
    @Transactional
    public void seed() {
        int added = seedSource(Source.GREENHOUSE, GREENHOUSE)
                + seedSource(Source.ASHBY, ASHBY)
                + seedSource(Source.LEVER, LEVER)
                + seedSource(Source.SMARTRECRUITERS, SMARTRECRUITERS)
                + seedSource(Source.AMAZON, AMAZON)
                + seedSource(Source.WORKDAY, WORKDAY)
                + seedSource(Source.RECRUITEE, RECRUITEE)
                + seedSource(Source.HACKER_NEWS, HACKER_NEWS)
                + seedSource(Source.JOBICY, JOBICY)
                + seedSource(Source.WE_WORK_REMOTELY, WE_WORK_REMOTELY)
                + seedSource(Source.ARBEITNOW, ARBEITNOW)
                + seedSource(Source.EIGHTFOLD, EIGHTFOLD)
                + seedSource(Source.GOOGLE, GOOGLE)
                + seedSource(Source.ORACLE_HCM, ORACLE_HCM)
                + seedSource(Source.SITEMAP, SITEMAP);
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
