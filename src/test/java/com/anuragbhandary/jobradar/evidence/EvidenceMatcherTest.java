package com.anuragbhandary.jobradar.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.analysis.Requirement;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementCategory;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** "Give me the strongest evidence for requirement X" - and nothing when there is none. */
class EvidenceMatcherTest {

    private static final EvidenceBank BANK = EvidenceFixtures.bank();

    private static List<String> ids(String requirement) {
        return BANK.strongestFor(requirement).stream().map(EvidenceMatch::itemId).toList();
    }

    // ---- Technologies -------------------------------------------------------

    @Test
    @DisplayName("a technology matches the items that list it, aliases and filler folded")
    void technologyMatch() {
        assertThat(ids("Kafka")).containsExactly("acme-replay", "docs-async");
        assertThat(ids("Apache Kafka")).containsExactly("acme-replay", "docs-async");
        assertThat(ids("Postgres")).containsExactly("sched-persist");
        assertThat(BANK.strongestFor("Kafka")).allSatisfy(m -> {
            assertThat(m.kind()).isEqualTo(EvidenceMatch.Kind.TECHNOLOGY);
            assertThat(m.supportsClaim()).isTrue();
        });
    }

    @Test
    @DisplayName("a product is never matched through a concept: 'streaming' is not evidence of Kafka")
    void productsNeedTheProduct() {
        assertThat(ids("Kafka")).doesNotContain("acme-ingest");
        assertThat(ids("Kubernetes")).isEmpty();
    }

    @Test
    @DisplayName("a narrower name - Kafka Streams - finds Kafka, and does not license the claim")
    void containedTechnology() {
        List<EvidenceMatch> matches = BANK.strongestFor("Kafka Streams");

        assertThat(matches).extracting(EvidenceMatch::itemId).containsExactly("acme-replay", "docs-async");
        assertThat(matches).allSatisfy(m -> {
            assertThat(m.kind()).isEqualTo(EvidenceMatch.Kind.CONTAINED);
            assertThat(m.supportsClaim()).isFalse();
        });
    }

    @Test
    @DisplayName("a technology the claim only delivers to - React clients - is not evidence of React")
    void contextOnlyIsNotEvidence() {
        assertThat(ids("React")).isEmpty();
    }

    // ---- Concepts -----------------------------------------------------------

    @Test
    @DisplayName("an idea matches the item whose own words show it")
    void conceptMatch() {
        List<EvidenceMatch> dedup = BANK.strongestFor("deduplication");

        assertThat(dedup).extracting(EvidenceMatch::itemId).containsExactly("acme-replay");
        assertThat(dedup.getFirst().kind()).isEqualTo(EvidenceMatch.Kind.CONCEPT);
        assertThat(dedup.getFirst().reason()).contains("deduplication");
        assertThat(ids("message ordering")).contains("acme-replay");
        assertThat(ids("asynchronous processing")).first().isEqualTo("docs-async");
    }

    @Test
    @DisplayName("'event-driven backend systems' finds Kafka, event replay, ordering and deduplication")
    void eventDrivenBackendSystems() {
        List<EvidenceMatch> matches = BANK.strongestFor("event-driven backend systems");

        assertThat(matches.getFirst().itemId()).isEqualTo("acme-replay");
        assertThat(matches.getFirst().item().concepts())
                .contains("event replay", "deduplication", "ordering");
        assertThat(matches).anySatisfy(m -> {
            assertThat(m.itemId()).isEqualTo("docs-async");
            assertThat(m.kind()).isEqualTo(EvidenceMatch.Kind.IMPLEMENTS);
            assertThat(m.reason()).isEqualTo("Kafka implements event-driven");
        });
    }

    @Test
    @DisplayName("a general idea - backend engineering - is real evidence and ranks below anything specific")
    void generalIdeasRankLast() {
        List<EvidenceMatch> matches = BANK.strongestFor("event-driven backend systems");
        EvidenceMatch docs = matches.stream().filter(m -> m.itemId().equals("docs-async"))
                .findFirst().orElseThrow();

        assertThat(matches).filteredOn(m -> m.itemId().equals("sched-api")).singleElement()
                .satisfies(m -> assertThat(m.kind()).isEqualTo(EvidenceMatch.Kind.GENERAL));
        assertThat(matches).filteredOn(m -> m.kind() == EvidenceMatch.Kind.GENERAL).isNotEmpty()
                .allSatisfy(m -> assertThat(m.score()).isLessThan(docs.score()));
    }

    // ---- Ranking ------------------------------------------------------------

    @Test
    @DisplayName("of two items showing Kafka, the job with a measured result outranks the project")
    void ranking() {
        List<EvidenceMatch> matches = BANK.strongestFor("Kafka");

        assertThat(matches.get(0).score()).isGreaterThan(matches.get(1).score());
        assertThat(matches.get(0).source().kind()).isEqualTo(EvidenceSource.Kind.EMPLOYMENT);
        assertThat(BANK.strongestFor("Kafka", 1)).extracting(EvidenceMatch::itemId)
                .containsExactly("acme-replay");
    }

