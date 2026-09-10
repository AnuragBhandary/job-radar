package com.anuragbhandary.jobradar.knowledge.experience;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.TestResumes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What the system knows without being told.
 *
 * <p>The claim this phase makes is that a form should never ask him about Java,
 * Kafka or Docker, because his own resume already says so. These tests are that
 * claim, checked - and the deduplication rule with it, because "Java" appears in
 * a skills group, three project stacks and a dozen bullets and has to come out as
 * one thing with twelve citations rather than twelve things.
 */
class ExperienceIndexTest {

    private final ExperienceIndex index = new ExperienceIndex(TestResumes.backendResume());

    @ParameterizedTest(name = "knows {0}")
    @ValueSource(strings = {"java", "python", "kafka", "spring boot", "fastapi",
            "postgresql", "redis", "docker", "aws", "microservices",
            "distributed systems", "websockets", "junit", "sqlite"})
    @DisplayName("everything his resume names is already known")
    void knowsWhatTheResumeNames(String technology) {
        assertThat(index.has(technology)).as(technology).isTrue();
    }

    @ParameterizedTest(name = "does not invent {0}")
    @ValueSource(strings = {"kubernetes", "terraform", "cobol", "rabbitmq", "graphql",
            "mongodb", "elasticsearch", "ansible"})
    @DisplayName("nothing his resume does not name is invented")
    void inventsNothing(String technology) {
        assertThat(index.has(technology)).as(technology).isFalse();
    }

    @Test
    @DisplayName("one technology, however many places name it")
    void mentionsAreMergedNotDuplicated() {
        // Java: the skills group, two project stacks, two project tag lists.
        ExperienceIndex.Entry java = index.find("java").orElseThrow();

        assertThat(java.term()).isEqualTo("java");
        assertThat(java.evidence()).hasSizeGreaterThan(1);
        // Every citation is a different place. Two entries for one project - its
        // stack line and one of its bullets - is the same project twice.
        assertThat(java.evidence().stream()
                .map(item -> item.depth() + "@" + item.where()).distinct().count())
                .isEqualTo(java.evidence().size());
    }

    @Test
    @DisplayName("professional work outranks a project, which outranks a skills line")
    void depthIsRanked() {
        // Kafka is in a bullet describing paid work as well as in two projects.
        assertThat(index.find("kafka").orElseThrow().depth())
                .isEqualTo(ExperienceEvidence.Depth.PROFESSIONAL);
        assertThat(index.find("kafka").orElseThrow().isProfessional()).isTrue();

        // SQLite is only in the skills list, and says so.
        assertThat(index.find("sqlite").orElseThrow().depth())
                .isEqualTo(ExperienceEvidence.Depth.LISTED);
    }

    @Test
    @DisplayName("a skills entry written with brackets still indexes cleanly")
    void bracketedSkillsAreReadable() {
        // His file writes "AWS (EC2, S3)" as a YAML flow sequence, which arrives
        // already split into "AWS (EC2" and "S3)". The term has to be usable in a
        // sentence regardless.
        assertThat(index.has("aws")).isTrue();
        assertThat(index.terms()).noneMatch(term -> term.contains("(") || term.contains(")"));
    }

    @Test
    @DisplayName("an empty resume produces an empty index rather than an exception")
    void noResumeIsSafe() {
        ExperienceIndex empty = new ExperienceIndex(null);

        assertThat(empty.size()).isZero();
        assertThat(empty.has("java")).isFalse();
        assertThat(empty.find("java")).isEmpty();
    }

    @Test
    @DisplayName("the graph is symmetric, so which of two names a form uses does not matter")
    void theGraphIsSymmetric() {
        assertThat(SkillGraph.neighbours("kubernetes")).contains("docker");
        assertThat(SkillGraph.neighbours("docker")).contains("kubernetes");
        assertThat(SkillGraph.neighbours("kafka")).contains("rabbitmq");
        assertThat(SkillGraph.neighbours("rabbitmq")).contains("kafka");
    }
}
