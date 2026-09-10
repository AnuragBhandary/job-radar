package com.anuragbhandary.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.apply.form.FieldClassifier;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.WorkMode;
import com.anuragbhandary.jobradar.filter.RealConfigAccess;
import com.anuragbhandary.jobradar.knowledge.ApprovalState;
import com.anuragbhandary.jobradar.knowledge.Assertion;
import com.anuragbhandary.jobradar.knowledge.AssertionRepository;
import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.Concepts;
import com.anuragbhandary.jobradar.knowledge.Confidence;
import com.anuragbhandary.jobradar.knowledge.Derivations;
import com.anuragbhandary.jobradar.knowledge.KnowledgeResolver;
import com.anuragbhandary.jobradar.knowledge.KnowledgeService;
import com.anuragbhandary.jobradar.knowledge.KnowledgeSource;
import com.anuragbhandary.jobradar.knowledge.ProfileFacts;
import com.anuragbhandary.jobradar.knowledge.Scope;
import com.anuragbhandary.jobradar.knowledge.SessionAnswers;
import com.anuragbhandary.jobradar.knowledge.ai.AnswerProposer;
import com.anuragbhandary.jobradar.knowledge.ai.ProposedAnswer;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * What the applicant may do to a field, and what he is stopped from doing.
 *
 * <p>Against a real database, because the interesting failures are about rows -
 * a version that did not move, an assertion that was superseded rather than
 * edited, a scope the write path had to refuse. A fake store would keep passing
 * while none of that worked.
 *
 * <p>Each action is followed by a flush and a clear, which is what a real request
 * boundary does. Without it the persistence context hands the same instance back
 * and the version never moves, so the staleness test would prove nothing.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class FieldActionsTest {

    @Autowired
    private ApplicationFieldRepository fields;
    @Autowired
    private ApplicationAttemptRepository attempts;
    @Autowired
    private PostingRepository postings;
    @Autowired
    private AssertionRepository assertions;
    @Autowired
    private BoardTokenRepository boards;
    @Autowired
    private TestEntityManager entityManager;

    @TempDir
    private Path tempDir;

    private FieldActions actions;
    private AnswerProposer proposer;
    private AnswerStore answerStore;
    private KnowledgeService knowledge;
    private Long attemptId;

    @BeforeEach
    void setUp() {
        fields.deleteAll();
        attempts.deleteAll();
        assertions.deleteAll();
        postings.deleteAll();

        ApplicantProfile profile = TestProfiles.indianApplicant();
        SessionAnswers session = new SessionAnswers();
        session.clearAll();
        knowledge = new KnowledgeService(assertions, session);
        KnowledgeResolver resolver = new KnowledgeResolver(assertions,
                new ProfileFacts(profile), new Derivations(profile), session,
                new FieldClassifier());
        // The real positioner, reading the real test resume: the point of
        // these tests is what the system does with actual evidence.
        var positioner = new com.anuragbhandary.jobradar.knowledge.experience
                .ExperiencePositioner(
                        new com.anuragbhandary.jobradar.knowledge.experience
                                .ExperienceIndex(TestResumes.backendResume()));
        proposer = mock(AnswerProposer.class);
        when(proposer.isUsable()).thenReturn(true);
        when(proposer.evidenceFor(any())).thenReturn(List.of());
        // Never the real applicant.yml: this file is hand-maintained personal
        // data outside the repository and a test has no business near it.
        answerStore = new AnswerStore(profile, tempDir.resolve("applicant.yml"));

        actions = new FieldActions(fields, attempts, postings, assertions, knowledge,
                resolver, new com.anuragbhandary.jobradar.knowledge.ApplicationContextFactory(
                        boards, profile, RealConfigAccess.countryStrategy()),
                new ProposalService(fields, proposer, knowledge,
                        new com.anuragbhandary.jobradar.knowledge.AnswerPlan(
                                resolver, positioner)), answerStore);

        attemptId = germanAttempt();
    }

    /** A Berlin role: onsite, relocation, and therefore sponsorship territory. */
    private Long germanAttempt() {
        Posting posting = new Posting(Source.GREENHOUSE, "camunda", "req-1",
                "Backend Engineer");
        posting.setUrl("https://example.invalid/1");
        posting.setLocation("Berlin, Germany");
        posting.setCountryCode("DE");
        posting.setEmployerCountryCode("DE");
        posting.setWorkMode(WorkMode.ONSITE);
        posting.setStrategicClass(StrategicClass.INTERNATIONAL_RELOCATION);
        posting.setFirstSeen(java.time.Instant.now());
        posting.setLastSeen(java.time.Instant.now());
        posting.setStatus(com.anuragbhandary.jobradar.domain.PostingStatus.NEW);
        posting.setVerdict(com.anuragbhandary.jobradar.domain.Verdict.CANDIDATE);
        Posting saved = postings.save(posting);

        ApplicationAttempt attempt =
                new ApplicationAttempt(saved.getId(), "Camunda", "Backend Engineer");
        attempt.setStatus(AttemptStatus.AWAITING_ANSWER);
        return attempts.save(attempt).getId();
    }

    private ApplicationField store(String label, String conceptId, FieldState state,
            boolean required, String value) {
        ApplicationField field = new ApplicationField(attemptId, label, state);
        field.setConceptId(conceptId);
        field.setRequired(required);
        field.setResolvedValue(value);
        return fields.save(field);
    }

    /** What a request boundary does, so a version actually moves between actions. */
    private ApplicationField reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return fields.findById(id).orElseThrow();
    }

    // ------------------------------------------------------------------
    // Answering an unknown question
    // ------------------------------------------------------------------

    @Test
    @DisplayName("answering resolves the field and records who answered it")
    void answeringResolvesTheField() {
        ApplicationField open = store("Do you hold a valid passport?",
                Concepts.VALID_PASSPORT.id(), FieldState.AWAITING_ANSWER, true, null);

        actions.answer(open.getId(), open.getVersion(), "Yes", null, null);

        ApplicationField answered = reload(open.getId());
        assertThat(answered.getState()).isEqualTo(FieldState.RESOLVED);
        assertThat(answered.getResolvedValue()).isEqualTo("Yes");
        assertThat(answered.getSource()).isEqualTo(KnowledgeSource.USER_INPUT);
        assertThat(answered.getConfidence()).isEqualTo(Confidence.HIGH);
        // No scope chosen, so nothing was remembered. Answering and remembering
        // are separate acts, and an application must never be blocked because the
        // scope picker was cancelled.
        assertThat(assertions.findAll()).isEmpty();
    }

    @Test
    @DisplayName("choosing a country saves the answer for that country and no other")
    void aCountryScopedAnswerIsStoredAtThatCountry() {
        ApplicationField open = store("Will you require visa sponsorship?",
                Concepts.SPONSORSHIP_REQUIRED.id(), FieldState.AWAITING_ANSWER, true, null);

        actions.answer(open.getId(), open.getVersion(), "Yes", "COUNTRY", null);

        List<Assertion> stored = assertions.findAll();
        assertThat(stored).hasSize(1);
        // The employment country, which for an onsite Berlin role is Germany.
        assertThat(stored.getFirst().scope()).isEqualTo(Scope.country("DE"));
        assertThat(stored.getFirst().getSource()).isEqualTo(KnowledgeSource.USER_INPUT);
        assertThat(stored.getFirst().isUsable()).isTrue();
    }

    @Test
    @DisplayName("a context-sensitive answer cannot be saved globally - refused, not narrowed")
    void globalIsRefusedForContextSensitiveConcepts() {
        ApplicationField open = store("Will you require visa sponsorship?",
                Concepts.SPONSORSHIP_REQUIRED.id(), FieldState.AWAITING_ANSWER, true, null);

        // The page never offers it. A client that sends it anyway is refused
        // here, because the server is the boundary and quietly narrowing would
        // hide the bug that sent it.
        assertThatThrownBy(() ->
                actions.answer(open.getId(), open.getVersion(), "Yes", "GLOBAL", null))
                .isInstanceOf(ActionRefused.class)
                .hasMessageContaining("context-sensitive");
        assertThat(assertions.findAll()).isEmpty();
    }

    @Test
    @DisplayName("the offered scopes never include one the concept forbids")
    void offeredScopesRespectTheConcept() {
        ApplicationField open = store("Will you require visa sponsorship?",
                Concepts.SPONSORSHIP_REQUIRED.id(), FieldState.AWAITING_ANSWER, true, null);

        List<ScopeOption> offered = actions.scopesFor(open.getId());

        assertThat(offered).isNotEmpty();
        assertThat(offered).noneMatch(scope -> "GLOBAL".equals(scope.level()));
        assertThat(offered).anyMatch(ScopeOption::recommended);
        // Spelled out, so what is about to be saved is not a guess.
        assertThat(offered).filteredOn(scope -> "COUNTRY".equals(scope.level()))
                .first()
                .extracting(ScopeOption::sentence).asString()
                .contains("Germany");
    }

    @Test
    @DisplayName("a global answer also reaches the flat store, which is what fills a form")
    void globalAnswersReachTheOldStore() {
        ApplicationField open = store("Do you hold a valid passport?",
                Concepts.VALID_PASSPORT.id(), FieldState.AWAITING_ANSWER, true, null);

        actions.answer(open.getId(), open.getVersion(), "Yes", "GLOBAL", null);

        assertThat(assertions.findAll()).hasSize(1);
        // Global is what extra-answers already means, so the two agree. A
        // narrower scope deliberately does not reach it.
        assertThat(answerStore.answers("Do you hold a valid passport?")).isTrue();
    }

    @Test
    @DisplayName("a country-scoped answer stays out of the flat store")
    void narrowerAnswersDoNotReachTheOldStore() {
        ApplicationField open = store("Will you require visa sponsorship?",
                Concepts.SPONSORSHIP_REQUIRED.id(), FieldState.AWAITING_ANSWER, true, null);

        actions.answer(open.getId(), open.getVersion(), "Yes", "COUNTRY", null);

        // The whole point. In the old store this entry would answer an Indian
        // form with "Yes", which is an auto-reject.
        assertThat(answerStore.answers("Will you require visa sponsorship?")).isFalse();
    }

    @Test
    @DisplayName("a blank answer is refused rather than filling the field with nothing")
    void blankAnswersAreRefused() {
        ApplicationField open = store("Notice period", Concepts.NOTICE_PERIOD.id(),
                FieldState.AWAITING_ANSWER, true, null);

        assertThatThrownBy(() -> actions.answer(open.getId(), open.getVersion(), "  ",
                null, null))
                .isInstanceOf(ActionRefused.class);
    }

    // ------------------------------------------------------------------
    // Drafts
    // ------------------------------------------------------------------

    private ApplicationField draft(String text) {
        ApplicationField field = store("Why do you want to work here?",
                Concepts.WHY_COMPANY.id(), FieldState.AWAITING_APPROVAL, false, text);
        Assertion proposal = knowledge.propose(Concepts.WHY_COMPANY, text,
                Scope.company("Camunda"), List.of(), field.getRawLabel());
        field.setPendingAssertionId(proposal.getId());
        field.setSource(KnowledgeSource.AI_PROPOSED);
        return fields.save(field);
    }

    @Test
    @DisplayName("an approved draft stays marked as drafted, not as something he wrote")
    void approvingKeepsTheDraftAiOriginated() {
        ApplicationField field = draft("Your work on process orchestration is the reason.");
        Long proposalId = field.getPendingAssertionId();

        actions.approve(field.getId(), field.getVersion(), proposalId, null);

        Assertion approved = assertions.findById(proposalId).orElseThrow();
        assertThat(approved.getApproval()).isEqualTo(ApprovalState.APPROVED);
        // Approving records that he read it, not that he wrote it. The version
        // before this one lost exactly that distinction.
        assertThat(approved.getSource()).isEqualTo(KnowledgeSource.AI_PROPOSED);
        assertThat(approved.isUserEdited()).isFalse();
        assertThat(reload(field.getId()).getState()).isEqualTo(FieldState.RESOLVED);
    }

    @Test
    @DisplayName("editing then approving records that he changed it")
    void editingBeforeApprovingIsRecorded() {
        ApplicationField field = draft("A sentence he did not like.");
        Long proposalId = field.getPendingAssertionId();

        actions.approve(field.getId(), field.getVersion(), proposalId,
                "A sentence he wrote instead.");

        Assertion approved = assertions.findById(proposalId).orElseThrow();
        assertThat(approved.getValue()).isEqualTo("A sentence he wrote instead.");
        assertThat(approved.isUserEdited()).isTrue();
        assertThat(approved.getSource()).isEqualTo(KnowledgeSource.AI_PROPOSED);
        assertThat(reload(field.getId()).getResolvedValue())
                .isEqualTo("A sentence he wrote instead.");
    }

    @Test
    @DisplayName("a draft that was replaced cannot be approved by a page that predates it")
    void obsoleteProposalsCannotBeApproved() {
        ApplicationField field = draft("The first draft.");
        Long firstProposal = field.getPendingAssertionId();

        when(proposer.propose(any(), any(), anyString())).thenReturn(Optional.of(
                new ProposedAnswer(ProposedAnswer.Status.PROPOSED, Concepts.WHY_COMPANY.id(),
                        "The second draft.", List.of("R1"), Confidence.MEDIUM, null)));
        actions.regenerate(field.getId(), field.getVersion(), firstProposal);

        ApplicationField afterRegenerate = reload(field.getId());
        assertThat(afterRegenerate.getResolvedValue()).isEqualTo("The second draft.");

        // The stale page still points at the first draft, and would approve prose
        // nobody has read.
        assertThatThrownBy(() -> actions.approve(afterRegenerate.getId(),
                afterRegenerate.getVersion(), firstProposal, null))
                .isInstanceOf(ActionRefused.class)
                .hasMessageContaining("replaced");
        assertThat(assertions.findById(firstProposal).orElseThrow().getApproval())
                .isEqualTo(ApprovalState.REJECTED);
    }

    @Test
    @DisplayName("an action carrying a version the row has moved past is refused")
    void staleVersionsAreRefused() {
        ApplicationField field = store("City", Concepts.CITY.id(),
                FieldState.RESOLVED, true, "Mumbai");
        long staleVersion = field.getVersion();

        actions.override(field.getId(), staleVersion, "Something else.");
        ApplicationField moved = reload(field.getId());
        assertThat(moved.getVersion()).isGreaterThan(staleVersion);

        assertThatThrownBy(() ->
                actions.override(field.getId(), staleVersion, "A third thing."))
                .isInstanceOf(ActionRefused.class)
                .hasMessageContaining("changed after the page was loaded");
    }

    @Test
    @DisplayName("rejecting a draft puts a required field back to awaiting an answer")
    void rejectingReturnsARequiredFieldToUnanswered() {
        ApplicationField field = store("Why this role?", Concepts.WHY_ROLE.id(),
                FieldState.AWAITING_APPROVAL, true, "A draft.");
        Assertion proposal = knowledge.propose(Concepts.WHY_ROLE, "A draft.",
                Scope.application(1L), List.of(), field.getRawLabel());
        field.setPendingAssertionId(proposal.getId());
        fields.save(field);

        actions.reject(field.getId(), field.getVersion(), proposal.getId());

        ApplicationField rejected = reload(field.getId());
        assertThat(rejected.getState()).isEqualTo(FieldState.AWAITING_ANSWER);
        assertThat(rejected.getResolvedValue()).isNull();
        assertThat(rejected.getPendingAssertionId()).isNull();
        // Kept in the record rather than deleted: what a previous version said is
        // part of the history.
        assertThat(assertions.findById(proposal.getId()).orElseThrow().getApproval())
                .isEqualTo(ApprovalState.REJECTED);
    }

    @Test
    @DisplayName("rejecting an optional draft leaves the field blank, not blocking")
    void rejectingAnOptionalDraftSkipsIt() {
        ApplicationField field = draft("A draft nobody wants.");

        actions.reject(field.getId(), field.getVersion(), field.getPendingAssertionId());

        assertThat(reload(field.getId()).getState()).isEqualTo(FieldState.SKIPPED_OPTIONAL);
    }

    @Test
    @DisplayName("a model that cannot draft leaves the draft he already had")
    void aFailedRegenerationKeepsTheOldDraft() {
        ApplicationField field = draft("The only draft there is.");
        Long proposalId = field.getPendingAssertionId();
        when(proposer.propose(any(), any(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                actions.regenerate(field.getId(), field.getVersion(), proposalId))
                .isInstanceOf(ActionRefused.class);

        ApplicationField unchanged = reload(field.getId());
        assertThat(unchanged.getResolvedValue()).isEqualTo("The only draft there is.");
        assertThat(unchanged.getPendingAssertionId()).isEqualTo(proposalId);
        assertThat(assertions.findById(proposalId).orElseThrow().getApproval())
                .isEqualTo(ApprovalState.UNAPPROVED);
    }

    // ------------------------------------------------------------------
    // Overriding
    // ------------------------------------------------------------------

    @Test
    @DisplayName("overriding keeps what it replaced, so 'what would it have sent?' is answerable")
    void overridingPreservesTheOriginal() {
        ApplicationField field = store("City", Concepts.CITY.id(),
                FieldState.RESOLVED, true, "Mumbai");
        field.setSource(KnowledgeSource.PROFILE);
        fields.save(field);

        actions.override(field.getId(), reload(field.getId()).getVersion(), "Navi Mumbai");

        ApplicationField overridden = reload(field.getId());
        assertThat(overridden.getResolvedValue()).isEqualTo("Navi Mumbai");
        assertThat(overridden.isUserEdited()).isTrue();
        assertThat(overridden.getOriginalValue()).isEqualTo("Mumbai");
        assertThat(overridden.getOriginalSource()).isEqualTo(KnowledgeSource.PROFILE);
    }

    @Test
    @DisplayName("a second override does not overwrite what the first one replaced")
    void theOriginalIsWrittenOnce() {
        ApplicationField field = store("City", Concepts.CITY.id(),
                FieldState.RESOLVED, true, "Mumbai");
        field.setSource(KnowledgeSource.PROFILE);
        fields.save(field);

        actions.override(field.getId(), reload(field.getId()).getVersion(), "Navi Mumbai");
        actions.override(field.getId(), reload(field.getId()).getVersion(), "Thane");

        assertThat(reload(field.getId()).getOriginalValue()).isEqualTo("Mumbai");
    }

    @Test
    @DisplayName("a field with a draft on it is approved or rejected, never edited around")
    void draftsCannotBeQuietlyOverridden() {
        ApplicationField field = draft("A draft.");

        assertThatThrownBy(() ->
                actions.override(field.getId(), field.getVersion(), "Something else."))
                .isInstanceOf(ActionRefused.class)
                .hasMessageContaining("draft");
    }

    // ------------------------------------------------------------------
    // Explaining
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the explanation carries the context, not a sentence about the context")
    void explanationsCarryStructuredContext() {
        ApplicationField field = store("Will you require visa sponsorship?",
                Concepts.SPONSORSHIP_REQUIRED.id(), FieldState.RESOLVED, true, "Yes");
        field.setSource(KnowledgeSource.DERIVED);
        field.setConfidence(Confidence.HIGH);
        field.setExplanation("the job is in Germany and you hold no permit there");
        fields.save(field);

        FieldExplanation why = actions.explain(field.getId());

        assertThat(why.conceptLabel()).isEqualTo(Concepts.SPONSORSHIP_REQUIRED.label());
        assertThat(why.contextSensitive()).isTrue();
        assertThat(why.context()).contains("Germany");
        assertThat(why.answer()).isEqualTo("Yes");
        assertThat(why.hasConflict()).isFalse();
        // The new resolver's opinion travels alongside, marked as comparison only.
        assertThat(why.secondOpinion()).isNotNull();
    }

    @Test
    @DisplayName("two equally applicable answers that disagree are shown, never chosen between")
    void conflictsAreSurfacedAndNotResolved() {
        Concept concept = Concepts.SPONSORSHIP_REQUIRED;
        knowledge.remember(concept, "Yes", Scope.country("DE"), KnowledgeSource.USER_RULE,
                List.of(), "sponsorship", "test", true);
        Assertion second = new Assertion(concept.id(), "No", Scope.country("DE"),
                KnowledgeSource.USER_RULE);
        second.approve("test");
        assertions.save(second);

        ApplicationField field = store("Will you require visa sponsorship?",
                concept.id(), FieldState.RESOLVED, true, "Yes");

        assertThat(actions.explain(field.getId()).hasConflict()).isTrue();
        assertThat(actions.conflictedFields(attemptId)).contains(field.getId());
    }

    @Test
    @DisplayName("an unclassified question can still be answered, and offers no scope at all")
    void unclassifiedQuestionsAreAnswerableAndNotGeneralisable() {
        ApplicationField open = store("Describe a time you disagreed with a manager.",
                null, FieldState.AWAITING_ANSWER, true, null);

        assertThat(actions.scopesFor(open.getId())).isEmpty();

        actions.answer(open.getId(), open.getVersion(), "A paragraph.", null, null);

        assertThat(reload(open.getId()).getState()).isEqualTo(FieldState.RESOLVED);
        assertThat(assertions.findAll()).isEmpty();
    }
}
