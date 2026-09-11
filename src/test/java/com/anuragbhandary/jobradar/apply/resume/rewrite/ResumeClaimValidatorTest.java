package com.anuragbhandary.jobradar.apply.resume.rewrite;

import static com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteFixtures.known;
import static com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator.IssueType;
import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator.Verdict;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * New wording is acceptable; new facts are not. Each test is a rewrite a model
 * would plausibly produce, and what must happen to it.
 */
class ResumeClaimValidatorTest {

    private static Verdict check(String rewrite, RewriteRequest request) {
        return ResumeClaimValidator.check(rewrite, request, known());
    }

    // ---- Kubernetes from Docker -----------------------------------------

    @Test
    @DisplayName("Docker is not Kubernetes: a deployment claim fails")
    void kubernetesFromDocker() {
        Verdict verdict = check("Built and deployed Kubernetes services using Docker.",
                request("docker-services", "Kubernetes"));

        assertThat(verdict.pass()).isFalse();
        assertThat(verdict.count(IssueType.UNSUPPORTED_TECHNOLOGY)).isEqualTo(1);
        assertThat(verdict.issues()).anyMatch(i -> i.detail().contains("deployed"));
    }

    @Test
    @DisplayName("managing production clusters from Docker evidence fails on every count")
    void managedProductionClusters() {
        Verdict verdict = check("Managed production Kubernetes clusters for backend services.",
                request("docker-services", "Kubernetes"));

        assertThat(verdict.pass()).isFalse();
        assertThat(verdict.issues()).extracting(ResumeClaimValidator.Issue::type)
                .contains(IssueType.UNSUPPORTED_TECHNOLOGY, IssueType.UNSUPPORTED_CLAIM,
                        IssueType.INFLATED_RESPONSIBILITY);
    }

    @Test
    @DisplayName("Docker may be called containerised, which is what it is")
    void containerisedFromDocker() {
        assertThat(check("Built containerised backend services with Docker.",
                request("docker-services", "Kubernetes")).pass()).isTrue();
    }

    @Test
    @DisplayName("but containers with no Docker in scope are a new fact")
    void containersWithoutDocker() {
        Verdict verdict = check("Implemented containerised request validation and retry "
                + "handling for the ingestion API.", request("ingest-validation"));

        assertThat(verdict.count(IssueType.UNSUPPORTED_CLAIM)).isEqualTo(1);
    }

    // ---- Numbers --------------------------------------------------------

    @Test
    @DisplayName("3x becoming 40% is an invented metric")
    void inventedMetric() {
        Verdict verdict = check("Cut report generation time by 40% by caching results in Redis.",
                request("report-cache"));

        assertThat(verdict.pass()).isFalse();
        assertThat(verdict.count(IssueType.INVENTED_NUMBER)).isEqualTo(1);
    }

    @Test
    @DisplayName("roughly 3x said as roughly threefold, or ~3x, is the same metric")
    void metricRephrased() {
        Verdict threefold = check("Reduced report generation time roughly threefold by caching "
                + "results in Redis.", request("report-cache"));
        Verdict tilde = check("Cut report generation time by ~3x through Redis result caching.",
                request("report-cache"));

        assertThat(threefold.pass()).isTrue();
        assertThat(threefold.sourceMetricsKept()).isEqualTo(1);
        assertThat(tilde.pass()).as(tilde.issues().toString()).isTrue();
    }

    @Test
    @DisplayName("dropping a metric passes with a warning; dropping only its hedge fails")
    void metricDroppedOrUnhedged() {
        Verdict dropped = check("Cached report results in Redis to speed up generation.",
                request("report-cache"));
        Verdict unhedged = check("Cut report generation time 3x by caching results in Redis.",
                request("report-cache"));

        assertThat(dropped.pass()).isTrue();
        assertThat(dropped.warnings()).anyMatch(w -> w.startsWith("metric dropped"));
        assertThat(unhedged.pass()).isFalse();
        assertThat(unhedged.count(IssueType.QUALIFIER_LOST)).isEqualTo(1);
    }

    @Test
    @DisplayName("years that are not in the source are invented")
    void inventedYears() {
        Verdict verdict = check("Implemented request validation and retry handling for the "
                + "ingestion API over three years.", request("ingest-validation"));

        assertThat(verdict.count(IssueType.INVENTED_YEARS)).isEqualTo(1);
    }

    // ---- Responsibility -------------------------------------------------

