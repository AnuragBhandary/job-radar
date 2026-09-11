package com.anuragbhandary.jobradar.evidence;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import java.util.List;

/**
 * A resume and the evidence bank that matches it, both invented.
 *
 * <p>Shaped like the real ones - a job with a measured result, a shared piece of
 * work, a tag naming a technology he only delivered to (React), projects with stack
 * lines - and carrying none of the person. The repository is public.
 *
 * <p>The cases it is built for: Kafka evidence in both a job and a project, so
 * ranking has something to decide; two approved wordings and one unapproved one of
 * the same claim; a qualifier that scopes shared work; three projects against a cap
 * of two; and more bullets than the caps allow, so selection is visible.
 */
public final class EvidenceFixtures {

    private EvidenceFixtures() {
    }

    public static final String REPLAY = "Built a Kafka-based event replay service handling 12 streams "
            + "and approximately 3,000 messages with deduplication and ordering preservation.";
    public static final String CACHE = "Reduced report generation time roughly fourfold by caching "
            + "intermediate results in Redis.";
    public static final String INGEST = "Developed the ingestion side of a FastAPI service streaming "
            + "updates over WebSockets to React clients.";
    public static final String RUNBOOKS = "Wrote runbooks for the on-call rotation.";
    public static final String SCHED_API = "Built a job-scheduling backend exposing REST APIs for "
            + "creating and cancelling jobs.";
    public static final String SCHED_PERSIST = "Persisted execution history with Spring Data JPA and "
            + "PostgreSQL, tested with JUnit.";
    public static final String SCHED_CONTAINERS = "Packaged the services as containers for local deployment.";
    public static final String DOCS_ASYNC = "Processed uploaded documents asynchronously through "
            + "Kafka-backed workers.";
    public static final String SITE = "Built a static portfolio site.";

    /** Approved, emphasising event-driven. */
    public static final String EVENTS_FIRST = "Built event-driven replay on Kafka for 12 streams and "
            + "approximately 3,000 messages, with deduplication and ordering preservation.";
    /** Approved, emphasising deduplication and ordering. */
    public static final String DEDUP_FIRST = "Deduplicated and preserved the ordering of approximately "
            + "3,000 messages across 12 streams in a Kafka-based event replay service.";
    /** Valid, and never approved: must never be printed. */
    public static final String UNAPPROVED = "Built a Kafka replay service for 12 streams and "
            + "approximately 3,000 messages with deduplication and ordering preservation.";
    /** Approved, emphasising Java and Spring Boot. */
    public static final String JAVA_FIRST = "Built a Java and Spring Boot job-scheduling backend "
            + "exposing REST APIs for creating and cancelling jobs.";

    public static ResumeModel resume() {
        return new ResumeModel(
                "Backend Engineer",
                List.of(new ResumeModel.Summary("default", List.of(), "Backend engineer."),
                        new ResumeModel.Summary("java", List.of("java", "spring boot"),
                                "Java backend engineer.")),
                List.of(new ResumeModel.SkillGroup("Languages", List.of("Python", "Java", "SQL")),
                        new ResumeModel.SkillGroup("Backend",
                                List.of("Spring Boot", "FastAPI", "Apache Kafka", "WebSockets")),
                        new ResumeModel.SkillGroup("Data", List.of("PostgreSQL", "Redis")),
                        // Split the way Spring binds "AWS (EC2, S3)" from YAML.
                        new ResumeModel.SkillGroup("Cloud & DevOps", List.of("AWS (EC2", "S3)", "Docker"))),
                List.of(new ResumeModel.Job("Acme Streaming", "Software Engineer", "Remote",
                        "Jan 2025 - Dec 2025", "contract",
                        List.of(new ResumeModel.Bullet(REPLAY, List.of("kafka", "event-driven")),
                                new ResumeModel.Bullet(CACHE, List.of("redis", "performance")),
                                new ResumeModel.Bullet(INGEST,
                                        List.of("fastapi", "websockets", "python", "react")),
                                new ResumeModel.Bullet(RUNBOOKS, List.of("on-call"))))),
                List.of(new ResumeModel.Project("Job Scheduler",
                                "Java · Spring Boot · PostgreSQL · Redis · Docker",
                                List.of("java", "spring boot", "postgresql", "redis", "docker"),
                                List.of(new ResumeModel.Bullet(SCHED_API, List.of("java", "spring boot", "rest")),
                                        new ResumeModel.Bullet(SCHED_PERSIST, List.of("jpa", "postgresql", "junit")),
                                        new ResumeModel.Bullet(SCHED_CONTAINERS, List.of("docker")))),
                        new ResumeModel.Project("Document Pipeline", "Python · FastAPI · Kafka",
                                List.of("python", "fastapi", "kafka"),
                                List.of(new ResumeModel.Bullet(DOCS_ASYNC,
                                        List.of("python", "kafka", "asynchronous")))),
                        new ResumeModel.Project("Static Site", "HTML · CSS", List.of("html"),
                                List.of(new ResumeModel.Bullet(SITE, List.of("html"))))),
                List.of(new ResumeModel.Education("M.S. Computer Science", "A University",
                        "Somewhere", "2023 - 2025", "GPA 3.5")),
                List.of("Open-source contributions."),
                2, 3, 2);
    }

