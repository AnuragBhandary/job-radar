package com.anuragbhandary.jobradar.config;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import java.util.LinkedHashMap;
import java.util.Map;
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

    static {
        GREENHOUSE.put("stripe", "Stripe");
        GREENHOUSE.put("intercom", "Intercom");
        GREENHOUSE.put("celonis", "Celonis");
        GREENHOUSE.put("n26", "N26");
        GREENHOUSE.put("razorpaysoftwareprivatelimited", "Razorpay");
        GREENHOUSE.put("groww", "Groww");
        GREENHOUSE.put("postman", "Postman");
        GREENHOUSE.put("traderepublic", "Trade Republic");
        GREENHOUSE.put("getyourguide", "GetYourGuide");
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
    }

    private final BoardTokenRepository boards;

    public BoardTokenSeeder(BoardTokenRepository boards) {
        this.boards = boards;
    }

    /** Adds any missing seed tokens. Safe to call on every startup. */
    @Transactional
    public void seed() {
        int added = 0;
        for (Map.Entry<String, String> entry : GREENHOUSE.entrySet()) {
            if (boards.findBySourceAndToken(Source.GREENHOUSE, entry.getKey()).isEmpty()) {
                boards.save(new BoardToken(Source.GREENHOUSE, entry.getKey(), entry.getValue()));
                added++;
            }
        }
        if (added > 0) {
            log.info("Seeded {} new board tokens ({} total)", added, boards.count());
        }
    }
}
