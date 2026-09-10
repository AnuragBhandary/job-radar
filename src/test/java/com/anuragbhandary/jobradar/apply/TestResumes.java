package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import java.util.List;

/**
 * A resume with the same shape as the real one, and none of the person in it.
 *
 * <p>Built from the technologies the applicant's own resume actually carries -
 * Java, Spring Boot, Kafka, FastAPI, PostgreSQL, Redis, Docker, AWS - because the
 * experience tests are about what the system does with real evidence and a
 * fixture of invented skills would prove nothing about that.
 *
 * <p>The employer names, dates and metrics are not here. The repository is public
 * and the tests do not need them: an adjacency verdict turns on the technology
 * and on whether it appeared in work or in a project, and both survive the names
 * being placeholders.
 *
 * <h2>The three cases it is built to exercise</h2>
 * Kafka is professional and in a project, so it is DIRECT with the strongest kind
 * of evidence. Docker and AWS are projects and a skills list, so Kubernetes and
 * Terraform have somewhere to be ADJACENT from. And nothing here is anywhere near
 * COBOL, which is how NONE gets tested against something real.
 */
public final class TestResumes {

    private TestResumes() {
    }

    public static ResumeModel backendResume() {
        return new ResumeModel(
                "Backend Engineer",
                List.of(new ResumeModel.Summary("distributed",
                        List.of("java", "kafka"), "Backend engineer.")),
                List.of(
                        new ResumeModel.SkillGroup("Languages",
                                List.of("Python", "Java", "SQL", "JavaScript")),
                        new ResumeModel.SkillGroup("Backend & APIs",
                                List.of("Spring Boot", "FastAPI", "Flask", "REST APIs",
                                        "WebSockets", "Apache Kafka")),
                        new ResumeModel.SkillGroup("Databases",
                                List.of("PostgreSQL", "MySQL", "SQLite", "Redis")),
                        new ResumeModel.SkillGroup("Architecture",
                                List.of("Microservices", "Event-Driven Systems",
                                        "Distributed Systems", "API Design")),
                        // Written the way his own file writes it, brackets and
                        // all, because the indexing has to survive that.
                        new ResumeModel.SkillGroup("Cloud & DevOps",
                                List.of("AWS (EC2, S3)", "Docker", "Git", "Linux"))),
                // A real period range: the years derivation counts the months
                // between the two ends, and a bare year gives it nothing to
                // count.
                List.of(new ResumeModel.Job("An employer", "Backend Engineer",
                        "Remote", "Jul 2025 - Jun 2026", null,
                        List.of(new ResumeModel.Bullet(
                                        "Built a Kafka-based replay pipeline behind a "
                                                + "FastAPI service.",
                                        List.of("kafka", "fastapi")),
                                new ResumeModel.Bullet(
                                        "Worked on real-time delivery over WebSockets.",
                                        List.of("websockets"))))),
                List.of(
                        new ResumeModel.Project("Distributed Job Scheduler",
                                "Java · Spring Boot · PostgreSQL · Redis · Kafka · "
                                        + "JUnit · Maven · Docker",
                                List.of("java", "spring boot", "distributed systems",
                                        "kafka", "redis", "postgresql", "docker"),
                                List.of(new ResumeModel.Bullet(
                                        "Scheduled work across workers with Redis leases.",
                                        List.of("redis")))),
                        new ResumeModel.Project("Compliance Document Processing",
                                "Python · FastAPI · PostgreSQL · Redis · Kafka · Docker",
                                List.of("python", "fastapi", "postgresql", "redis",
                                        "kafka", "docker", "asynchronous"),
                                List.of(new ResumeModel.Bullet(
                                        "Processed documents asynchronously.",
                                        List.of("asynchronous"))))),
                List.of(new ResumeModel.Education("M.S. Computer Science",
                        "A university", "Somewhere", "2023-2025", "Focus: software")),
                List.of(),
                3, 4, 3);
    }
}
