package com.anuragbhandary.jobradar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.apply.TestProfiles;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The write path, against a real database.
 *
 * <p>Against SQLite rather than a fake, for the same reason
 * {@code PostingRepositoryTest} is: the interesting failures here are schema
 * ones, and a fake store would keep passing while the column that holds the
 * scope did not exist.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class KnowledgeStoreTest {

    @Autowired
    private AssertionRepository assertions;

    private KnowledgeService knowledge;
    private SessionAnswers session;

    @BeforeEach
    void setUp() {
        assertions.deleteAll();
        session = new SessionAnswers();
        knowledge = new KnowledgeService(assertions, session);
    }

    // ------------------------------------------------------------------
    // Scope safety
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a context-sensitive answer cannot be stored globally, at all")
    void refusesGlobalForContextSensitiveConcepts() {
        // Not narrowed quietly - refused. A caller asking for this has a bug, and
        // silently storing it at country scope would hide the bug while looking
        // like it worked.
        assertThatThrownBy(() -> knowledge.remember(Concepts.SPONSORSHIP_REQUIRED, "Yes",
                Scope.global(), KnowledgeSource.USER_RULE, List.of(), "q", "test", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context-sensitive");

        assertThat(assertions.count()).isZero();
    }

    @Test
    @DisplayName("answering the same question for two countries makes two rules, never a third")
    void scopeIsNeverWidenedAutomatically() {
        knowledge.remember(Concepts.SPONSORSHIP_REQUIRED, "Yes", Scope.country("DE"),
                KnowledgeSource.USER_RULE, List.of(), "q", "test", true);
        knowledge.remember(Concepts.SPONSORSHIP_REQUIRED, "Yes", Scope.country("NL"),
                KnowledgeSource.USER_RULE, List.of(), "q", "test", true);

        List<Assertion> live = knowledge.forConcept(Concepts.SPONSORSHIP_REQUIRED.id());
        assertThat(live).hasSize(2);
        assertThat(live).extracting(a -> a.scope().level())
                .containsOnly(Scope.Level.COUNTRY);
        assertThat(live).noneMatch(a -> a.scope().level() == Scope.Level.GLOBAL);
    }

    @Test
    @DisplayName("the default scope for a context-sensitive concept is the employment country")
    void defaultScopeIsConservative() {
        Scope scope = knowledge.defaultScopeFor(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.germanyOnsite());
        assertThat(scope.level()).isEqualTo(Scope.Level.COUNTRY);
        assertThat(scope.value()).isEqualTo("de");

        // For a US employer hiring into India it is India, because that is where
        // he would sit and therefore what the answer is about.
        assertThat(knowledge.defaultScopeFor(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.usRemoteFromIndia()).value()).isEqualTo("in");
    }

    @Test
    @DisplayName("global is never among the scopes offered for a context-sensitive concept")
    void globalIsNotOffered() {
        List<Scope> offered = knowledge.offeredScopes(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.germanyOnsite());
        assertThat(offered).isNotEmpty();
        assertThat(offered).noneMatch(scope -> scope.level() == Scope.Level.GLOBAL);
        assertThat(offered).anyMatch(scope -> scope.level() == Scope.Level.COUNTRY);
    }

    @Test
    @DisplayName("a scope whose value the context cannot supply is not offered")
    void unusableScopesAreNotOffered() {
        // A remote posting that never said which country. Offering COUNTRY here
        // would store an assertion that can never match anything.
        List<Scope> offered = knowledge.offeredScopes(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.remoteUnstated());
        assertThat(offered).noneMatch(Scope::isPending);
        assertThat(offered).noneMatch(scope -> scope.level() == Scope.Level.COUNTRY);
    }

    // ------------------------------------------------------------------
    // Provenance
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an approved AI draft stays AI-originated forever")
    void approvedProposalKeepsItsOrigin() {
        Assertion draft = knowledge.propose(Concepts.WHY_COMPANY, "A drafted paragraph.",
                Scope.company("Camunda"),
                List.of(Evidence.resume("R1", "Built a thing")), "Why us?");

        assertThat(draft.getApproval()).isEqualTo(ApprovalState.UNAPPROVED);
        assertThat(draft.isUsable()).isFalse();

        Assertion approved = knowledge.approve(draft.getId(), null, "review page").orElseThrow();

        // The bug this exists to prevent: the old path wrote approved drafts back
        // into applicant.yml as plain pairs, after which they were
        // indistinguishable from facts he had typed himself.
        assertThat(approved.getSource()).isEqualTo(KnowledgeSource.AI_PROPOSED);
        assertThat(approved.getApproval()).isEqualTo(ApprovalState.APPROVED);
        assertThat(approved.getApprovedAt()).isNotNull();
        assertThat(approved.getApprovedVia()).isEqualTo("review page");
        assertThat(approved.isUserEdited()).isFalse();
    }

    @Test
    @DisplayName("editing a draft records the edit and keeps the origin")
    void editedProposalRecordsTheEdit() {
        Assertion draft = knowledge.propose(Concepts.WHY_COMPANY, "What the model wrote.",
                Scope.company("Camunda"), List.of(), "Why us?");
        Assertion approved = knowledge.approve(draft.getId(), "What he actually meant.",
                "review page").orElseThrow();

        assertThat(approved.getValue()).isEqualTo("What he actually meant.");
        assertThat(approved.isUserEdited()).isTrue();
        assertThat(approved.getSource()).isEqualTo(KnowledgeSource.AI_PROPOSED);
    }

    @Test
    @DisplayName("a rejected draft is kept, not deleted")
    void rejectedDraftsSurvive() {
        Assertion draft = knowledge.propose(Concepts.WHY_COMPANY, "A bad draft.",
                Scope.company("Camunda"), List.of(), "Why us?");
        knowledge.reject(draft.getId(), "review page");

        // So the same draft is not offered again, and "why is this still
        // unanswered?" has an answer.
        assertThat(assertions.findById(draft.getId())).isPresent();
        assertThat(assertions.findById(draft.getId()).orElseThrow().getApproval())
                .isEqualTo(ApprovalState.REJECTED);
    }

    @Test
    @DisplayName("a model may not draft an answer to a factual question")
    void proposalsAreRefusedForFactualConcepts() {
        assertThatThrownBy(() -> knowledge.propose(Concepts.NOTICE_PERIOD, "Two weeks",
                Scope.global(), List.of(), "Notice period?"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Supersession and history
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a changed answer supersedes the old one rather than overwriting it")
    void changingAnAnswerKeepsTheHistory() {
        Assertion first = knowledge.remember(Concepts.NOTICE_PERIOD, "Two months",
                Scope.global(), KnowledgeSource.USER_RULE, List.of(), "q", "test", true);
        Assertion second = knowledge.remember(Concepts.NOTICE_PERIOD, "Immediately",
                Scope.global(), KnowledgeSource.USER_RULE, List.of(), "q", "test", true);

        assertThat(knowledge.forConcept(Concepts.NOTICE_PERIOD.id()))
                .extracting(Assertion::getValue)
                .containsExactly("Immediately");

        // "What did I tell them in March?" stays answerable after the answer
        // changes, which is the point.
        Assertion stored = assertions.findById(first.getId()).orElseThrow();
        assertThat(stored.isLive()).isFalse();
        assertThat(stored.getSupersededById()).isEqualTo(second.getId());
        assertThat(stored.getValue()).isEqualTo("Two months");
    }

    @Test
    @DisplayName("a different scope does not supersede - they are different knowledge")
    void differentScopesCoexist() {
        knowledge.remember(Concepts.NOTICE_PERIOD, "Two months", Scope.global(),
                KnowledgeSource.USER_RULE, List.of(), "q", "test", true);
        knowledge.remember(Concepts.NOTICE_PERIOD, "One month", Scope.country("DE"),
                KnowledgeSource.USER_RULE, List.of(), "q", "test", true);

        assertThat(knowledge.forConcept(Concepts.NOTICE_PERIOD.id())).hasSize(2);
    }

    @Test
    @DisplayName("a historical answer is stored unapproved")
    void historicalAnswersAreNotFacts() {
        Assertion history = knowledge.recordHistorical(Concepts.SPONSORSHIP_REQUIRED, "Yes",
                Scope.country("DE"), "Do you require sponsorship?", 42L);

        assertThat(history.getSource()).isEqualTo(KnowledgeSource.HISTORICAL);
        assertThat(history.getApproval()).isEqualTo(ApprovalState.UNAPPROVED);
        assertThat(history.isUsable()).isFalse();
        assertThat(history.evidenceList()).isNotEmpty();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("learning uses the answer now and stores it separately")
    void learningAnswersNowAndRemembersAfter() {
        ApplicationContext context = Contexts.germanyOnsite();
        knowledge.learn(context, Concepts.SPONSORSHIP_REQUIRED, "Yes",
                knowledge.defaultScopeFor(Concepts.SPONSORSHIP_REQUIRED, context),
                KnowledgeSource.USER_RULE, "Do you require sponsorship?", "answers page");

        // The current application gets it regardless of what happens to the rule.
        assertThat(session.get(context.postingId(), Concepts.SPONSORSHIP_REQUIRED.id()))
                .contains("Yes");
        assertThat(knowledge.forConcept(Concepts.SPONSORSHIP_REQUIRED.id()))
                .singleElement()
                .satisfies(a -> assertThat(a.scope().describe()).isEqualTo("country=de"));
    }

    @Test
    @DisplayName("evidence round-trips through the column")
    void evidenceSurvivesStorage() {
        Assertion assertion = knowledge.remember(Concepts.AGE_OVER_18, "Yes", Scope.global(),
                KnowledgeSource.USER_INPUT,
                List.of(Evidence.profile("dob", "recorded"),
                        Evidence.context("asked by", "Greenhouse")),
                "Are you at least 18?", "test", true);

        List<Evidence> read = assertions.findById(assertion.getId())
                .orElseThrow().evidenceList();
        assertThat(read).hasSize(2);
        assertThat(read.getFirst().kind()).isEqualTo(Evidence.Kind.PROFILE_FIELD);
        assertThat(read.getLast().ref()).isEqualTo("asked by");
    }
}
