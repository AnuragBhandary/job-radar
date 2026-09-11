package com.anuragbhandary.jobradar.evidence;

import static com.anuragbhandary.jobradar.evidence.EvidenceFixtures.errors;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the validator refuses, one rule at a time.
 *
 * <p>An item with an error is left out of the bank; a variant with an error is left
 * out of its item. Each test checks both the message and that the broken thing is
 * really gone.
 */
class EvidenceValidatorTest {

    private static final String HEAD = """
            version: 1
            sources:
              - id: acme
                kind: employment
                name: Acme Streaming
                stack: [Python, Kafka]
            items:
            """;

    private static EvidenceBank bank(String items) {
        return EvidenceFixtures.bank(HEAD + items);
    }

    /** A valid item with metrics, and one variant to vary. */
    private static EvidenceBank withVariant(String text) {
        return bank("""
                  - id: replay
                    source: acme
                    claim: "Built a Kafka-based replay service handling 12 streams and approximately 3,000 messages."
                    technologies: [Kafka, Python]
                    concepts: [replay]
                    metrics: ["12 streams", "approximately 3,000 messages"]
                    variants:
                      - id: v
                        text: "%s"
                        approved: true
                """.formatted(text));
    }

    private static boolean variantKept(EvidenceBank bank) {
        return bank.find("replay").orElseThrow().variants().stream().anyMatch(v -> v.id().equals("v"));
    }

    // ---- Items -------------------------------------------------------------

    @Test
    @DisplayName("a well-formed item and variant pass untouched")
    void validItem() {
        EvidenceBank bank = withVariant(
                "Built a replay service on Kafka handling 12 streams and approximately 3,000 messages.");

        assertThat(bank.problems()).isEmpty();
        assertThat(variantKept(bank)).isTrue();
    }

    @Test
    @DisplayName("a technology neither in the claim nor in the source's stack is refused")
    void ungroundedTechnology() {
        EvidenceBank bank = bank("""
                  - id: consumer
                    source: acme
                    claim: "Built a Kafka consumer."
                    technologies: [Kafka, Kubernetes, Python]
                """);

        assertThat(errors(bank)).singleElement().asString().contains("'Kubernetes'");
        assertThat(bank.find("consumer")).isEmpty();
    }

    @Test
    @DisplayName("a metric the claim does not state, and a figure no metric declares, are refused")
    void metricsAreGrounded() {
        EvidenceBank wrong = bank("""
                  - id: m
                    source: acme
                    claim: "Replayed 12 streams on Kafka."
                    technologies: [Kafka]
                    metrics: ["20 streams"]
                """);
        EvidenceBank undeclared = bank("""
                  - id: m
                    source: acme
                    claim: "Replayed 12 streams on Kafka."
                    technologies: [Kafka]
                """);

        assertThat(errors(wrong)).anySatisfy(e -> assertThat(e).contains("'20 streams' is not in the claim"));
        assertThat(errors(undeclared)).singleElement().asString().contains("states '12'");
        assertThat(undeclared.find("m")).isEmpty();
    }

    @Test
    @DisplayName("a qualifier must be in the claim, and shared work must carry one")
    void qualifiersAndAttribution() {
        EvidenceBank missing = bank("""
                  - id: q
                    source: acme
                    claim: "Built a Python service."
                    technologies: [Python]
                    qualifiers: [the ingestion side of]
                """);
        EvidenceBank shared = bank("""
                  - id: s
                    source: acme
                    claim: "Built a Python service."
                    technologies: [Python]
                    attribution: shared
                """);

        assertThat(errors(missing)).singleElement().asString().contains("qualifier");
        assertThat(errors(shared)).singleElement().asString().contains("which part was his");
    }

    @Test
    @DisplayName("a product name is refused as a concept; an idea the claim does not show is refused")
    void concepts() {
        EvidenceBank product = bank("""
                  - id: c
                    source: acme
                    claim: "Built a Kafka consumer."
                    technologies: [Kafka]
                    concepts: [Kafka]
                """);
        EvidenceBank ungrounded = bank("""
                  - id: c
                    source: acme
                    claim: "Built a Kafka consumer."
                    technologies: [Kafka]
                    concepts: [machine learning]
                """);
        EvidenceBank implemented = bank("""
                  - id: c
                    source: acme
                    claim: "Built a Kafka consumer."
                    technologies: [Kafka]
                    concepts: [event-driven, streaming]
                """);

        assertThat(errors(product)).singleElement().asString().contains("is a technology");
        assertThat(errors(ungrounded)).singleElement().asString().contains("'machine learning'");
        assertThat(implemented.problems()).as("ideas Kafka implements").isEmpty();
    }

    @Test
    @DisplayName("context-only names must be in the claim and cannot also be listed as used")
    void contextOnly() {
        EvidenceBank bank = bank("""
                  - id: r
                    source: acme
                    claim: "Streamed updates from a Python service to React clients."
                    technologies: [Python, React]
                    context-only: [React, Vue]
                """);

        assertThat(errors(bank)).anySatisfy(e -> assertThat(e).contains("'Vue' is not named"))
                .anySatisfy(e -> assertThat(e).contains("both as used and as context only"));
    }

