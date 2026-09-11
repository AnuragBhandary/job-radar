package com.anuragbhandary.jobradar.apply.resume.rewrite;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator.IssueType;
import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator.Verdict;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Words that limit a claim. Every test here is a rewrite that adds nothing false
 * and still says something untrue, because of what it took away.
 */
class QualifierGuardTest {

    private static Verdict validate(String source, String rewrite, Set<String> terms) {
        EvidenceScope scope = new EvidenceScope("s1", source, source, terms, Map.of());
        return ResumeClaimValidator.check(rewrite,
                new RewriteRequest("s1", source, scope, List.of(), List.of(), List.of(), 500),
                Set.of("s1"));
    }

    @Test
    @DisplayName("self-built cannot quietly become just a platform")
    void selfBuiltPreserved() {
        assertThat(QualifierGuard.lost("Built a self-built job-scheduling platform in Java.",
                "Built a job-scheduling platform in Java.")).singleElement()
                .satisfies(line -> assertThat(line).contains("ATTRIBUTION"));
        // "personal" says the same thing, and is declared to.
        assertThat(QualifierGuard.lost("Built a self-built job-scheduling platform.",
                "Built a personal job-scheduling platform.")).isEmpty();
    }

    @Test
    @DisplayName("a personal project cannot become a project")
    void personalPreserved() {
        assertThat(QualifierGuard.lost("Developed a personal project that tracks job postings.",
                "Developed a project that tracks job postings.")).isNotEmpty();
    }

    @Test
    @DisplayName("roughly fourfold cannot become fourfold, and ~4x keeps the hedge")
    void approximationPreserved() {
        Verdict exact = validate("Cut report build time roughly fourfold.",
                "Cut report build time fourfold.", Set.of());
        Verdict tilde = validate("Cut report build time roughly fourfold.",
                "Cut report build time by ~4x.", Set.of());

        assertThat(exact.count(IssueType.QUALIFIER_LOST)).isEqualTo(1);
        assertThat(tilde.pass()).as(tilde.issues().toString()).isTrue();
    }

    @Test
    @DisplayName("production-style cannot become professional experience, or production")
    void productionStylePreserved() {
        String source = "Built a production-style Java and Spring Boot backend for job scheduling.";
        Verdict professional = validate(source,
                "Built professional Java and Spring Boot backend experience for job scheduling.",
                Set.of("java", "spring boot"));
        Verdict production = validate(source,
                "Built a production Java and Spring Boot backend for job scheduling.",
                Set.of("java", "spring boot"));

        assertThat(professional.issues()).extracting(ResumeClaimValidator.Issue::type)
                .contains(IssueType.QUALIFIER_LOST, IssueType.UNSUPPORTED_CLAIM);
        assertThat(production.issues()).extracting(ResumeClaimValidator.Issue::type)
                .contains(IssueType.QUALIFIER_LOST, IssueType.UNSUPPORTED_CLAIM);
    }

    @Test
    @DisplayName("a duration may be reworded but not dropped or changed")
    void durationPreserved() {
        assertThat(QualifierGuard.lost("Spent a year building Python services.",
                "Spent one year building Python services.")).isEmpty();
        assertThat(QualifierGuard.lost("Spent a year building Python services.",
                "Built Python services.")).anyMatch(line -> line.contains("DURATION"));
        assertThat(QualifierGuard.lost("Spent a year building Python services.",
                "Spent two years building Python services.")).anyMatch(line -> line.contains("DURATION"));
    }

    @Test
    @DisplayName("helping build something is not building it")
    void ownershipPreserved() {
        assertThat(QualifierGuard.lost("Helped build the ingestion API.",
                "Built the ingestion API.")).anyMatch(line -> line.contains("OWNERSHIP"));
    }

    @Test
    @DisplayName("'rather than the models themselves' is the honest framing, and stays")
    void framingPreserved() {
        String source = "Built the services around AI systems rather than the models themselves.";
        assertThat(QualifierGuard.lost(source, "Built AI systems and their models.")).isNotEmpty();
        assertThat(QualifierGuard.lost(source,
                "Built the services around AI systems, not the models themselves.")).isEmpty();
    }

    @Test
    @DisplayName("an ordinary rewrite with no limiting words loses nothing")
    void ordinaryRewriteUntouched() {
        assertThat(QualifierGuard.lost("Built a Kafka-based event replay service.",
                "Built an event-driven Kafka replay system for game events.")).isEmpty();
    }

    @Test
    @DisplayName("dropping a claim is allowed: 'in production' is not a qualifier")
    void droppingAClaimIsAllowed() {
        assertThat(QualifierGuard.lost("Shipped a Kafka replay pipeline in production.",
                "Shipped a Kafka replay pipeline.")).isEmpty();
    }
}
