package com.anuragbhandary.jobradar.apply.resume.rewrite;

import static com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteFixtures.RESUME;
import static com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteFixtures.SOURCES;
import static com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteFixtures.known;
import static com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.analysis.Requirement;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementCategory;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator.IssueType;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bullet rests on its own words, plus project context that is true of it and
 * says why. Not on its project's whole stack.
 */
class EvidenceScopeTest {

    private static EvidenceScope scope(String id) {
        return EvidenceScope.forBullet(SOURCES, id).orElseThrow();
    }

    @Test
    @DisplayName("a bullet cannot gain Kafka because another bullet of its project used it")
    void unrelatedStackTechnologyRejected() {
        assertThat(scope("sched-redis").terms()).doesNotContain("kafka", "postgresql", "docker");

        var verdict = ResumeClaimValidator.check(
                "Coordinated scheduler workers with Redis leases and Kafka.",
                request("sched-redis"), known());
        assertThat(verdict.count(IssueType.UNSUPPORTED_TECHNOLOGY)).isEqualTo(1);
    }

    @Test
    @DisplayName("the bullet's own technology is primary evidence")
    void bulletTechnologyAccepted() {
        assertThat(scope("sched-redis").primaryTerms()).contains("redis");
        assertThat(ResumeClaimValidator.check("Coordinated scheduler workers using Redis leases.",
                request("sched-redis"), known()).pass()).isTrue();
    }

    @Test
    @DisplayName("a single-language project's language is justified secondary evidence")
    void projectLanguageWhenJustified() {
        EvidenceScope scope = scope("sched-redis");

        assertThat(scope.secondaryTerms()).containsKey("java");
        assertThat(scope.secondaryTerms().get("java")).contains("written in java alone");
        assertThat(ResumeClaimValidator.check("Coordinated Java scheduler workers with Redis leases.",
                request("sched-redis"), known()).pass()).isTrue();
    }

    @Test
    @DisplayName("a polyglot project justifies no language for a sentence that names none")
    void polyglotProjectJustifiesNothing() {
        assertThat(scope("risk-store").terms()).doesNotContain("java", "python", "spring boot", "fastapi");
        assertThat(ResumeClaimValidator.check("Stored risk scores for later review in a Python service.",
                request("risk-store"), known()).pass()).isFalse();
    }

    @Test
    @DisplayName("'containers' justifies the project's Docker, and says so")
    void wordingJustifiesOneStackItem() {
        EvidenceScope scope = scope("sched-containers");

        assertThat(scope.secondaryTerms()).containsKey("docker");
        assertThat(scope.secondaryTerms().get("docker")).contains("containers");
        assertThat(scope.terms()).doesNotContain("kafka", "redis", "postgresql");
    }

    @Test
    @DisplayName("secondary evidence may be named but is never a rewrite target")
    void secondaryIsNotATarget() {
        CoverageAnalyzer analyzer = new CoverageAnalyzer(
                new ExperiencePositioner(new ExperienceIndex(RESUME)), SOURCES);
        CoverageLedger ledger = analyzer.analyse(1L, "Java Engineer", "test", List.of(
                Requirement.of("Java", RequirementCategory.LANGUAGE, RequirementImportance.REQUIRED, "Java"),
                Requirement.of("Redis", RequirementCategory.DATABASE, RequirementImportance.REQUIRED, "Redis")));

        RewriteRequest request = RewritePlanner.forBullet(scope("sched-redis"), ledger);

        assertThat(request.targets()).extracting(RewriteRequest.Target::term).containsExactly("Redis");
    }
}
