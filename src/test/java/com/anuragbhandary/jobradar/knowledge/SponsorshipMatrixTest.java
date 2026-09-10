package com.anuragbhandary.jobradar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.apply.TestProfiles;
import com.anuragbhandary.jobradar.apply.form.FieldClassifier;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.WorkMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sponsorship across every combination that changes the answer.
 *
 * <p>The six cases in {@code SponsorshipDerivationTest} prove the derivation.
 * These go through the whole resolver, with stored rules in play, because the
 * dangerous version of this bug is not a wrong derivation - it is a right
 * derivation quietly overruled by a rule learned somewhere else.
 *
 * <p>The rule the matrix exists to hold: <b>the employer's country never decides
 * this on its own.</b> A German company can employ him in Mumbai and a US company
 * can require him in New York, and the only thing that settles it is where the
 * employee will actually be.
 */
class SponsorshipMatrixTest {

    private final List<Assertion> stored = new ArrayList<>();
    private final SessionAnswers session = new SessionAnswers();
    private KnowledgeResolver resolver;

    @BeforeEach
    void setUp() {
        stored.clear();
        session.clearAll();
        resolver = resolverFor(TestProfiles.indianApplicant());
    }

    private KnowledgeResolver resolverFor(ApplicantProfile profile) {
        AssertionRepository repository = mock(AssertionRepository.class);
        when(repository.findByConceptIdAndSupersededByIdIsNull(anyString()))
                .thenAnswer(call -> stored.stream()
                        .filter(a -> a.getConceptId().equals(call.getArgument(0)))
                        .filter(Assertion::isLive)
                        .toList());
        return new KnowledgeResolver(repository, new ProfileFacts(profile),
                new Derivations(profile), session, new FieldClassifier());
    }

    private String sponsorship(ApplicationContext context) {
        return resolver.resolve(Concepts.SPONSORSHIP_REQUIRED, context).value();
    }

    private void rule(String value, Scope scope) {
        Assertion assertion = new Assertion(Concepts.SPONSORSHIP_REQUIRED.id(), value, scope,
                KnowledgeSource.USER_RULE);
        assertion.approve("test");
        stored.add(assertion);
    }

    // ------------------------------------------------------------------
    // Employer country never decides it on its own
    // ------------------------------------------------------------------

    @Test
    @DisplayName("US employer, employee in India: no sponsorship")
    void usEmployerIndianEmployment() {
        assertThat(sponsorship(Contexts.usRemoteFromIndia())).isEqualTo("No");
    }

    @Test
    @DisplayName("US employer, employee in the US: sponsorship")
    void usEmployerUsEmployment() {
        assertThat(sponsorship(Contexts.usOnsite())).isEqualTo("Yes");
    }

    @Test
    @DisplayName("German employer hiring into India: no sponsorship")
    void germanEmployerIndianEmployment() {
        // The mirror image of the US case, and the one that catches a resolver
        // that has quietly started reading the employer's country.
        ApplicationContext context = Contexts.builder()
                .company("Camunda").country("DE").employer("DE")
                .mode(WorkMode.REMOTE_GLOBAL).eligibleFrom("IN")
                .lane(StrategicClass.INTERNATIONAL_REMOTE).build();
        assertThat(context.employmentCountryCode()).isEqualTo("IN");
        assertThat(sponsorship(context)).isEqualTo("No");
    }

    @Test
    @DisplayName("German employer, employee in Germany: sponsorship")
    void germanEmployerGermanEmployment() {
        assertThat(sponsorship(Contexts.germanyOnsite())).isEqualTo("Yes");
    }

    @Test
    @DisplayName("Indian employer, employee in India: no sponsorship")
    void indianEmployerIndianEmployment() {
        assertThat(sponsorship(Contexts.indiaOnsite())).isEqualTo("No");
    }

    @Test
    @DisplayName("an Indian employer posting a German role still needs sponsorship")
    void indianEmployerForeignEmployment() {
        ApplicationContext context = Contexts.builder()
                .company("Infosys").country("DE").employer("IN")
                .mode(WorkMode.ONSITE).lane(StrategicClass.INTERNATIONAL_RELOCATION).build();
        assertThat(sponsorship(context)).isEqualTo("Yes");
    }

    @Test
    @DisplayName("a foreign employer with no stated remote scope is never assumed either way")
    void foreignEmployerUnstatedScope() {
        ApplicationContext context = Contexts.builder()
                .company("PostHog").country(null).employer("US")
                .mode(WorkMode.REMOTE_UNSPECIFIED)
                .lane(StrategicClass.INTERNATIONAL_REMOTE).build();
        Resolution resolution = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED, context);
        assertThat(resolution.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(resolution.isAutoFillable()).isFalse();
    }

