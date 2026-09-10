package com.anuragbhandary.jobradar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.apply.TestProfiles;
import com.anuragbhandary.jobradar.apply.TestResumes;
import com.anuragbhandary.jobradar.apply.form.FieldClassifier;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Who ends up being asked, and who does not.
 *
 * <p>The rule this phase is built on has two halves and both are here. A
 * technology question must <em>not</em> reach him just because the exact word is
 * missing from his resume - that was the old behaviour and the reason for the
 * whole feature. And a question about a visa, a degree or an EEO declaration must
 * <em>always</em> reach him, because positioning is reasoning towards an answer
 * and those are not things to reason towards.
 */
class AnswerPlanTest {

    private final List<Assertion> stored = new ArrayList<>();
    private AnswerPlan plan;

    @BeforeEach
    void setUp() {
        stored.clear();
        SessionAnswers session = new SessionAnswers();
        session.clearAll();

        AssertionRepository repository = mock(AssertionRepository.class);
        when(repository.findByConceptIdAndSupersededByIdIsNull(anyString()))
                .thenAnswer(call -> stored.stream()
                        .filter(a -> a.getConceptId().equals(call.getArgument(0)))
                        .filter(Assertion::isLive)
                        .toList());

        var profile = TestProfiles.indianApplicant();
        KnowledgeResolver resolver = new KnowledgeResolver(repository,
                new ProfileFacts(profile), new Derivations(profile), session,
                new FieldClassifier());
        plan = new AnswerPlan(resolver,
                new ExperiencePositioner(new ExperienceIndex(TestResumes.backendResume())));
    }