    @Test
    @DisplayName("a duplicate id, an unknown source and a malformed id are refused; the first copy stays")
    void identity() {
        EvidenceBank bank = bank("""
                  - id: same
                    source: acme
                    claim: "Built a Kafka consumer."
                    technologies: [Kafka]
                  - id: same
                    source: acme
                    claim: "Built a Kafka producer."
                    technologies: [Kafka]
                  - id: orphan
                    source: nowhere
                    claim: "Built a thing."
                  - id: "has a space"
                    source: acme
                    claim: "Built another thing."
                """);

        assertThat(bank.items()).extracting(EvidenceItem::claim).containsExactly("Built a Kafka consumer.");
        assertThat(errors(bank)).anySatisfy(e -> assertThat(e).contains("used twice"))
                .anySatisfy(e -> assertThat(e).contains("'nowhere'"))
                .anySatisfy(e -> assertThat(e).contains("needs an id"));
    }

    // ---- Variants: the same rules model rewrites were held to ---------------

    @Test
    @DisplayName("a variant that changes a figure is refused: dropped metric and invented number")
    void inventedNumber() {
        EvidenceBank bank = withVariant(
                "Built a Kafka-based replay service handling 14 streams and approximately 3,000 messages.");

        assertThat(errors(bank)).anySatisfy(e -> assertThat(e).contains("metric '12 streams'"))
                .anySatisfy(e -> assertThat(e).contains("INVENTED_NUMBER"));
        assertThat(variantKept(bank)).isFalse();
        assertThat(bank.find("replay")).as("the item itself survives").isPresent();
    }

    @Test
    @DisplayName("a variant that drops the hedge on a figure is refused")
    void hedgeDropped() {
        EvidenceBank bank = withVariant(
                "Built a Kafka-based replay service handling 12 streams and 3,000 messages.");

        assertThat(errors(bank)).anySatisfy(e -> assertThat(e).contains("approximately 3,000 messages"))
                .anySatisfy(e -> assertThat(e).contains("QUALIFIER_LOST"));
        assertThat(variantKept(bank)).isFalse();
    }

    @Test
    @DisplayName("a variant naming a technology the item does not list is refused")
    void unsupportedTechnology() {
        EvidenceBank bank = withVariant(
                "Built a Kafka-based replay service on Kubernetes handling 12 streams and approximately 3,000 messages.");

        assertThat(errors(bank)).anySatisfy(e -> assertThat(e).contains("UNSUPPORTED_TECHNOLOGY"));
    }

    @Test
    @DisplayName("a technology tacked onto the end is stuffing, even one the item lists")
    void stuffing() {
        EvidenceBank bank = withVariant(
                "Built a Kafka-based replay service handling 12 streams and approximately 3,000 messages using Python.");

        assertThat(errors(bank)).anySatisfy(e -> assertThat(e).contains("KEYWORD_STUFFING"));
    }

    @Test
    @DisplayName("a variant that inflates responsibility, or names a narrower product, is refused")
    void inflationAndProducts() {
        EvidenceBank led = withVariant(
                "Led a Kafka-based replay service handling 12 streams and approximately 3,000 messages.");
        EvidenceBank streams = withVariant(
                "Built a Kafka Streams replay service handling 12 streams and approximately 3,000 messages.");

        assertThat(errors(led)).anySatisfy(e -> assertThat(e).contains("INFLATED_RESPONSIBILITY"));
        assertThat(errors(streams)).anySatisfy(e -> assertThat(e).contains("UNSUPPORTED_PRODUCT"));
    }

    @Test
    @DisplayName("a variant that drops a qualifier is refused, and the claim stays")
    void qualifierDropped() {
        EvidenceBank bank = bank("""
                  - id: ingest
                    source: acme
                    claim: "Developed the ingestion side of a Python service."
                    technologies: [Python]
                    qualifiers: [the ingestion side of]
                    attribution: shared
                    variants:
                      - id: dropped
                        text: "Developed a Python ingestion service."
                        approved: true
                """);

        assertThat(errors(bank)).singleElement().asString().contains("drops the qualifier");
        assertThat(bank.find("ingest").orElseThrow().approvedTexts())
                .containsExactly("Developed the ingestion side of a Python service.");
    }

    @Test
    @DisplayName("'production-style' cannot become 'production'")
    void productionStyle() {
        EvidenceBank bank = bank("""
                  - id: sched
                    source: acme
                    claim: "Built a production-style scheduler with Kafka."
                    technologies: [Kafka]
                    variants:
                      - id: v
                        text: "Built a production scheduler with Kafka."
                        approved: true
                """);

        assertThat(errors(bank)).anySatisfy(e -> assertThat(e).contains("UNSUPPORTED_CLAIM"))
                .anySatisfy(e -> assertThat(e).contains("QUALIFIER_LOST"));
    }

    @Test
    @DisplayName("a variant may emphasise only what the item lists, and may not be called 'claim'")
    void emphasisAndIds() {
        EvidenceBank emphasis = bank("""
                  - id: r
                    source: acme
                    claim: "Built a Kafka consumer."
                    technologies: [Kafka]
                    variants:
                      - id: v
                        text: "Built a consumer on Kafka."
                        emphasis: [kubernetes]
                        approved: true
                      - id: claim
                        text: "Wrote a Kafka consumer."
                        approved: true
                """);

        assertThat(errors(emphasis)).anySatisfy(e -> assertThat(e).contains("emphasises 'kubernetes'"))
                .anySatisfy(e -> assertThat(e).contains("other than 'claim'"));
        assertThat(emphasis.find("r").orElseThrow().variants()).isEmpty();
    }
}
