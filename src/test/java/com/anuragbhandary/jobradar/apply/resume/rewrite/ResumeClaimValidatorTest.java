package com.anuragbhandary.jobradar.apply.resume.rewrite;

import static com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteFixtures.known;
import static com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteFixtures.request;
import static com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteFixtures.summaryRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator.IssueType;
import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator.Verdict;
import java.util.List;
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
    @DisplayName("roughly 3x said as roughly threefold is the same metric")
    void metricRephrased() {
        Verdict threefold = check("Reduced report generation time roughly threefold by caching "
                + "results in Redis.", request("report-cache"));
        Verdict tilde = check("Cut report generation time by ~3x through Redis result caching.",
                request("report-cache"));

        assertThat(threefold.pass()).isTrue();
        assertThat(threefold.sourceMetricsKept()).isEqualTo(1);
        assertThat(tilde.pass()).isTrue();
        assertThat(tilde.warnings()).noneMatch(w -> w.startsWith("hedge dropped"));
    }

    @Test
    @DisplayName("dropping a metric passes, with a warning, and dropping the hedge is flagged")
    void metricDroppedOrUnhedged() {
        Verdict dropped = check("Cached report results in Redis to speed up generation.",
                request("report-cache"));
        Verdict unhedged = check("Cut report generation time 3x by caching results in Redis.",
                request("report-cache"));

        assertThat(dropped.pass()).isTrue();
        assertThat(dropped.warnings()).anyMatch(w -> w.startsWith("metric dropped"));
        assertThat(unhedged.pass()).isTrue();
        assertThat(unhedged.warnings()).anyMatch(w -> w.startsWith("hedge dropped"));
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
        assertThat(verdict.issues()).anyMatch(i -> i.type() == IssueType.UNSUPPORTED_PRODUCT
                && i.detail().startsWith("Streams"));
    }

    @Test
    @DisplayName("PostgreSQL on AWS is not Aurora, and not Terraform")
    void auroraAndTerraform() {
        assertThat(check("Stored upload metadata in Aurora PostgreSQL on AWS.",
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

    // ---- Identity, emptiness, meaning ------------------------------------

    @Test
    @DisplayName("an unknown sourceId fails before anything else is read")
    void unknownSource() {
        EvidenceScope scope = new EvidenceScope("invented-1", "x", "x", Set.of());
        RewriteRequest request = new RewriteRequest("invented-1", "x", scope, List.of(), List.of(),
                List.of(), 100, false);

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

    // ---- Summary ----------------------------------------------------------

    @Test
    @DisplayName("a summary may not grow a year into three")
    void summaryYears() {
        Verdict invented = ResumeClaimValidator.check("Backend engineer with three years of "
                + "experience building Python services and event-driven systems.",
                summaryRequest(), known());
        Verdict kept = ResumeClaimValidator.check("Backend engineer with a year of experience "
                + "building event-driven Python services on Kafka and PostgreSQL.",
                summaryRequest(), known());

        assertThat(invented.count(IssueType.INVENTED_YEARS)).isEqualTo(1);
        assertThat(kept.pass()).as(kept.issues().toString()).isTrue();
    }

    @Test
    @DisplayName("a summary may not claim seniority the source never did")
    void summarySeniority() {
        Verdict verdict = ResumeClaimValidator.check("Senior backend engineer with deep expertise "
                + "in Python services and event-driven systems.", summaryRequest(), known());

        assertThat(verdict.count(IssueType.INFLATED_RESPONSIBILITY)).isGreaterThanOrEqualTo(1);
    }
}
