package com.anuragbhandary.jobradar.apply.resume.rewrite;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A small resume built to be rewritten badly: Docker without Kubernetes, a
 * hedged metric, Kafka without Kafka Streams, PostgreSQL on AWS without Aurora or
 * Terraform, a bullet that already says "Architected" and one that does not.
 */
final class RewriteFixtures {

    private RewriteFixtures() {
    }

    static final ResumeModel RESUME = new ResumeModel(
            "Backend Engineer",
            List.of(new ResumeModel.Summary("default", List.of(),
                    "Backend engineer with a year of experience building Python services "
                            + "and event-driven systems.")),
            List.of(new ResumeModel.SkillGroup("Languages", List.of("Python", "Java")),
                    new ResumeModel.SkillGroup("Cloud & DevOps",
                            List.of("AWS (EC2", "S3)", "Docker"))),
            List.of(new ResumeModel.Job("An employer", "Engineer", "Remote", "2025 - 2026", null,
                    List.of(
                            new ResumeModel.Bullet("Built containerised backend services using Docker.",
                                    List.of("docker"), "docker-services"),
                            new ResumeModel.Bullet(
                                    "Cut report generation time roughly 3x by caching results in Redis.",
                                    List.of("redis", "performance"), "report-cache"),
                            new ResumeModel.Bullet(
                                    "Implemented Kafka-based real-time processing of game events "
                                            + "with deduplication.",
                                    List.of("kafka", "real-time"), "kafka-events"),
                            new ResumeModel.Bullet(
                                    "Implemented request validation and retry handling for the "
                                            + "ingestion API.",
                                    List.of("api"), "ingest-validation"),
                            new ResumeModel.Bullet("Stored upload metadata in PostgreSQL on AWS.",
                                    List.of("postgresql", "aws"), "pg-aws"),
                            new ResumeModel.Bullet(
                                    "Architected Kafka-based event processing for document ingestion.",
                                    List.of("kafka"), "arch-kafka")))),
            List.of(new ResumeModel.Project("Doc Service", "Python · FastAPI · PostgreSQL · Docker",
                    List.of("python", "fastapi"),
                    List.of(new ResumeModel.Bullet(
                            "Developed REST endpoints for document upload and status queries.",
                            List.of("fastapi", "rest"), "doc-endpoints")))),
            List.of(), List.of(), 2, 6, 3);

    static final ResumeSources SOURCES = new ResumeSources(RESUME);

    static Set<String> known() {
        Set<String> ids = new HashSet<>();
        SOURCES.all().forEach(item -> ids.add(item.id()));
        ids.add(EvidenceScope.summaryIdOf("default"));
        return ids;
    }

    static RewriteRequest request(String sourceId, String... prohibited) {
        EvidenceScope scope = EvidenceScope.forBullet(SOURCES, sourceId).orElseThrow();
        return new RewriteRequest(sourceId, scope.sourceText(), scope, List.of(), List.of(),
                List.of(prohibited), scope.sourceText().length() * 2, false);
    }

    static RewriteRequest summaryRequest() {
        String text = RESUME.summaries().getFirst().text();
        EvidenceScope scope = EvidenceScope.forSummary(SOURCES, "default", text);
        return new RewriteRequest(scope.sourceId(), text, scope, List.of(), List.of(), List.of(),
                text.length() * 2, true);
    }
}