    // ------------------------------------------------------------------
    // The point of the phase: technology questions do not interrupt him
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unfamiliar technology is drafted, not handed back to him")
    void kubernetesDoesNotReachHim() {
        AnswerPlan.Plan route = plan.planFor(Concept.UNRECOGNISED,
                "Do you have experience with Kubernetes?", Contexts.germanyOnsite(), true);

        assertThat(route.route()).isEqualTo(AnswerRoute.AI_PROPOSE);
        assertThat(route.isPositioned()).isTrue();
        assertThat(route.positioning().level()).isEqualTo(ExperienceLevel.ADJACENT);
        // And the reason is checkable rather than a verdict he has to trust.
        assertThat(route.because()).contains("Docker");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "Do you have experience with Kubernetes?",
            "Have you worked with Terraform?",
            "How familiar are you with GraphQL?",
            "Describe your experience with Elasticsearch.",
            "Are you comfortable with TypeScript?",
            "Hands-on experience with RabbitMQ?"})
    @DisplayName("every shape of technology question is answered rather than asked")
    void technologyQuestionsAreAnswered(String question) {
        AnswerPlan.Plan route = plan.planFor(Concept.UNRECOGNISED, question,
                Contexts.germanyOnsite(), true);

        assertThat(route.route()).isEqualTo(AnswerRoute.AI_PROPOSE);
        assertThat(route.isPositioned()).isTrue();
    }

    @Test
    @DisplayName("even a technology he has never met is drafted rather than asked")
    void anUnknownTechnologyIsStillNotHisProblem() {
        AnswerPlan.Plan route = plan.planFor(Concept.UNRECOGNISED,
                "Do you have experience with COBOL?", Contexts.germanyOnsite(), true);

        assertThat(route.route()).isEqualTo(AnswerRoute.AI_PROPOSE);
        assertThat(route.positioning().level()).isEqualTo(ExperienceLevel.NONE);
    }

    // ------------------------------------------------------------------
    // The other half: what must always reach him
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} is never positioned")
    @ValueSource(strings = {
            "eeo.gender", "eeo.race", "eeo.veteran", "eeo.disability", "consent"})
    @DisplayName("a regulated declaration is never reasoned towards")
    void sensitiveConceptsAreNeverPositioned(String conceptId) {
        Concept concept = Concepts.byId(conceptId).orElseThrow();

        assertThat(concept.category()).isEqualTo(Concept.Category.SENSITIVE);
        assertThat(concept.allowsExperiencePositioning()).isFalse();
    }

    @Test
    @DisplayName("a question about a work permit never reaches the positioner")
    void workAuthorisationIsNeverAnExperienceQuestion() {
        // It contains "experience" in some wordings and is emphatically not this.
        AnswerPlan.Plan route = plan.planFor(Concepts.WORK_AUTHORISATION,
                "Do you have experience working in Germany legally?",
                Contexts.germanyOnsite(), true);

        assertThat(route.isPositioned()).isFalse();
        // And it is answered deterministically rather than asked at all.
        assertThat(route.route()).isEqualTo(AnswerRoute.AUTO_RESOLVE);
    }

    @ParameterizedTest(name = "{0} is not a technology question")
    @ValueSource(strings = {
            "How many years of experience do you have with Java?",
            "Do you require visa sponsorship?",
            "Are you legally authorised to work in Germany?",
            "What are your salary expectations?",
            "Are you willing to relocate?",
            "Do you have experience of any criminal convictions?"})
    @DisplayName("questions that merely contain the word experience are left alone")
    void lookalikesAreRefused() {
        // Checked at the detector, so nothing downstream has to remember.
        for (String question : List.of(
                "How many years of experience do you have with Java?",
                "Do you require visa sponsorship?",
                "Are you legally authorised to work in Germany?",
                "What are your salary expectations?",
                "Are you willing to relocate?")) {
            assertThat(com.anuragbhandary.jobradar.knowledge.experience.ExperienceQuestion
                    .subjectOf(question))
                    .as(question)
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Everything the system already knows
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} fills itself")
    @ValueSource(strings = {"identity.first_name", "identity.last_name", "contact.email",
            "address.city", "link.linkedin", "notice_period"})
    @DisplayName("a fact in the profile never becomes a question")
    void profileFactsFillThemselves(String conceptId) {
        Concept concept = Concepts.byId(conceptId).orElseThrow();

        AnswerPlan.Plan route = plan.planFor(concept, concept.label(),
                Contexts.germanyOnsite(), true);

        assertThat(route.route()).isEqualTo(AnswerRoute.AUTO_RESOLVE);
    }

    @Test
    @DisplayName("sponsorship stays deterministic and country-aware, in both directions")
    void sponsorshipIsDerivedNotDrafted() {
        AnswerPlan.Plan german = plan.planFor(Concepts.SPONSORSHIP_REQUIRED,
                "Will you require visa sponsorship?", Contexts.germanyOnsite(), true);
        AnswerPlan.Plan indian = plan.planFor(Concepts.SPONSORSHIP_REQUIRED,
                "Will you require visa sponsorship?", Contexts.indiaOnsite(), true);

        assertThat(german.route()).isEqualTo(AnswerRoute.AUTO_RESOLVE);
        assertThat(german.resolution().value()).isEqualTo("Yes");
        assertThat(german.isPositioned()).isFalse();

        assertThat(indian.route()).isEqualTo(AnswerRoute.AUTO_RESOLVE);
        assertThat(indian.resolution().value()).isEqualTo("No");
        assertThat(indian.isPositioned()).isFalse();
    }

    @Test
    @DisplayName("a US employer hiring into India needs no sponsorship, and is not drafted")
    void remoteFromIndiaIsDerived() {
        AnswerPlan.Plan route = plan.planFor(Concepts.SPONSORSHIP_REQUIRED,
                "Will you require sponsorship?", Contexts.usRemoteFromIndia(), true);

        assertThat(route.route()).isEqualTo(AnswerRoute.AUTO_RESOLVE);
        assertThat(route.resolution().value()).isEqualTo("No");
    }

    @Test
    @DisplayName("a voluntary question is answered by declining, not by asking")
    void voluntaryQuestionsDecline() {
        AnswerPlan.Plan route = plan.planFor(Concepts.PRONOUNS, "Pronouns",
                Contexts.germanyOnsite(), false);

        assertThat(route.route()).isIn(AnswerRoute.AUTO_RESOLVE, AnswerRoute.NOT_APPLICABLE);
    }

    // ------------------------------------------------------------------
    // What is left
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a required factual question with no answer is still his")
    void genuineGapsStillReachHim() {
        AnswerPlan.Plan route = plan.planFor(Concept.UNRECOGNISED,
                "What is your father's maiden name?", Contexts.germanyOnsite(), true);

        assertThat(route.route()).isEqualTo(AnswerRoute.USER_REQUIRED);
        assertThat(route.isPositioned()).isFalse();
    }

    @Test
    @DisplayName("two saved answers that disagree are never resolved by picking one")
    void conflictsReachHim() {
        stored.add(approved(Concepts.SPONSORSHIP_REQUIRED, "Yes", Scope.country("DE")));
        stored.add(approved(Concepts.SPONSORSHIP_REQUIRED, "No", Scope.country("DE")));

        AnswerPlan.Plan route = plan.planFor(Concepts.SPONSORSHIP_REQUIRED,
                "Will you require sponsorship?", Contexts.germanyOnsite(), true);

        assertThat(route.route()).isEqualTo(AnswerRoute.USER_REQUIRED);
        assertThat(route.because()).contains("disagree");
    }

    @Test
    @DisplayName("an optional question nothing answers is skipped rather than asked")
    void optionalGapsAreSkipped() {
        AnswerPlan.Plan route = plan.planFor(Concept.UNRECOGNISED,
                "What is your father's maiden name?", Contexts.germanyOnsite(), false);

        assertThat(route.route()).isEqualTo(AnswerRoute.NOT_APPLICABLE);
    }

    private static Assertion approved(Concept concept, String value, Scope scope) {
        Assertion assertion = new Assertion(concept.id(), value, scope,
                KnowledgeSource.USER_RULE);
        assertion.approve("test");
        return assertion;
    }
}
