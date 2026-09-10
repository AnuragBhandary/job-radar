package com.anuragbhandary.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.apply.form.Answer;
import com.anuragbhandary.jobradar.apply.form.FieldClassifier;
import com.anuragbhandary.jobradar.apply.form.FieldKind;
import com.anuragbhandary.jobradar.apply.form.FillReport;
import com.anuragbhandary.jobradar.apply.form.FormField;
import com.anuragbhandary.jobradar.apply.form.Submitter;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.domain.WorkMode;
import com.anuragbhandary.jobradar.filter.RealConfigAccess;
import com.anuragbhandary.jobradar.knowledge.ApplicationContext;
import com.anuragbhandary.jobradar.knowledge.ApplicationContextFactory;
import com.anuragbhandary.jobradar.knowledge.ApprovalState;
import com.anuragbhandary.jobradar.knowledge.AssertionRepository;
import com.anuragbhandary.jobradar.knowledge.Concepts;
import com.anuragbhandary.jobradar.knowledge.Confidence;
import com.anuragbhandary.jobradar.knowledge.Derivations;
import com.anuragbhandary.jobradar.knowledge.KnowledgeResolver;
import com.anuragbhandary.jobradar.knowledge.KnowledgeService;
import com.anuragbhandary.jobradar.knowledge.KnowledgeSource;
import com.anuragbhandary.jobradar.knowledge.ProfileFacts;
import com.anuragbhandary.jobradar.knowledge.SessionAnswers;
import com.anuragbhandary.jobradar.knowledge.ai.AnswerProposer;
import com.anuragbhandary.jobradar.knowledge.ai.ProposedAnswer;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * One application, from a filled form to a form that opens again.
 *
 * <p>The seven kinds of field a real board actually produces, in one fixture:
 * something copied from the profile, something worked out from the country,
 * something a model drafted, a required question nothing could answer, an
 * optional one, an answer the page refused to take, and a board that wants a
 * person. Every one of them behaves differently and the point of the phase is
 * that they now look different too.
 *
 * <p>No browser. The form reading and the typing are the two parts that need one,
 * and they are exactly the parts this is not testing: what happens afterwards is
 * a question about rows, and rows are testable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PreparationFixtureTest {

    @Autowired
    private ApplicationFieldRepository fields;
    @Autowired
    private QuestionSightingRepository sightings;
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

    private FieldRecorder recorder;
    private ProposalService proposals;
    private FieldActions actions;
    private ApplicationContext context;
    private Posting posting;
    private ApplicationAttempt attempt;

    @BeforeEach
    void setUp() {
        fields.deleteAll();
        sightings.deleteAll();
        attempts.deleteAll();
        assertions.deleteAll();
        postings.deleteAll();

        ApplicantProfile profile = TestProfiles.indianApplicant();
        SessionAnswers session = new SessionAnswers();
        session.clearAll();
        KnowledgeService knowledge = new KnowledgeService(assertions, session);
        KnowledgeResolver resolver = new KnowledgeResolver(assertions,
                new ProfileFacts(profile), new Derivations(profile), session,
                new FieldClassifier());

        // The real positioner, reading the real test resume: the point of
        // these tests is what the system does with actual evidence.
        var positioner = new com.anuragbhandary.jobradar.knowledge.experience
                .ExperiencePositioner(
                        new com.anuragbhandary.jobradar.knowledge.experience
                                .ExperienceIndex(TestResumes.backendResume()));
        AnswerProposer proposer = mock(AnswerProposer.class);
        when(proposer.isUsable()).thenReturn(true);
        when(proposer.evidenceFor(any())).thenReturn(List.of());
        when(proposer.propose(any(), any(), anyString())).thenReturn(Optional.of(
                new ProposedAnswer(ProposedAnswer.Status.PROPOSED, Concepts.WHY_COMPANY.id(),
                        "Your work on process orchestration is the closest thing I have "
                                + "seen to the replay system I built.",
                        List.of("R1"), Confidence.MEDIUM, null)));

        recorder = new FieldRecorder(fields, sightings, resolver);
        proposals = new ProposalService(fields, proposer, knowledge,
                        new com.anuragbhandary.jobradar.knowledge.AnswerPlan(
                                resolver, positioner));
        ApplicationContextFactory contexts = new ApplicationContextFactory(
                boards, profile, RealConfigAccess.countryStrategy());
        actions = new FieldActions(fields, attempts, postings, assertions, knowledge,
                resolver, contexts, proposals,
                new AnswerStore(profile, tempDir.resolve("applicant.yml")));

        posting = germanPosting();
        context = contexts.of(posting, "Camunda", null, null);
        attempt = attempts.save(
                new ApplicationAttempt(posting.getId(), "Camunda", "Backend Engineer"));
    }

    private Posting germanPosting() {
        Posting berlin = new Posting(Source.GREENHOUSE, "camunda", "req-1",
                "Backend Engineer");
        berlin.setUrl("https://example.invalid/1");
        berlin.setLocation("Berlin, Germany");
        berlin.setCountryCode("DE");
        berlin.setEmployerCountryCode("DE");
        berlin.setWorkMode(WorkMode.ONSITE);
        berlin.setStrategicClass(StrategicClass.INTERNATIONAL_RELOCATION);
        berlin.setFirstSeen(Instant.now());
        berlin.setLastSeen(Instant.now());
        berlin.setStatus(PostingStatus.NEW);
        berlin.setVerdict(Verdict.CANDIDATE);
        return postings.save(berlin);
    }

    // ------------------------------------------------------------------
    // The fixture
    // ------------------------------------------------------------------

    private static FormField field(String selector, String label,
            FormField.ControlType control, List<String> options, boolean required,
            FieldKind kind) {
        return new FormField(selector, label, control, options, required, kind);
    }

    /** What {@code FormFiller} hands back after a real Greenhouse form. */
    private FillReport filledForm() {
        FillReport.Entry known = new FillReport.Entry(
                field("#first_name", "First name", FormField.ControlType.TEXT,
                        List.of(), true, FieldKind.FIRST_NAME),
                Answer.profile("Anurag"), true, null);

        FillReport.Entry derived = new FillReport.Entry(
                field("#sponsorship", "Will you require visa sponsorship?",
                        FormField.ControlType.SELECT, List.of("Yes", "No"), true,
                        FieldKind.SPONSORSHIP_REQUIRED),
                Answer.derived("Yes", "the job is in Germany and you hold no permit there"),
                true, null);

        FillReport.Entry openEnded = new FillReport.Entry(
                field("#why", "Why do you want to work here?",
                        FormField.ControlType.TEXTAREA, List.of(), true, FieldKind.UNKNOWN),
                Answer.unanswered("no configured answer"), false, null);

        FillReport.Entry unknown = new FillReport.Entry(
                field("#k8s", "Do you have professional Kubernetes experience?",
                        FormField.ControlType.SELECT, List.of("Yes", "No"), true,
                        FieldKind.UNKNOWN),
                Answer.unanswered("no configured answer"), false, null);

        FillReport.Entry optional = new FillReport.Entry(
                field("#pronouns", "Pronouns", FormField.ControlType.TEXT,
                        List.of(), false, FieldKind.PRONOUNS),
                Answer.unanswered("not set in applicant.yml"), false, null);

        // The one this whole phase is about: the answer is right, and the widget
        // would not take it.
        FillReport.Entry blocked = new FillReport.Entry(
                field("#start", "Earliest start date", FormField.ControlType.DATE,
                        List.of(), true, FieldKind.START_DATE),
                Answer.profile("2027-06-01"), false,
                "the date picker has no text input");

        List<FillReport.Entry> entries =
                List.of(known, derived, openEnded, unknown, optional, blocked);
        return new FillReport(entries, List.of(openEnded, unknown, blocked));
    }

    private Map<String, ApplicationField> recorded() {
        entityManager.flush();
        entityManager.clear();
        return fields.findByAttemptIdOrderByIdAsc(attempt.getId()).stream()
                .collect(Collectors.toMap(ApplicationField::getRawLabel,
                        Function.identity(), (a, b) -> a));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("the seven kinds of field come out of one form as seven different states")
    void oneFormProducesSevenDistinctOutcomes() {
        AttemptStatus status = recorder.record(attempt.getId(), posting, context,
                filledForm(), "Camunda");
        proposals.proposeFor(attempt.getId(), context, status);

        Map<String, ApplicationField> rows = recorded();

        // 1. Copied from the profile, and typed in.
        assertThat(rows.get("First name").outcome())
                .isEqualTo(ApplicationField.Outcome.FILLED);
        assertThat(rows.get("First name").getSource()).isEqualTo(KnowledgeSource.PROFILE);

        // 2. Worked out from the country, and typed in. Shown, because a
        //    derivation is right only if the derivation is.
        ApplicationField sponsorship = rows.get("Will you require visa sponsorship?");
        assertThat(sponsorship.getSource()).isEqualTo(KnowledgeSource.DERIVED);
        assertThat(sponsorship.getResolvedValue()).isEqualTo("Yes");
        assertThat(sponsorship.getConceptId()).isEqualTo(Concepts.SPONSORSHIP_REQUIRED.id());

        // 3. Drafted, and waiting to be read.
        ApplicationField draft = rows.get("Why do you want to work here?");
        assertThat(draft.getState()).isEqualTo(FieldState.AWAITING_APPROVAL);
        assertThat(draft.getPendingAssertionId()).isNotNull();
        assertThat(draft.getSource()).isEqualTo(KnowledgeSource.AI_PROPOSED);

        // 4. Nothing could answer it, and it is required.
        ApplicationField open = rows.get("Do you have professional Kubernetes experience?");
        assertThat(open.getState()).isEqualTo(FieldState.AWAITING_ANSWER);
        assertThat(open.outcome()).isEqualTo(ApplicationField.Outcome.AWAITING_ANSWER);

        // 5. Nothing could answer it, and it blocks nothing.
        assertThat(rows.get("Pronouns").outcome())
                .isEqualTo(ApplicationField.Outcome.SKIPPED);

        // 6. Known, and refused by the page. The value survives - that rule is
        //    the reason ApplicationField has two state columns.
        ApplicationField stuck = rows.get("Earliest start date");
        assertThat(stuck.getState()).isEqualTo(FieldState.RESOLVED);
        assertThat(stuck.getAutomationState()).isEqualTo(AutomationState.BLOCKED);
        assertThat(stuck.getResolvedValue()).isEqualTo("2027-06-01");
        assertThat(stuck.outcome()).isEqualTo(ApplicationField.Outcome.AUTOMATION_BLOCKED);
    }

    @Test
    @DisplayName("a required question with no answer outranks everything else waiting")
    void theStatusNamesTheMostValuableThingOutstanding() {
        AttemptStatus status = recorder.record(attempt.getId(), posting, context,
                filledForm(), "Camunda");
        // Answering teaches Job Radar something every future form will use; a
        // blocked control is the browser's problem, not his.
        assertThat(status).isEqualTo(AttemptStatus.AWAITING_ANSWER);
    }

    @Test
    @DisplayName("the whole flow: approve, answer, scope, and the form can open")
    void theApplicationBecomesOpenable() {
        AttemptStatus status = recorder.record(attempt.getId(), posting, context,
                filledForm(), "Camunda");
        proposals.proposeFor(attempt.getId(), context, status);

        Map<String, ApplicationField> rows = recorded();
        Readiness before = Readiness.of(fields.findByAttemptIdOrderByIdAsc(attempt.getId()));
        assertThat(before.total()).isEqualTo(6);
        assertThat(before.awaitingAnswer()).isEqualTo(1);
        assertThat(before.awaitingApproval()).isEqualTo(1);
        assertThat(before.automationBlocked()).isEqualTo(1);
        // A required question with no answer would go back into the form as the
        // same blank. Not offered.
        assertThat(before.canOpen(null)).isFalse();

        // He reads the draft and takes it.
        ApplicationField draft = rows.get("Why do you want to work here?");
        actions.approve(draft.getId(), draft.getVersion(), draft.getPendingAssertionId(),
                null);
        assertThat(assertions.findById(draft.getPendingAssertionId()).orElseThrow()
                .getSource()).isEqualTo(KnowledgeSource.AI_PROPOSED);
        assertThat(assertions.findById(draft.getPendingAssertionId()).orElseThrow()
                .getApproval()).isEqualTo(ApprovalState.APPROVED);

        // He answers the one nothing could answer, and says where it applies.
        ApplicationField open = recorded()
                .get("Do you have professional Kubernetes experience?");
        actions.answer(open.getId(), open.getVersion(), "No", "GLOBAL", null);

        Readiness after = Readiness.of(fields.findByAttemptIdOrderByIdAsc(attempt.getId()));
        assertThat(after.awaitingAnswer()).isZero();
        assertThat(after.awaitingApproval()).isZero();
        // Still blocked, still counted, and still not a reason to refuse to open -
        // opening is how a blocked control gets finished.
        assertThat(after.automationBlocked()).isEqualTo(1);
        assertThat(after.canOpen(null)).isTrue();
        assertThat(after.summary()).contains("needing manual completion");

        // And everything settled goes back into the form on the next run.
        PreparedAnswers settled = PreparedAnswers.from(
                fields.findByAttemptIdOrderByIdAsc(attempt.getId()));
        assertThat(settled.byQuestion()).containsKey("earliest start date");
        assertThat(settled.byQuestion())
                .containsKey("do you have professional kubernetes experience?");
        // The optional question was skipped on purpose and stays skipped.
        assertThat(settled.byQuestion()).doesNotContainKey("pronouns");
    }

    @Test
    @DisplayName("a board that wants a person is manual, and its answers are still good")
    void aCaptchaIsManualRatherThanFailed() {
        recorder.record(attempt.getId(), posting, context, filledForm(), "Camunda");
        attempt.setManualReason(ManualReason.CAPTCHA);
        attempt.setStatus(AttemptStatus.MANUAL_REQUIRED);
        attempts.save(attempt);

        assertThat(attempt.getStatus()).isNotEqualTo(AttemptStatus.FAILED);
        assertThat(attempt.getManualReason().valuesReusable()).isTrue();
        // Opening is the only move left, so it is offered even with the required
        // question still open - the answers ride along with it.
        Readiness readiness = Readiness.of(fields.findByAttemptIdOrderByIdAsc(attempt.getId()));
        assertThat(readiness.canOpen(ManualReason.CAPTCHA)).isTrue();
    }

    @Test
    @DisplayName("nothing in this flow can submit without a person saying so")
    void submissionStillNeedsAHuman() {
        FillReport report = filledForm();
        Submitter submitter = new Submitter();

        // The first check, before the page is even looked at. There is no
        // configuration that sets this flag and no caller in the preparation
        // workflow that passes true.
        assertThatThrownBy(() -> submitter.submit(null, report, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without human confirmation");

        // And a form with unanswered required fields is refused even then.
        assertThatThrownBy(() -> submitter.submit(null, report, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not submittable");
    }

    @Test
    @DisplayName("every question is recorded as a sighting, so frequency is answerable")
    void everyQuestionIsSighted() {
        recorder.record(attempt.getId(), posting, context, filledForm(), "Camunda");

        List<QuestionSighting> seen = sightings.findByAttemptId(attempt.getId());
        assertThat(seen).hasSize(6);
        assertThat(seen).allMatch(sighting -> "Camunda".equals(sighting.getCompany()));
        // The employment country, which is what a context-sensitive answer would
        // have been scoped to.
        assertThat(seen).allMatch(sighting -> "DE".equals(sighting.getCountryCode()));
    }
}
