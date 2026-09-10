package com.anuragbhandary.jobradar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.apply.TestProfiles;
import com.anuragbhandary.jobradar.apply.form.FieldClassifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Applicability, authority, specificity, freshness and conflict - the five things
 * the plan asked to be kept apart, tested apart.
 */
class KnowledgeResolverTest {

    private final List<Assertion> stored = new ArrayList<>();
    private final SessionAnswers session = new SessionAnswers();
    private KnowledgeResolver resolver;

    @BeforeEach
    void setUp() {
        stored.clear();
        session.clearAll();

        AssertionRepository repository = mock(AssertionRepository.class);
        when(repository.findByConceptIdAndSupersededByIdIsNull(anyString()))
                .thenAnswer(call -> stored.stream()
                        .filter(a -> a.getConceptId().equals(call.getArgument(0)))
                        .filter(Assertion::isLive)
                        .toList());

        resolver = new KnowledgeResolver(repository,
                new ProfileFacts(TestProfiles.indianApplicant()),
                new Derivations(TestProfiles.indianApplicant()),
                session, new FieldClassifier());
    }

    private Assertion store(Concept concept, String value, Scope scope, KnowledgeSource source) {
        Assertion assertion = new Assertion(concept.id(), value, scope, source);
        assertion.approve("test");
        stored.add(assertion);
        return assertion;
    }

    // ------------------------------------------------------------------
    // Applicability: the safety property
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a German sponsorship rule does not answer an Indian application")
    void countryScopedKnowledgeDoesNotLeak() {
        store(Concepts.SPONSORSHIP_REQUIRED, "Yes", Scope.country("DE"),
                KnowledgeSource.USER_RULE);

        Resolution german = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.germanyOnsite());
        assertThat(german.value()).isEqualTo("Yes");
        assertThat(german.source()).isEqualTo(KnowledgeSource.USER_RULE);