    // ------------------------------------------------------------------
    // Work authorisation the candidate actually holds
    // ------------------------------------------------------------------

    @Test
    @DisplayName("authorised in Germany: a German job needs no sponsorship")
    void authorisedInGermany() {
        KnowledgeResolver withPermit = resolverFor(authorisedIn(Country.INDIA, Country.GERMANY));
        ApplicationContext german = Contexts.builder()
                .company("Camunda").country("DE").employer("DE").mode(WorkMode.ONSITE)
                .lane(StrategicClass.INTERNATIONAL_RELOCATION)
                .authorisedIn("IN", "DE").build();
        assertThat(withPermit.resolve(Concepts.SPONSORSHIP_REQUIRED, german).value())
                .isEqualTo("No");
    }

    @Test
    @DisplayName("not authorised in Germany: the same job needs sponsorship")
    void notAuthorisedInGermany() {
        assertThat(sponsorship(Contexts.germanyOnsite())).isEqualTo("Yes");
    }

    // ------------------------------------------------------------------
    // Rules over derivations, and scope over everything
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a country rule overrides the derivation for that country only")
    void countryRuleOverridesDerivation() {
        rule("No", Scope.country("DE"));

        assertThat(sponsorship(Contexts.germanyOnsite())).isEqualTo("No");
        // And leaves every other country to the derivation.
        assertThat(sponsorship(Contexts.usOnsite())).isEqualTo("Yes");
        assertThat(sponsorship(Contexts.indiaOnsite())).isEqualTo("No");
    }

    @Test
    @DisplayName("a strategic-class rule covers a lane without touching the others")
    void strategicClassRule() {
        // "For anything that means moving abroad, I need sponsorship" - the rule
        // the knowledge review page offers for the migrated sponsorship answers.
        rule("Yes", Scope.of(Scope.Level.STRATEGIC_CLASS,
                StrategicClass.INTERNATIONAL_RELOCATION.name()));

        assertThat(sponsorship(Contexts.germanyOnsite())).isEqualTo("Yes");
        assertThat(sponsorship(Contexts.usOnsite())).isEqualTo("Yes");
        assertThat(sponsorship(Contexts.usRemoteFromIndia())).isEqualTo("No");
        assertThat(sponsorship(Contexts.indiaOnsite())).isEqualTo("No");
    }

    @Test
    @DisplayName("an application answer beats a country rule")
    void applicationBeatsCountry() {
        rule("Yes", Scope.country("DE"));
        ApplicationContext german = Contexts.germanyOnsite();
        rule("No", Scope.application(german.postingId()));

        assertThat(sponsorship(german)).isEqualTo("No");
    }

    @Test
    @DisplayName("what he says now beats a stored application rule")
    void sessionBeatsStoredApplicationRule() {
        ApplicationContext german = Contexts.germanyOnsite();
        rule("Yes", Scope.application(german.postingId()));
        session.record(german.postingId(), Concepts.SPONSORSHIP_REQUIRED.id(), "No");

        Resolution resolution = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED, german);
        assertThat(resolution.value()).isEqualTo("No");
        assertThat(resolution.source()).isEqualTo(KnowledgeSource.SESSION);
    }

    @Test
    @DisplayName("contradictory rules for the same country are a conflict, not a guess")
    void contradictoryCountryRules() {
        rule("Yes", Scope.country("DE"));
        rule("No", Scope.country("DE"));

        Resolution resolution = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.germanyOnsite());
        assertThat(resolution.state()).isEqualTo(Resolution.State.CONFLICT);
        assertThat(resolution.competing()).hasSize(2);
        assertThat(resolution.isAutoFillable()).isFalse();
        // And the other countries are untouched by the argument.
        assertThat(sponsorship(Contexts.indiaOnsite())).isEqualTo("No");
    }

    @Test
    @DisplayName("a German rule never reaches an Indian or American application")
    void rulesDoNotLeakAcrossTheMatrix() {
        rule("Yes", Scope.country("DE"));
        for (ApplicationContext context : List.of(Contexts.indiaOnsite(),
                Contexts.usRemoteFromIndia(), Contexts.canadaRemoteFromIndia())) {
            Resolution resolution = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED, context);
            assertThat(resolution.source())
                    .as("a German rule reached %s", context.describe())
                    .isEqualTo(KnowledgeSource.DERIVED);
        }
    }

    private static ApplicantProfile authorisedIn(Country... countries) {
        ApplicantProfile base = TestProfiles.indianApplicant();
        return new ApplicantProfile(base.name(), base.contact(), base.address(),
                new ApplicantProfile.WorkAuthorisation(List.of(countries), true, "note"),
                base.demographics(), base.compensation(), base.availability(),
                base.extraAnswers(), base.assistantBriefing());
    }
}
