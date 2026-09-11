package com.anuragbhandary.jobradar.bench;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The default benchmark set: 24 postings from the local database, chosen for
 * spread rather than volume.
 *
 * <p>Ids, not text. The postings live in the local SQLite file and the benchmark
 * reads them from there, so this list is only meaningful against that database;
 * pass {@code --posting-ids} to use others.
 *
 * <p>The labels say why each one is here - the stack it tests, the country, and
 * whether it has clear required/preferred sections or none at all - so a result
 * on one posting can be read in context.
 */
public final class BenchmarkPostings {

    private BenchmarkPostings() {
    }

    public static final Map<Long, String> DEFAULT;

    static {
        Map<Long, String> set = new LinkedHashMap<>();
        // Java / Spring
        set.put(779L, "Java backend, India");
        set.put(5506L, "Java, Netherlands, has a preferred section");
        set.put(5507L, "Java payments, Netherlands");
        set.put(8755L, "Java/Kotlin + Kafka + Kubernetes, UK, no requirement headings");
        set.put(4469L, "Java auth + Kubernetes, US");
        set.put(5608L, "Java, location unknown, short");
        // Python / backend
        set.put(5530L, "Python knowledge infrastructure, Netherlands");
        set.put(9039L, "Python product, Netherlands, has a preferred section");
        set.put(8945L, "Backend ingestion + Kafka, UK");
        // Distributed systems
        set.put(4761L, "Clustering and distributed systems, Sweden");
        set.put(1030L, "Backend + Kafka/Kubernetes, Germany");
        // AI / backend
        set.put(2970L, "AI engineer, India, long");
        set.put(6196L, "Applied AI engineer, India");
        set.put(2976L, "AI backend in a Ruby shop, Canada");
        set.put(7872L, "Agentic software engineer, location unknown");
        // India
        set.put(2977L, "Backend geo team, India");
        set.put(5739L, "Associate database engineer, India");
        // Europe
        set.put(1027L, "Backend cards and wallets, Germany");
        set.put(1026L, "Backend, Spain");
        set.put(5611L, "PostgreSQL engineer, Netherlands");
        // Unfamiliar or adjacent stacks
        set.put(1076L, "SRE data platform, Germany (ops stack he does not have)");
        set.put(8947L, "ClickHouse operations, location unknown (unfamiliar)");
        // Messy formatting: inline "Preferred:" and run-on list text
        set.put(8620L, "AWS ElastiCache SDE, Ireland, inline Preferred:");
        set.put(8652L, "SDE new grad, Ireland, run-on qualification lists");
        DEFAULT = Collections.unmodifiableMap(set);
    }
}