        // The same rule, an Indian job. It is not outranked - it is not a
        // candidate at all, which is the difference between a preference and a
        // safety property.
        Resolution indian = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.indiaOnsite());
        assertThat(indian.value()).isEqualTo("No");
        assertThat(indian.source()).isEqualTo(KnowledgeSource.DERIVED);
        assertThat(indian.competing()).isEmpty();
    }

    @Test
    @DisplayName("an Indian answer does not answer a German application")
    void theLeakIsSymmetrical() {
        store(Concepts.SPONSORSHIP_REQUIRED, "No", Scope.country("IN"),
                KnowledgeSource.USER_RULE);
        Resolution german = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.germanyOnsite());
        assertThat(german.value()).isEqualTo("Yes");
        assertThat(german.source()).isEqualTo(KnowledgeSource.DERIVED);
    }

    @Test
    @DisplayName("a company answer does not reach another company")
    void companyScopedKnowledgeDoesNotLeak() {
        store(Concepts.WHY_COMPANY, "Because of their work on workflow engines.",
                Scope.company("Camunda"), KnowledgeSource.USER_INPUT);

        assertThat(resolver.resolve(Concepts.WHY_COMPANY, Contexts.germanyOnsite()).value())
                .contains("workflow engines");
        assertThat(resolver.resolve(Concepts.WHY_COMPANY, Contexts.usOnsite()).state())
                .isEqualTo(Resolution.State.UNKNOWN);
    }

    @Test
    @DisplayName("an application answer does not become a rule for the next one")
    void applicationScopedKnowledgeDoesNotLeak() {
        ApplicationContext first = Contexts.builder().postingId(11L).country("DE")
                .company("Camunda").build();
        ApplicationContext second = Contexts.builder().postingId(12L).country("DE")
                .company("Camunda").build();
        store(Concepts.WHY_ROLE, "It is the closest to what I have built.",
                Scope.application(11L), KnowledgeSource.USER_INPUT);

        assertThat(resolver.resolve(Concepts.WHY_ROLE, first).state())
                .isEqualTo(Resolution.State.KNOWN);
        assertThat(resolver.resolve(Concepts.WHY_ROLE, second).state())
                .isEqualTo(Resolution.State.UNKNOWN);
    }

    // ------------------------------------------------------------------
    // Authority
    // ------------------------------------------------------------------

    @Test
    @DisplayName("what he says now beats what he said before")
    void sessionAnswerWins() {
        store(Concepts.NOTICE_PERIOD, "Two months", Scope.global(), KnowledgeSource.USER_RULE);
        ApplicationContext context = Contexts.germanyOnsite();
        session.record(context.postingId(), Concepts.NOTICE_PERIOD.id(), "Immediately");

        Resolution resolution = resolver.resolve(Concepts.NOTICE_PERIOD, context);
        assertThat(resolution.value()).isEqualTo("Immediately");
        assertThat(resolution.source()).isEqualTo(KnowledgeSource.SESSION);
    }

    @Test
    @DisplayName("an approved rule beats an unapproved AI draft")
    void approvedRuleBeatsProposal() {
        Assertion draft = new Assertion(Concepts.WHY_COMPANY.id(),
                "A drafted paragraph.", Scope.company("Camunda"), KnowledgeSource.AI_PROPOSED);
        stored.add(draft);
        store(Concepts.WHY_COMPANY, "What he actually wrote.", Scope.company("Camunda"),
                KnowledgeSource.USER_INPUT);

        Resolution resolution = resolver.resolve(Concepts.WHY_COMPANY,
                Contexts.germanyOnsite());
        assertThat(resolution.value()).isEqualTo("What he actually wrote.");
        assertThat(resolution.source()).isEqualTo(KnowledgeSource.USER_INPUT);
    }

    @Test
    @DisplayName("an approved rule beats a derivation about the same country")
    void ruleBeatsDerivation() {
        // He knows something the derivation does not - a permit, an arrangement -
        // and an explicit approved statement outranks a computed one.
        store(Concepts.SPONSORSHIP_REQUIRED, "No", Scope.country("DE"),
                KnowledgeSource.USER_RULE);
        Resolution resolution = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.germanyOnsite());
        assertThat(resolution.value()).isEqualTo("No");
        assertThat(resolution.source()).isEqualTo(KnowledgeSource.USER_RULE);
    }

    @Test
    @DisplayName("a profile fact beats a historical answer")
    void profileBeatsHistory() {
        Assertion history = new Assertion(Concepts.NOTICE_PERIOD.id(), "Three months",
                Scope.global(), KnowledgeSource.HISTORICAL);
        stored.add(history);

        Resolution resolution = resolver.resolve(Concepts.NOTICE_PERIOD,
                Contexts.germanyOnsite());
        assertThat(resolution.source()).isEqualTo(KnowledgeSource.PROFILE);
        assertThat(resolution.value()).isEqualTo("None");
    }

    @Test
    @DisplayName("a historical answer is never auto-filled on its own")
    void historyIsEvidenceNotTruth() {
        Assertion history = new Assertion(Concepts.EDUCATION_DEGREE.id(), "Master's degree",
                Scope.global(), KnowledgeSource.HISTORICAL);
        stored.add(history);

        Resolution resolution = resolver.resolve(Concepts.EDUCATION_DEGREE,
                Contexts.germanyOnsite());
        assertThat(resolution.value()).isEqualTo("Master's degree");
        assertThat(resolution.confidence()).isEqualTo(Confidence.LOW);
        assertThat(resolution.isAutoFillable()).isFalse();
    }

    // ------------------------------------------------------------------
    // Specificity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("among equally authoritative answers the narrower scope wins")
    void narrowerScopeWins() {
        store(Concepts.EDUCATION_DEGREE, "Master's", Scope.global(), KnowledgeSource.USER_RULE);
        store(Concepts.EDUCATION_DEGREE, "MS Computer Science", Scope.country("DE"),
                KnowledgeSource.USER_RULE);

        assertThat(resolver.resolve(Concepts.EDUCATION_DEGREE, Contexts.germanyOnsite()).value())
                .isEqualTo("MS Computer Science");
        // And the global one still answers everywhere else.
        assertThat(resolver.resolve(Concepts.EDUCATION_DEGREE, Contexts.indiaOnsite()).value())
                .isEqualTo("Master's");
    }

    // ------------------------------------------------------------------
    // Conflict
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two equally applicable answers that disagree are a conflict, not a choice")
    void equalAndOppositeIsAConflict() {
        store(Concepts.SPONSORSHIP_REQUIRED, "Yes", Scope.country("DE"),
                KnowledgeSource.USER_RULE);
        store(Concepts.SPONSORSHIP_REQUIRED, "No", Scope.country("DE"),
                KnowledgeSource.USER_RULE);

        Resolution resolution = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.germanyOnsite());
        assertThat(resolution.state()).isEqualTo(Resolution.State.CONFLICT);
        assertThat(resolution.value()).isNull();
        assertThat(resolution.isAutoFillable()).isFalse();
        // Both survive, so the disagreement can be shown rather than described.
        assertThat(resolution.competing()).hasSize(2);
        assertThat(resolution.explanation()).contains("disagree");
    }

    @Test
    @DisplayName("the same value twice is agreement, not a conflict")
    void duplicatesAreNotConflicts() {
        store(Concepts.SPONSORSHIP_REQUIRED, "Yes", Scope.country("DE"),
                KnowledgeSource.USER_RULE);
        store(Concepts.SPONSORSHIP_REQUIRED, "Yes", Scope.country("DE"),
                KnowledgeSource.USER_RULE);
        assertThat(resolver.resolve(Concepts.SPONSORSHIP_REQUIRED, Contexts.germanyOnsite())
                .state()).isEqualTo(Resolution.State.KNOWN);
    }

    @Test
    @DisplayName("different specificity is a decision, not a conflict")
    void differentSpecificityResolves() {
        store(Concepts.EDUCATION_DEGREE, "Master's", Scope.global(), KnowledgeSource.USER_RULE);
        store(Concepts.EDUCATION_DEGREE, "Bachelor's", Scope.company("Camunda"),
                KnowledgeSource.USER_RULE);
        assertThat(resolver.resolve(Concepts.EDUCATION_DEGREE, Contexts.germanyOnsite())
                .state()).isEqualTo(Resolution.State.KNOWN);
    }

    // ------------------------------------------------------------------
    // Freshness and supersession
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a stale volatile fact is downgraded, never overwritten")
    void staleVolatileFactIsDowngraded() {
        Assertion notice = store(Concepts.NOTICE_PERIOD, "Two months", Scope.country("DE"),
                KnowledgeSource.USER_RULE);
        notice.setVerifyBy(LocalDate.of(2026, 1, 1));

        Resolution fresh = resolver.resolve(Concepts.NOTICE_PERIOD, Contexts.germanyOnsite(),
                LocalDate.of(2025, 12, 31));
        assertThat(fresh.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(fresh.isAutoFillable()).isTrue();

        Resolution stale = resolver.resolve(Concepts.NOTICE_PERIOD, Contexts.germanyOnsite(),
                LocalDate.of(2026, 6, 1));
        assertThat(stale.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(stale.isAutoFillable()).isFalse();
        // The value is still there. Ageing changes trust, never the answer.
        assertThat(stale.value()).isEqualTo("Two months");
        assertThat(stale.explanation()).contains("check-by date");
    }

    @Test
    @DisplayName("a superseded assertion is never returned")
    void supersededIsInvisible() {
        Assertion old = store(Concepts.NOTICE_PERIOD, "Three months", Scope.country("DE"),
                KnowledgeSource.USER_RULE);
        Assertion current = store(Concepts.NOTICE_PERIOD, "One month", Scope.country("DE"),
                KnowledgeSource.USER_RULE);
        old.setSupersededById(99L);

        Resolution resolution = resolver.resolve(Concepts.NOTICE_PERIOD,
                Contexts.germanyOnsite());
        assertThat(resolution.value()).isEqualTo("One month");
        assertThat(current.isLive()).isTrue();
        assertThat(old.isLive()).isFalse();
    }

    @Test
    @DisplayName("a rejected draft is never returned")
    void rejectedIsInvisible() {
        Assertion draft = new Assertion(Concepts.WHY_COMPANY.id(), "A bad draft.",
                Scope.company("Camunda"), KnowledgeSource.AI_PROPOSED);
        draft.reject("test");
        stored.add(draft);
        assertThat(resolver.resolve(Concepts.WHY_COMPANY, Contexts.germanyOnsite()).state())
                .isEqualTo(Resolution.State.UNKNOWN);
    }

    // ------------------------------------------------------------------
    // States
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unapproved draft resolves as a proposal, never as an answer")
    void unapprovedDraftIsAProposal() {
        Assertion draft = new Assertion(Concepts.WHY_COMPANY.id(), "A drafted paragraph.",
                Scope.company("Camunda"), KnowledgeSource.AI_PROPOSED);
        stored.add(draft);

        Resolution resolution = resolver.resolve(Concepts.WHY_COMPANY,
                Contexts.germanyOnsite());
        assertThat(resolution.state()).isEqualTo(Resolution.State.AI_PROPOSED);
        assertThat(resolution.isAutoFillable()).isFalse();
        assertThat(resolution.needsReview()).isTrue();
    }

    @Test
    @DisplayName("nothing known is UNKNOWN, which is not the same as blocked automation")
    void nothingKnownIsUnknown() {
        Resolution resolution = resolver.resolve(Concepts.VALID_PASSPORT,
                Contexts.germanyOnsite());
        assertThat(resolution.state()).isEqualTo(Resolution.State.UNKNOWN);
        assertThat(resolution.hasValue()).isFalse();
        // The states here are about knowledge only. Whether a browser could fill
        // a field is a different question with a different answer.
        assertThat(Resolution.State.values())
                .noneMatch(state -> state.name().contains("AUTOMATION"));
    }

    @Test
    @DisplayName("a blank voluntary field is declined, not unknown")
    void voluntaryBlankIsDeclined() {
        Resolution resolution = resolver.resolve(Concepts.PRONOUNS, Contexts.germanyOnsite());
        assertThat(resolution.state()).isEqualTo(Resolution.State.DECLINED);
    }

    @Test
    @DisplayName("a consent box is recognised and deliberately never answered")
    void consentIsNeverAnswered() {
        Resolution resolution = resolver.resolve(Concepts.CONSENT, Contexts.germanyOnsite());
        assertThat(resolution.state()).isEqualTo(Resolution.State.DECLINED);
        assertThat(resolution.isAutoFillable()).isFalse();
    }

    @Test
    @DisplayName("resolution is deterministic for the same inputs")
    void deterministic() {
        store(Concepts.SPONSORSHIP_REQUIRED, "Yes", Scope.country("DE"),
                KnowledgeSource.USER_RULE);
        Resolution first = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.germanyOnsite(), LocalDate.of(2026, 9, 9));
        for (int i = 0; i < 20; i++) {
            Resolution again = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED,
                    Contexts.germanyOnsite(), LocalDate.of(2026, 9, 9));
            assertThat(again.value()).isEqualTo(first.value());
            assertThat(again.state()).isEqualTo(first.state());
            assertThat(again.source()).isEqualTo(first.source());
        }
    }
}