    @Test
    @DisplayName("'implemented' becoming 'led the architecture' is inflation")
    void inflatedLeadership() {
        Verdict verdict = check("Led the architecture of request validation and retry handling "
                + "for the ingestion API.", request("ingest-validation"));

        assertThat(verdict.pass()).isFalse();
        assertThat(verdict.count(IssueType.INFLATED_RESPONSIBILITY)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("seniority and expertise need the source to claim them")
    void inflatedSeniority() {
        Verdict verdict = check("Implemented expert-level request validation and retry handling "
                + "for the ingestion API.", request("ingest-validation"));

        assertThat(verdict.count(IssueType.INFLATED_RESPONSIBILITY)).isEqualTo(1);
    }

    @Test
    @DisplayName("owned and architected need the source to say so")
    void ownedAndArchitected() {
        assertThat(check("Owned request validation and retry handling for the ingestion API.",
                request("ingest-validation")).pass()).isFalse();
        assertThat(check("Architected request validation and retry handling for the ingestion API.",
                request("ingest-validation")).pass()).isFalse();
        // This one already says Architected, so the word is his.
        assertThat(check("Architected event-driven document ingestion using Kafka.",
                request("arch-kafka")).pass()).isTrue();
    }

    // ---- Products -------------------------------------------------------

    @Test
    @DisplayName("Kafka is not Kafka Streams")
    void kafkaStreams() {
        Verdict verdict = check("Implemented Kafka Streams processing of real-time game events "
                + "with deduplication.", request("kafka-events"));

        assertThat(verdict.pass()).isFalse();
        assertThat(verdict.issues()).anyMatch(i -> i.type() == IssueType.UNSUPPORTED_PRODUCT);
    }

    @Test
    @DisplayName("PostgreSQL on AWS is not Aurora, in any case, and not Terraform")
    void auroraAndTerraform() {
        assertThat(check("Stored upload metadata in Aurora PostgreSQL on AWS.",
                request("pg-aws")).count(IssueType.UNSUPPORTED_PRODUCT)).isGreaterThanOrEqualTo(1);
        assertThat(check("Stored upload metadata in aurora postgresql on AWS.",
                request("pg-aws")).count(IssueType.UNSUPPORTED_PRODUCT)).isEqualTo(1);
        assertThat(check("Provisioned PostgreSQL on AWS with Terraform for upload metadata.",
                request("pg-aws", "Terraform")).count(IssueType.UNSUPPORTED_TECHNOLOGY))
                .isEqualTo(1);
    }

    // ---- Legitimate reframing -------------------------------------------

    @Test
    @DisplayName("Kafka real-time processing may be called event-driven")
    void legitimateReframing() {
        Verdict verdict = check("Implemented event-driven, real-time game-event processing on "
                + "Kafka with deduplication.", request("kafka-events", "Kubernetes"));

        assertThat(verdict.pass()).as(verdict.issues().toString()).isTrue();
    }

    @Test
    @DisplayName("REST endpoints may be called REST APIs, but not production ones")
    void restApisAndProduction() {
        assertThat(check("Developed REST APIs for document upload and status queries.",
                request("doc-endpoints")).pass()).isTrue();
        assertThat(check("Developed production REST APIs for document upload and status queries.",
                request("doc-endpoints")).count(IssueType.UNSUPPORTED_CLAIM)).isEqualTo(1);
    }

    @Test
    @DisplayName("a technology tacked onto the end is stuffing, even when it is true")
    void keywordStuffing() {
        // Python is true of this project - it is written in Python alone - and it
        // is still not what this sentence is about.
        Verdict verdict = check("Developed REST endpoints for document upload and status "
                + "queries using Python.", request("doc-endpoints"));

        assertThat(verdict.pass()).isFalse();
        assertThat(verdict.issues()).extracting(ResumeClaimValidator.Issue::type)
                .containsExactly(IssueType.KEYWORD_STUFFING);
    }

    // ---- Identity, emptiness, meaning ------------------------------------

    @Test
    @DisplayName("an unknown sourceId fails before anything else is read")
    void unknownSource() {
        EvidenceScope scope = new EvidenceScope("invented-1", "x", "x", Set.of(), Map.of());
        RewriteRequest request = new RewriteRequest("invented-1", "x", scope, List.of(),
                List.of(), List.of(), 100);

        Verdict verdict = ResumeClaimValidator.check("Built things.", request, known());

        assertThat(verdict.pass()).isFalse();
        assertThat(verdict.count(IssueType.UNKNOWN_SOURCE)).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty rewrite is rejected")
    void emptyRewrite() {
        assertThat(check("  ", request("pg-aws")).count(IssueType.EMPTY)).isEqualTo(1);
    }

    @Test
    @DisplayName("a different accomplishment that suits the job better is not a rewrite")
    void meaningDrift() {
        Verdict verdict = check("Built analytics dashboards for monitoring game performance.",
                request("ingest-validation"));

        assertThat(verdict.pass()).isFalse();
        assertThat(verdict.count(IssueType.MEANING_DRIFT)).isEqualTo(1);
    }
}