    @Test
    @DisplayName("equal evidence ranks in file order, so reordering the file is the only way to change it")
    void tiesFollowTheFile() {
        String head = """
                version: 1
                sources:
                  - id: a
                    kind: project
                    name: A
                items:
                """;
        String sessions = """
                  - id: sessions
                    source: a
                    claim: "Cached sessions in Redis."
                    technologies: [Redis]
                """;
        String reports = """
                  - id: reports
                    source: a
                    claim: "Cached reports in Redis."
                    technologies: [Redis]
                """;

        assertThat(EvidenceFixtures.bank(head + sessions + reports).strongestFor("Redis"))
                .extracting(EvidenceMatch::itemId).containsExactly("sessions", "reports");
        assertThat(EvidenceFixtures.bank(head + reports + sessions).strongestFor("Redis"))
                .extracting(EvidenceMatch::itemId).containsExactly("reports", "sessions");
    }

    @Test
    @DisplayName("a compound is split the way the ledger splits it: Python/JavaScript finds Python")
    void compounds() {
        assertThat(ids("Python/JavaScript")).contains("acme-ingest", "docs-async");
        assertThat(ids("Kafka / Redis")).contains("acme-replay", "acme-cache", "docs-async");
    }

    @Test
    @DisplayName("no evidence is an empty answer, never the nearest thing")
    void noEvidence() {
        assertThat(BANK.strongestFor("COBOL")).isEmpty();
        assertThat(BANK.strongestFor("Terraform")).isEmpty();
        assertThat(BANK.strongestFor("")).isEmpty();
        assertThat(EvidenceBank.empty("none", List.of()).strongestFor("Kafka")).isEmpty();
    }

    // ---- Through the ledger ------------------------------------------------

    private static final com.anuragbhandary.jobradar.apply.resume.ResumeModel RESUME =
            EvidenceFixtures.resume();
    private static final CoverageAnalyzer ANALYZER = new CoverageAnalyzer(
            new ExperiencePositioner(new ExperienceIndex(RESUME)), new ResumeSources(RESUME));

    private static CoverageLedger.Entry entry(String term) {
        return ANALYZER.analyse(1L, "t", "test", List.of(Requirement.of(term,
                RequirementCategory.TECHNOLOGY, RequirementImportance.REQUIRED, term)))
                .entries().getFirst();
    }

    @Test
    @DisplayName("Kubernetes is ADJACENT: the Docker item is put forward, scored down, and not a claim")
    void adjacentThroughTheLedger() {
        CoverageLedger.Entry kubernetes = entry("Kubernetes");
        List<EvidenceMatch> matches = BANK.evidenceFor(kubernetes);

        assertThat(kubernetes.level()).isEqualTo(ExperienceLevel.ADJACENT);
        assertThat(matches).extracting(EvidenceMatch::itemId).containsExactly("sched-containers");
        assertThat(matches.getFirst().supportsClaim()).isFalse();
        assertThat(matches.getFirst().reason()).contains("not claimed");
        assertThat(matches.getFirst().score())
                .isLessThan(BANK.strongestFor("Docker").getFirst().score());
    }

    @Test
    @DisplayName("when the ledger says NONE for a product, the bank cannot overrule it")
    void noneForAProductIsFinal() {
        CoverageLedger.Entry none = new CoverageLedger.Entry(
                Requirement.of("Kafka", RequirementCategory.TECHNOLOGY, RequirementImportance.REQUIRED, "Kafka"),
                ExperienceLevel.NONE, CoverageLedger.MatchedBy.NONE, List.of(), List.of(), List.of(),
                CoverageLedger.ResumeUse.OMIT, "");

        assertThat(BANK.evidenceFor(none)).isEmpty();
    }

    @Test
    @DisplayName("TRANSFERABLE puts nothing forward: general foundations say nothing about ClickHouse")
    void transferableGivesNothing() {
        CoverageLedger.Entry transferable = new CoverageLedger.Entry(
                Requirement.of("ClickHouse", RequirementCategory.DATABASE, RequirementImportance.REQUIRED,
                        "ClickHouse"),
                ExperienceLevel.TRANSFERABLE, CoverageLedger.MatchedBy.INDEX,
                List.of("backend engineering", "testing"), List.of(), List.of(),
                CoverageLedger.ResumeUse.EMPHASISE_EVIDENCE, "");

        assertThat(BANK.evidenceFor(transferable)).isEmpty();
    }

    @Test
    @DisplayName("an idea the ledger cannot place - deduplication - is found in the bank's own words")
    void noneForAnIdeaUsesTheBank() {
        CoverageLedger.Entry dedup = entry("deduplication");

        assertThat(dedup.level()).isEqualTo(ExperienceLevel.NONE);
        assertThat(BANK.evidenceFor(dedup)).extracting(EvidenceMatch::itemId)
                .containsExactly("acme-replay");
    }
}
