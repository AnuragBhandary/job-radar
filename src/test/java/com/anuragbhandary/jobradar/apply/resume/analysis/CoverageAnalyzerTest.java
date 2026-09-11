package com.anuragbhandary.jobradar.apply.resume.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.TestResumes;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger.Entry;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger.EvidenceRef;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger.MatchedBy;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger.ResumeUse;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ledger against a resume with the real one's technologies, for the same
 * reason the positioner's tests use it: the question is whether the verdicts are
 * right for the evidence he actually has.
 */
class CoverageAnalyzerTest {

    private final ResumeModel resume = TestResumes.backendResume();
    private final ResumeSources sources = new ResumeSources(resume);
    private final CoverageAnalyzer analyzer = new CoverageAnalyzer(
            new ExperiencePositioner(new ExperienceIndex(resume)), sources);

    private static Requirement req(String term, RequirementImportance importance) {
        return Requirement.of(term, RequirementCategory.TECHNOLOGY, importance, term);
    }

    private Entry one(String term) {
        return analyzer.analyse(1L, "t", "test", List.of(req(term, RequirementImportance.REQUIRED)))
                .entries().getFirst();
    }

    private Set<String> allIds() {
        return sources.all().stream().map(ResumeSources.SourceItem::id).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("DIRECT: his own technology, cited from the work bullet first")
    void direct() {
        Entry kafka = one("Kafka");

        assertThat(kafka.level()).isEqualTo(ExperienceLevel.DIRECT);
        assertThat(kafka.resumeUse()).isEqualTo(ResumeUse.CLAIM);
        assertThat(kafka.evidence()).isNotEmpty();
        assertThat(kafka.evidence().getFirst().kind()).isEqualTo(ResumeSources.Kind.JOB_BULLET);
        assertThat(kafka.evidence().getFirst().text()).contains("Kafka");
    }

    @Test
    @DisplayName("ADJACENT: Kubernetes reaches Docker, and may not be claimed")
    void adjacent() {
        Entry kubernetes = one("Kubernetes");

        assertThat(kubernetes.level()).isEqualTo(ExperienceLevel.ADJACENT);
        assertThat(kubernetes.resumeUse()).isEqualTo(ResumeUse.EMPHASISE_EVIDENCE);
        assertThat(kubernetes.via()).contains("docker");
        assertThat(kubernetes.evidence()).isNotEmpty().allSatisfy(ref ->
                assertThat(sources.find(ref.sourceId()).orElseThrow().terms()).contains("docker"));
        assertThat(kubernetes.note()).contains("Never list Kubernetes as a skill");
    }

    @Test
    @DisplayName("CONCEPTUAL: Cassandra reaches the idea he has worked on, not a tool")
    void conceptual() {
        Entry cassandra = one("Cassandra");

        assertThat(cassandra.level()).isEqualTo(ExperienceLevel.CONCEPTUAL);
        assertThat(cassandra.resumeUse()).isEqualTo(ResumeUse.EMPHASISE_EVIDENCE);
        assertThat(cassandra.via()).contains("distributed systems");
    }

    @Test
    @DisplayName("TRANSFERABLE: Elasticsearch has only a named engineering foundation")
    void transferable() {
        Entry elasticsearch = one("Elasticsearch");

        assertThat(elasticsearch.level()).isEqualTo(ExperienceLevel.TRANSFERABLE);
        assertThat(elasticsearch.resumeUse()).isEqualTo(ResumeUse.EMPHASISE_EVIDENCE);
    }

    @Test
    @DisplayName("NONE: COBOL cites nothing and is left out")
    void none() {
        Entry cobol = one("COBOL");

        assertThat(cobol.level()).isEqualTo(ExperienceLevel.NONE);
        assertThat(cobol.resumeUse()).isEqualTo(ResumeUse.OMIT);
        assertThat(cobol.evidence()).isEmpty();
    }

    @Test
    @DisplayName("required outweighs preferred, however often the preferred one is mentioned")
    void requiredOutweighsPreferred() {
        Posting posting = new Posting(Source.GREENHOUSE, "acme", "1", "Software Engineer");
        posting.setDescriptionText("""
                We run everything on Kubernetes. Kubernetes clusters, Kubernetes operators,
                Kubernetes everywhere. Our Kubernetes platform team is great.
                Requirements:
                - Java, Spring Boot and Kafka.
                Nice to have:
                - Kubernetes.""");

        CoverageLedger ledger = analyzer.analyse(posting);
        Map<String, Entry> byTerm = ledger.entries().stream()
                .collect(Collectors.toMap(e -> e.requirement().term(), e -> e));

        // Five mentions are one requirement, and the strongest section wins.
        assertThat(byTerm.get("kubernetes").requirement().importance())
                .isEqualTo(RequirementImportance.PREFERRED);
        assertThat(ledger.requiredTotal()).isEqualTo(3);
        assertThat(ledger.requiredDirect()).isEqualTo(3);
        // Required rows first, then preferred.
        assertThat(ledger.entries().subList(0, 3)).allMatch(e ->
                e.requirement().importance() == RequirementImportance.REQUIRED);

        // The item a planner would lead with carries the required evidence.
        String top = ledger.sourcePriority().keySet().iterator().next();
        Set<String> requiredIds = ledger.entries().stream()
                .filter(e -> e.requirement().importance() == RequirementImportance.REQUIRED)
                .flatMap(e -> e.evidence().stream()).map(EvidenceRef::sourceId)
                .collect(Collectors.toSet());
        assertThat(requiredIds).contains(top);
        // A preferred ADJACENT match is worth 0.5 per item; one required DIRECT one is 3.
        assertThat(ledger.sourcePriority().get(top)).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    @DisplayName("every cited id is a real source item")
    void evidenceIdsAreValid() {
        CoverageLedger ledger = analyzer.analyse(1L, "t", "test", List.of(
                req("Kafka", RequirementImportance.REQUIRED),
                req("Kubernetes", RequirementImportance.PREFERRED),
                req("Cassandra", RequirementImportance.SIGNAL),
                req("real-time delivery", RequirementImportance.REQUIRED),
                req("replay pipeline", RequirementImportance.SIGNAL),
                req("Zorbtrix DB", RequirementImportance.REQUIRED)));

        Set<String> ids = allIds();
        for (Entry entry : ledger.entries()) {
            entry.evidence().forEach(ref -> assertThat(ids).contains(ref.sourceId()));
            assertThat(ids).containsAll(entry.candidateSourceIds());
        }
        assertThat(ids).containsAll(ledger.sourcePriority().keySet());
    }

    @Test
    @DisplayName("a technology he has not used is never DIRECT, even when a bullet mentions containers")
    void unsupportedTechnologyIsNotDirect() {
        ResumeModel base = TestResumes.backendResume();
        ResumeModel withContainers = new ResumeModel(base.headline(), base.summaries(),
                base.skills(), List.of(new ResumeModel.Job("An employer", "Engineer", "Remote",
                        "2025 - 2026", null, List.of(new ResumeModel.Bullet(
                                "Packaged the services as containers for deployment.",
                                List.of())))),
                base.projects(), base.education(), base.extras(), 3, 4, 3);
        CoverageAnalyzer local = new CoverageAnalyzer(
                new ExperiencePositioner(new ExperienceIndex(withContainers)),
                new ResumeSources(withContainers));

        CoverageLedger ledger = local.analyse(1L, "t", "test", List.of(
                req("Kubernetes", RequirementImportance.REQUIRED),
                req("container orchestration", RequirementImportance.REQUIRED),
                req("Terraform", RequirementImportance.REQUIRED)));

        assertThat(ledger.entries()).noneMatch(e -> e.level() == ExperienceLevel.DIRECT);
        assertThat(ledger.entries()).noneMatch(e -> e.resumeUse() == ResumeUse.CLAIM);
        assertThat(ledger.requiredDirect()).isZero();
    }

    @Test
    @DisplayName("a requirement outside the vocabulary maps through his own wording")
    void outsideVocabularyThroughEvidence() {
        Entry realTime = one("real-time delivery");

        assertThat(realTime.level()).isEqualTo(ExperienceLevel.DIRECT);
        assertThat(realTime.matchedBy()).isEqualTo(MatchedBy.TEXT);
        assertThat(realTime.evidence()).singleElement()
                .satisfies(ref -> assertThat(ref.text()).contains("real-time delivery"));

        Entry rest = one("design REST APIs");
        assertThat(rest.level()).isEqualTo(ExperienceLevel.DIRECT);
        assertThat(rest.evidence()).extracting(EvidenceRef::sourceId).contains("skills-backend-apis");
        assertThat(rest.note()).contains("skills list only");
    }

    @Test
    @DisplayName("word overlap is only a candidate for review, never evidence")
    void overlapIsACandidateNotEvidence() {
        Entry replay = one("replay pipeline");

        assertThat(replay.level()).isEqualTo(ExperienceLevel.NONE);
        assertThat(replay.evidence()).isEmpty();
        assertThat(replay.candidateSourceIds()).isNotEmpty();
        assertThat(replay.resumeUse()).isEqualTo(ResumeUse.OMIT);
    }

    @Test
    @DisplayName("a narrower name than his evidence is ADJACENT, never DIRECT")
    void narrowerThanEvidence() {
        Entry streams = one("Kafka Streams");

        assertThat(streams.level()).isEqualTo(ExperienceLevel.ADJACENT);
        assertThat(streams.resumeUse()).isEqualTo(ResumeUse.EMPHASISE_EVIDENCE);
        assertThat(streams.via()).containsExactly("kafka");
    }

    @Test
    @DisplayName("an unknown requirement invents no source id")
    void noInventedIds() {
        Entry unknown = one("Zorbtrix DB");

        assertThat(unknown.level()).isEqualTo(ExperienceLevel.NONE);
        assertThat(unknown.evidence()).isEmpty();
        assertThat(unknown.candidateSourceIds()).isEmpty();
    }

    @Test
    @DisplayName("the same requirements give the same ledger")
    void deterministic() {
        List<Requirement> requirements = List.of(
                req("Kafka", RequirementImportance.REQUIRED),
                req("Kubernetes", RequirementImportance.PREFERRED));

        assertThat(analyzer.analyse(1L, "t", "test", requirements))
                .isEqualTo(analyzer.analyse(1L, "t", "test", requirements));
    }
}
