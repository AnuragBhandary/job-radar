package com.anuragbhandary.jobradar.apply.resume.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.TestResumes;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.rewrite.EvidenceScope;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewritePlanner;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ledger must never call a technology unsupported when one component of a
 * compound requirement is his - the bug that told the rewriter not to claim
 * Python on an AI posting.
 */
class CompoundRequirementsTest {

    private final ResumeModel resume = TestResumes.backendResume();
    private final ResumeSources sources = new ResumeSources(resume);
    private final CoverageAnalyzer analyzer = new CoverageAnalyzer(
            new ExperiencePositioner(new ExperienceIndex(resume)), sources);

    private static Requirement req(String term, RequirementImportance importance) {
        return Requirement.of(term, RequirementCategory.TECHNOLOGY, importance, term);
    }

    private static List<Requirement> expand(String term) {
        return CompoundRequirements.expand(req(term, RequirementImportance.REQUIRED));
    }

    private Map<String, CoverageLedger.Entry> ledger(Requirement... requirements) {
        return analyzer.analyse(1L, "t", "test", List.of(requirements)).entries().stream()
                .collect(Collectors.toMap(e -> e.requirement().id(), Function.identity()));
    }

    // ---- Splitting --------------------------------------------------------

    @Test
    @DisplayName("slash-separated technologies become independent alternatives")
    void slashSeparated() {
        List<Requirement> parts = expand("Python / JavaScript / TypeScript");

        assertThat(parts).extracting(Requirement::id)
                .containsExactly("req-python", "req-javascript", "req-typescript");
        assertThat(parts).allMatch(r -> "Python / JavaScript / TypeScript".equals(r.alternativeOf()));
        assertThat(parts).allMatch(r -> r.importance() == RequirementImportance.REQUIRED);
    }

    @Test
    @DisplayName("REST / GraphQL becomes REST and GraphQL")
    void restOrGraphql() {
        assertThat(expand("REST / GraphQL")).extracting(Requirement::term)
                .containsExactly("REST", "GraphQL");
    }

    @Test
    @DisplayName("a comma-and list is several requirements, each standing alone")
    void commaSeparated() {
        List<Requirement> parts = expand("Java, Spring Boot and Kafka");

        assertThat(parts).extracting(Requirement::id)
                .containsExactly("req-java", "req-spring-boot", "req-kafka");
        assertThat(parts).allMatch(r -> r.alternativeOf() == null);
    }

    @Test
    @DisplayName("'C, C++, or Rust' is one set of alternatives")
    void orAlternatives() {
        List<Requirement> parts = expand("C, C++, or Rust");

        assertThat(parts).extracting(Requirement::term).containsExactly("C", "C++", "Rust");
        assertThat(parts).allMatch(r -> "C, C++, or Rust".equals(r.alternativeOf()));
    }

    @Test
    @DisplayName("compounds that are one thing, or have a part that is not a name, stay whole")
    void notSplitIncorrectly() {
        assertThat(expand("CI/CD")).hasSize(1);
        assertThat(expand("TCP/IP")).hasSize(1);
        assertThat(expand("AWS (EC2, S3)")).hasSize(1);
        assertThat(expand("research and development")).hasSize(1);
        assertThat(expand("Kafka Streams")).hasSize(1);
    }

    @Test
    @DisplayName("versions are dropped from the name, and only separate version tokens")
    void parenthesisedVersions() {
        assertThat(PostingRequirements.canonicalSubject("Python (3.14)")).isEqualTo("python");
        assertThat(PostingRequirements.canonicalSubject("Java 17+")).isEqualTo("java");
        assertThat(PostingRequirements.canonicalSubject("Python 3.x")).isEqualTo("python");
        assertThat(PostingRequirements.canonicalSubject("EC2")).isEqualTo("EC2");
        assertThat(expand("Python (3.14)")).extracting(Requirement::id).containsExactly("req-python");
    }

    // ---- Merging ----------------------------------------------------------

    @Test
    @DisplayName("aliases and duplicates are one row, at the stronger importance")
    void aliasesAndDuplicates() {
        Map<String, CoverageLedger.Entry> ledger = ledger(
                req("K8s", RequirementImportance.PREFERRED),
                req("Kubernetes", RequirementImportance.REQUIRED),
                req("Postgres", RequirementImportance.SIGNAL),
                req("PostgreSQL", RequirementImportance.REQUIRED),
                req("Python (3.14)", RequirementImportance.SIGNAL),
                req("Python", RequirementImportance.PREFERRED));

        assertThat(ledger).containsKeys("req-kubernetes", "req-postgresql", "req-python");
        assertThat(ledger).doesNotContainKeys("req-k8s", "req-postgres", "req-python-3.14");
        assertThat(ledger.get("req-kubernetes").requirement().importance())
                .isEqualTo(RequirementImportance.REQUIRED);
        assertThat(ledger.get("req-python").level()).isEqualTo(ExperienceLevel.DIRECT);
    }

    // ---- The rule the whole fix exists for --------------------------------

    @Test
    @DisplayName("Python inside a compound is DIRECT, and is never listed as something not to claim")
    void componentWithEvidenceIsNeverProhibited() {
        CoverageLedger ledger = analyzer.analyse(1L, "AI Engineer", "test", List.of(
                req("Python/JavaScript/TypeScript", RequirementImportance.REQUIRED),
                req("REST / GraphQL", RequirementImportance.REQUIRED)));

        assertThat(ledger.entries()).filteredOn(e -> e.requirement().id().equals("req-python"))
                .singleElement().satisfies(e -> assertThat(e.level()).isEqualTo(ExperienceLevel.DIRECT));

        for (ResumeSources.SourceItem item : sources.bullets()) {
            List<String> prohibited = RewritePlanner.forBullet(
                    EvidenceScope.forBullet(sources, item.id()).orElseThrow(), ledger).prohibited();
            assertThat(prohibited).as(item.id())
                    .noneMatch(term -> term.toLowerCase().contains("python"))
                    .noneMatch(term -> term.equalsIgnoreCase("REST"))
                    .noneMatch(term -> term.contains("/"));
        }
    }

    @Test
    @DisplayName("a set of alternatives counts once, met by its best option")
    void alternativesCountOnce() {
        CoverageLedger none = analyzer.analyse(1L, "t", "test",
                List.of(req("C, C++, or Rust", RequirementImportance.REQUIRED)));
        CoverageLedger met = analyzer.analyse(1L, "t", "test",
                List.of(req("Java or Kotlin", RequirementImportance.REQUIRED)));

        assertThat(none.requiredTotal()).isEqualTo(1);
        assertThat(none.requiredDirect()).isZero();
        assertThat(met.requiredTotal()).isEqualTo(1);
        assertThat(met.requiredDirect()).isEqualTo(1);
        assertThat(met.weightedCoverage()).isEqualTo(1.0);
    }
}
