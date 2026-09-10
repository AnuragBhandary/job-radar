package com.anuragbhandary.jobradar.knowledge.experience;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.TestResumes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What may honestly be said about a technology.
 *
 * <p>Against a resume with the same technologies as the real one, because the
 * question these tests answer is whether the verdicts are <em>right</em> for the
 * evidence he actually has - and a fixture of invented skills would only prove
 * that the code runs.
 *
 * <p>The three that matter: Kafka is his and must be answerable directly;
 * Kubernetes is not his and must reach Docker rather than reaching him; COBOL is
 * nowhere near him and must say so rather than stretching for a connection.
 */
class ExperiencePositionerTest {

    private final ExperiencePositioner positioner =
            new ExperiencePositioner(new ExperienceIndex(TestResumes.backendResume()));

    // ------------------------------------------------------------------
    // Direct
    // ------------------------------------------------------------------

    @Test
    @DisplayName("something he has used is direct, and cites where he used it")
    void kafkaIsDirect() {
        Positioning position = positioner.position("Kafka");

        assertThat(position.level()).isEqualTo(ExperienceLevel.DIRECT);
        assertThat(position.isDirect()).isTrue();
        assertThat(position.needsDisclaimer()).isFalse();
        // Professional work outranks the project and the skills line, and the
        // strongest evidence is what an answer should lead with.
        assertThat(position.evidence().getFirst().depth())
                .isEqualTo(ExperienceEvidence.Depth.PROFESSIONAL);
    }

    @ParameterizedTest(name = "{0} is his")
    @ValueSource(strings = {"Java", "Spring Boot", "PostgreSQL", "Redis", "Docker",
            "FastAPI", "WebSockets", "Python"})
    @DisplayName("every technology on his resume answers directly")
    void hisOwnTechnologiesAreDirect(String technology) {
        assertThat(positioner.position(technology).level())
                .isEqualTo(ExperienceLevel.DIRECT);
    }

    @Test
    @DisplayName("a direct answer may not claim years or seniority even so")
    void directIsStillBounded() {
        Positioning position = positioner.position("Kafka");

        assertThat(position.mustNotSay()).anyMatch(claim -> claim.contains("years"));
        assertThat(position.mustNotSay()).anyMatch(claim -> claim.contains("expert"));
    }

    // ------------------------------------------------------------------
    // Adjacent - the case this phase exists for
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Kubernetes is adjacent through Docker, and never claims to be his")
    void kubernetesReachesDockerRatherThanHim() {
        Positioning position = positioner.position("Kubernetes");

        assertThat(position.level()).isEqualTo(ExperienceLevel.ADJACENT);
        assertThat(position.isDirect()).isFalse();
        assertThat(position.needsDisclaimer()).isTrue();
        assertThat(position.namedEvidence())
                .anyMatch(name -> name.toLowerCase().contains("docker"));
        // The whole point: the answer opens by saying he has not used it.
        assertThat(position.mayClaim())
                .anyMatch(claim -> claim.contains("not worked with Kubernetes directly"));
        assertThat(position.mustNotSay())
                .anyMatch(claim -> claim.contains("you have used Kubernetes"));
    }

    @Test
    @DisplayName("Terraform is adjacent through AWS")
    void terraformReachesAws() {
        Positioning position = positioner.position("Terraform");

        assertThat(position.level()).isEqualTo(ExperienceLevel.ADJACENT);
        assertThat(position.via()).contains("aws");
    }

    @Test
    @DisplayName("RabbitMQ is adjacent through Kafka, which he does have")
    void rabbitmqReachesKafka() {
        Positioning position = positioner.position("RabbitMQ");

        assertThat(position.level()).isEqualTo(ExperienceLevel.ADJACENT);
        assertThat(position.namedEvidence())
                .anyMatch(name -> name.toLowerCase().contains("kafka"));
    }

    @Test
    @DisplayName("an answer may name at most three technologies")
    void evidenceIsCapped() {
        // Java touches most of the graph. An answer listing eight of his
        // technologies reads as a search of his resume rather than as a person.
        assertThat(positioner.position("Kotlin").evidence()).hasSizeLessThanOrEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Conceptual
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a question naming an idea rather than a tool finds the tools he has")
    void containerOrchestrationFindsDocker() {
        Positioning position = positioner.position("container orchestration");

        assertThat(position.level()).isIn(ExperienceLevel.ADJACENT,
                ExperienceLevel.CONCEPTUAL);
        assertThat(position.namedEvidence())
                .anyMatch(name -> name.toLowerCase().contains("docker"));
        assertThat(position.needsDisclaimer()).isTrue();
    }

    // ------------------------------------------------------------------
    // None
    // ------------------------------------------------------------------

    @Test
    @DisplayName("something genuinely unrelated says so, and reaches for nothing")
    void cobolIsNone() {
        Positioning position = positioner.position("COBOL");

        assertThat(position.level()).isEqualTo(ExperienceLevel.NONE);
        assertThat(position.evidence()).isEmpty();
        assertThat(position.via()).isEmpty();
        // Still answerable - "not yet, and I pick things up quickly" is true -
        // but with nothing invented to fill the gap.
        assertThat(position.mayClaim())
                .anyMatch(claim -> claim.contains("comfortable picking up"));
        assertThat(position.mustNotSay())
                .anyMatch(claim -> claim.contains("any technology at all"));
    }

    @Test
    @DisplayName("an invented technology is NONE rather than a guess")
    void inventedTechnologyIsNone() {
        assertThat(positioner.position("Fluxonium").level())
                .isEqualTo(ExperienceLevel.NONE);
        assertThat(positioner.position("Zorbtrix DB").evidence()).isEmpty();
    }

    @Test
    @DisplayName("nothing and null are handled without an exception")
    void emptyInputIsSafe() {
        assertThat(positioner.position(null).level()).isEqualTo(ExperienceLevel.NONE);
        assertThat(positioner.position("  ").level()).isEqualTo(ExperienceLevel.NONE);
    }

    // ------------------------------------------------------------------
    // The invariant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("positioning the same thing twice gives the same verdict")
    void positioningIsDeterministic() {
        assertThat(positioner.position("Kubernetes").describe())
                .isEqualTo(positioner.position("Kubernetes").describe());
    }

    @Test
    @DisplayName("only DIRECT ever licenses a claim of having used the thing")
    void onlyDirectLicensesAClaim() {
        for (ExperienceLevel level : ExperienceLevel.values()) {
            assertThat(level.allowsDirectClaim())
                    .as(level + " licences a direct claim")
                    .isEqualTo(level == ExperienceLevel.DIRECT);
            assertThat(level.requiresDisclaimer())
                    .isEqualTo(level != ExperienceLevel.DIRECT);
        }
    }
}