    public static final String SOURCES_YAML = """
            version: 1
            sources:
              - id: acme
                kind: employment
                name: Acme Streaming
                stack: [Python, FastAPI, Kafka, WebSockets, Redis]
              - id: scheduler
                kind: project
                name: Job Scheduler
                stack: [Java, Spring Boot, PostgreSQL, Redis, Docker]
              - id: docs
                kind: project
                name: Document Pipeline
                stack: [Python, FastAPI, Kafka]
              - id: site
                kind: project
                name: Static Site
            """;

    public static final String ITEMS_YAML = """
            items:
              - id: acme-replay
                source: acme
                claim: "{REPLAY}"
                technologies: [Kafka]
                concepts: [event-driven, event replay, deduplication, ordering, streaming]
                metrics: ["12 streams", "approximately 3,000 messages"]
                categories: [backend, distributed-systems]
                strength: high
                variants:
                  - id: events-first
                    text: "{EVENTS_FIRST}"
                    emphasis: [event-driven]
                    approved: true
                  - id: dedup-first
                    text: "{DEDUP_FIRST}"
                    emphasis: [deduplication, ordering]
                    approved: true
                  - id: proposed
                    text: "{UNAPPROVED}"
                    emphasis: [kafka]
              - id: acme-cache
                source: acme
                claim: "{CACHE}"
                technologies: [Redis]
                concepts: [performance optimisation, caching]
                metrics: [roughly fourfold]
                categories: [backend, performance]
                strength: high
              - id: acme-ingest
                source: acme
                claim: "{INGEST}"
                technologies: [FastAPI, WebSockets, Python]
                context-only: [React]
                concepts: [real-time systems, streaming, api design]
                qualifiers: [the ingestion side of]
                attribution: shared
                categories: [backend, real-time]
              - id: acme-runbooks
                source: acme
                claim: "{RUNBOOKS}"
                concepts: [on-call, runbooks]
                strength: low
              - id: sched-api
                source: scheduler
                claim: "{SCHED_API}"
                technologies: [Java, Spring Boot]
                concepts: [REST APIs, job scheduling, api design]
                categories: [backend, java-backend]
                variants:
                  - id: java-first
                    text: "{JAVA_FIRST}"
                    emphasis: [java, spring boot]
                    approved: true
              - id: sched-persist
                source: scheduler
                claim: "{SCHED_PERSIST}"
                technologies: [Spring Data, JPA, PostgreSQL, JUnit]
                concepts: [persistence, testing]
              - id: sched-containers
                source: scheduler
                claim: "{SCHED_CONTAINERS}"
                technologies: [Docker]
                concepts: [containerisation]
              - id: docs-async
                source: docs
                claim: "{DOCS_ASYNC}"
                technologies: [Python, Kafka]
                concepts: [asynchronous processing, workers]
              - id: site-static
                source: site
                claim: "{SITE}"
                strength: low
            """
            .replace("{REPLAY}", REPLAY)
            .replace("{EVENTS_FIRST}", EVENTS_FIRST)
            .replace("{DEDUP_FIRST}", DEDUP_FIRST)
            .replace("{UNAPPROVED}", UNAPPROVED)
            .replace("{CACHE}", CACHE)
            .replace("{INGEST}", INGEST)
            .replace("{RUNBOOKS}", RUNBOOKS)
            .replace("{SCHED_API}", SCHED_API)
            .replace("{JAVA_FIRST}", JAVA_FIRST)
            .replace("{SCHED_PERSIST}", SCHED_PERSIST)
            .replace("{SCHED_CONTAINERS}", SCHED_CONTAINERS)
            .replace("{DOCS_ASYNC}", DOCS_ASYNC)
            .replace("{SITE}", SITE);

    public static final String BANK_YAML = SOURCES_YAML + ITEMS_YAML;

    public static EvidenceBank bank() {
        return EvidenceBankLoader.parse(BANK_YAML, "fixture");
    }

    public static EvidenceBank bank(String yaml) {
        return EvidenceBankLoader.parse(yaml, "fixture");
    }

    public static List<String> errors(EvidenceBank bank) {
        return bank.problems().stream().filter(EvidenceProblem::isError)
                .map(EvidenceProblem::toString).toList();
    }
}
