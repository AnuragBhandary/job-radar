package com.anuragbhandary.jobradar.knowledge.experience;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.TestResumes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a drafted answer is not allowed to say.
 *
 * <p>The prompt asks a model not to invent experience. These tests are about the
 * part that finds out whether it did - because a prompt is a request, and the
 * output goes to an employer under his name and may be asked about in an
 * interview six weeks later.
 *
 * <p>Every rejected string here is one a fluent model would plausibly produce.
 * None of them fails a style rule; they fail because they are not true.
 */
class ClaimValidatorTest {

    private final ExperienceIndex index = new ExperienceIndex(TestResumes.backendResume());
    private final ExperiencePositioner positioner = new ExperiencePositioner(index);

    private final Positioning kubernetes = positioner.position("Kubernetes");
    private final Positioning kafka = positioner.position("Kafka");
    private final Positioning cobol = positioner.position("COBOL");

    // ------------------------------------------------------------------
    // What must be rejected
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "rejects: {0}")
    @ValueSource(strings = {
            "I have deployed Kubernetes clusters in production.",
            "I've used Kubernetes for two years.",
            "I built our Kubernetes setup from scratch.",
            "I managed Kubernetes at my last job.",
            "I ran Kubernetes in production and scaled it to forty nodes."})
    @DisplayName("a claim to have used the thing is refused when nothing supports it")
    void rejectsFabricatedUse(String draft) {
        ClaimValidator.Verdict verdict = ClaimValidator.check(draft, kubernetes, index);

        assertThat(verdict.acceptable()).isFalse();
        assertThat(verdict.problem()).isNotBlank();
    }

    @Test
    @DisplayName("an answer that never says he has not used it reads as saying he has")
    void rejectsAMissingDisclaimer() {
        ClaimValidator.Verdict verdict = ClaimValidator.check(
                "Kubernetes is something I would be comfortable working with, and I've "
                        + "worked with Docker.", kubernetes, index);

        assertThat(verdict.acceptable()).isFalse();
        assertThat(verdict.problem()).contains("never says you have not");
    }

    @ParameterizedTest(name = "rejects: {0}")
    @ValueSource(strings = {
            "I haven't worked directly with Kubernetes, but I have extensive Docker experience.",
            "I haven't used Kubernetes, though I'm an expert in Docker.",
            "I haven't used Kubernetes. I have 5 years with Docker.",
            "I haven't used Kubernetes, but I have advanced knowledge of containers."})
    @DisplayName("depth and duration are refused, however honest the opening was")
    void rejectsInflation(String draft) {
        assertThat(ClaimValidator.check(draft, kubernetes, index).acceptable()).isFalse();
    }

    @Test
    @DisplayName("a technology the evidence never mentioned cannot be brought in")
    void rejectsUncitedTechnologies() {
        // Fluent, honest about Kubernetes, and it invents Terraform and Helm.
        ClaimValidator.Verdict verdict = ClaimValidator.check(
                "I haven't worked with Kubernetes directly, but I've used Terraform and "
                        + "Helm to manage infrastructure.", kubernetes, index);

        assertThat(verdict.acceptable()).isFalse();
        assertThat(verdict.problem()).contains("terraform");
    }

    @Test
    @DisplayName("with no evidence at all, nothing may be offered as related")
    void rejectsReachingWhenThereIsNothing() {
        assertThat(ClaimValidator.check(
                "I haven't used COBOL, but I've worked with Java and Spring Boot.", cobol, index)
                .acceptable()).isFalse();
    }

    // ------------------------------------------------------------------
    // What must be allowed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the honest adjacent answer passes")
    void acceptsTheHonestAnswer() {
        ClaimValidator.Verdict verdict = ClaimValidator.check(
                "I haven't worked directly with Kubernetes yet. I've used Docker across "
                        + "my backend projects, so containers and how services are packaged "
                        + "and run are familiar ground, and I'd expect to get productive "
                        + "with it quickly.", kubernetes, index);

        assertThat(verdict.acceptable()).as(verdict.problem()).isTrue();
    }

    @Test
    @DisplayName("naming the subject is not the same as claiming it")
    void namingTheSubjectIsFine() {
        assertThat(ClaimValidator.check(
                "Kubernetes is not something I've worked with directly. Docker is.",
                kubernetes).acceptable()).isTrue();
    }

    @Test
    @DisplayName("a claim about something he really has used is allowed")
    void acceptsADirectClaim() {
        ClaimValidator.Verdict verdict = ClaimValidator.check(
                "I've worked with Kafka in production, building a replay pipeline behind "
                        + "a FastAPI service.", kafka, index);

        assertThat(verdict.acceptable()).as(verdict.problem()).isTrue();
    }

    @Test
    @DisplayName("saying he learns quickly is a preference, not a claim about the past")
    void acceptsTheLearningStatement() {
        assertThat(ClaimValidator.check(
                "I haven't used COBOL. I'm comfortable picking up unfamiliar technologies "
                        + "and would get up to speed on it.", cobol, index).acceptable()).isTrue();
    }

    @Test
    @DisplayName("an empty draft is refused rather than treated as a blank answer")
    void rejectsEmpty() {
        assertThat(ClaimValidator.check("", kubernetes, index).acceptable()).isFalse();
        assertThat(ClaimValidator.check(null, kubernetes, index).acceptable()).isFalse();
    }

    @Test
    @DisplayName("a direct answer is still refused for claiming years")
    void directAnswersCannotClaimYears() {
        assertThat(ClaimValidator.check(
                "I've worked with Kafka for three years.", kafka, index).acceptable()).isFalse();
    }
}
