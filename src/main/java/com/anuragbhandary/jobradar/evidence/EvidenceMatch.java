package com.anuragbhandary.jobradar.evidence;

/**
 * One item found for one requirement, and why.
 *
 * @param requirement   the requirement as asked
 * @param matched       the technology or concept of the item that matched it
 * @param score         how strong the match is, for ranking only. The kind of match
 *                      decides first; within a kind, the item's strength, work over
 *                      projects, and a stated measured result.
 * @param reason        one line a person can check: "names Kafka", "shows deduplication"
 * @param supportsClaim whether this match lets the requirement itself be claimed.
 *                      False when the item only contains the name inside a narrower
 *                      term ("Kafka Streams"), when it is merely a related kind of
 *                      work, or when the ledger found it through a neighbouring
 *                      technology - Docker for Kubernetes. Such an item is worth
 *                      putting forward; the requirement's name is still not his.
 */
public record EvidenceMatch(
        EvidenceItem item,
        EvidenceSource source,
        Kind kind,
        String requirement,
        String matched,
        double score,
        String reason,
        boolean supportsClaim) {

    /** Strongest first. */
    public enum Kind {
        /** The item lists the technology asked for. */
        TECHNOLOGY(1.0),
        /** The item shows the idea asked for, in its own words. */
        CONCEPT(0.85),
        /** A technology of the item implements the idea asked for - Kafka, event-driven. */
        IMPLEMENTS(0.7),
        /** The requirement names one of the item's technologies inside a longer term. */
        CONTAINED(0.58),
        /**
         * Only a general engineering idea matches - "backend engineering". True of
         * nearly every item, so it ranks below anything specific.
         */
        GENERAL(0.48),
        /** Only the kind of role matches. */
        CATEGORY(0.38);

        private final double weight;

        Kind(double weight) {
            this.weight = weight;
        }

        public double weight() {
            return weight;
        }
    }

    public String itemId() {
        return item.id();
    }
}
