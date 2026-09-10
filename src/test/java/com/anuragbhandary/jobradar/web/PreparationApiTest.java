package com.anuragbhandary.jobradar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.ApplicationField;
import com.anuragbhandary.jobradar.apply.ApplicationFieldRepository;
import com.anuragbhandary.jobradar.apply.AttemptStatus;
import com.anuragbhandary.jobradar.apply.AutomationState;
import com.anuragbhandary.jobradar.apply.FieldState;
import com.anuragbhandary.jobradar.apply.ManualReason;
import com.anuragbhandary.jobradar.apply.PreparationStage;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.domain.WorkMode;
import com.anuragbhandary.jobradar.knowledge.AssertionRepository;
import com.anuragbhandary.jobradar.knowledge.Concepts;
import com.anuragbhandary.jobradar.knowledge.KnowledgeSource;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The preparation workflow over HTTP.
 *
 * <p>Not transactional, on purpose. Every action in the real application commits
 * at the end of its request, and that commit is what moves the row version the
 * staleness check reads - a test wrapped in one long transaction would never see
 * a version change and would pass while the check did nothing.
 *
 * <p>Nothing here starts a preparation. Everything that would open a browser is
 * either refused before it is queued or left alone; the browser paths are the
 * ones this cannot test and the ones a person is present for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PreparationApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ApplicationFieldRepository fields;
    @Autowired
    private ApplicationAttemptRepository attempts;
    @Autowired
    private PostingRepository postings;
    @Autowired
    private AssertionRepository assertions;

    private Long attemptId;
    private Long postingId;

    @BeforeEach
    void setUp() {
        clean();
        Posting berlin = new Posting(Source.GREENHOUSE, "camunda-test", "req-api",
                "Backend Engineer");
        berlin.setUrl("https://example.invalid/api");
        berlin.setLocation("Berlin, Germany");
        berlin.setCountryCode("DE");
        berlin.setEmployerCountryCode("DE");
        berlin.setWorkMode(WorkMode.ONSITE);
        berlin.setStrategicClass(StrategicClass.INTERNATIONAL_RELOCATION);
        berlin.setFirstSeen(Instant.now());
        berlin.setLastSeen(Instant.now());
        berlin.setStatus(PostingStatus.NEW);
        berlin.setVerdict(Verdict.CANDIDATE);
        postingId = postings.save(berlin).getId();

        ApplicationAttempt attempt =
                new ApplicationAttempt(postingId, "Camunda", "Backend Engineer");
        attempt.setStatus(AttemptStatus.AWAITING_ANSWER);
        attempt.setStage(PreparationStage.FINISHED, "6 fields read");
        attemptId = attempts.save(attempt).getId();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        fields.deleteAll();
        attempts.deleteAll();
        assertions.deleteAll();
        // Only this test's own posting: the shared test database is reused
        // across runs and deleting everything would take other tests' fixtures
        // with it.
        postings.findAll().stream()
                .filter(posting -> "camunda-test".equals(posting.getBoardToken()))
                .forEach(postings::delete);
    }

    private ApplicationField store(String label, String conceptId, FieldState state,
            AutomationState automation, boolean required, String value) {
        ApplicationField field = new ApplicationField(attemptId, label, state);
        field.setConceptId(conceptId);
        field.setRequired(required);
        field.setResolvedValue(value);
        field.setAutomationState(automation);
        field.setSource(value == null ? null : KnowledgeSource.PROFILE);
        return fields.save(field);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("progress carries the stage, the counts and what is still outstanding")
    void progressReportsWhatIsLeft() throws Exception {
        store("First name", Concepts.FIRST_NAME.id(), FieldState.RESOLVED,
                AutomationState.FILLED, true, "Anurag");
        store("Kubernetes experience?", null, FieldState.AWAITING_ANSWER,
                AutomationState.NOT_ATTEMPTED, true, null);
        ApplicationField blocked = store("Earliest start date", Concepts.START_DATE.id(),
                FieldState.RESOLVED, AutomationState.NOT_ATTEMPTED, true, "2027-06-01");
        blocked.blockedBy("the date picker has no text input");
        fields.save(blocked);

        mvc.perform(get("/api/preparation/" + attemptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("FINISHED"))
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.statusLabel").value("Waiting on your answer"))
                .andExpect(jsonPath("$.readiness.total").value(3))
                .andExpect(jsonPath("$.readiness.filled").value(1))
                .andExpect(jsonPath("$.readiness.awaitingAnswer").value(1))
                .andExpect(jsonPath("$.readiness.automationBlocked").value(1))
                .andExpect(jsonPath("$.canOpen").value(false))
                .andExpect(jsonPath("$.readiness.blockers").isNotEmpty());
    }

    @Test
    @DisplayName("the two states are separate fields, so a client cannot merge them by accident")
    void fieldsCarryBothStates() throws Exception {
        ApplicationField blocked = store("Earliest start date", Concepts.START_DATE.id(),
                FieldState.RESOLVED, AutomationState.NOT_ATTEMPTED, true, "2027-06-01");
        blocked.blockedBy("the date picker has no text input");
        fields.save(blocked);

        mvc.perform(get("/api/preparation/" + attemptId + "/fields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("RESOLVED"))
                .andExpect(jsonPath("$[0].automationState").value("BLOCKED"))
                .andExpect(jsonPath("$[0].outcome").value("AUTOMATION_BLOCKED"))
                // The answer survives the page refusing it. That is the rule.
                .andExpect(jsonPath("$[0].answer").value("2027-06-01"))
                .andExpect(jsonPath("$[0].automationLabel")
                        .value("Known, but the website would not take it"));
    }

    @Test
    @DisplayName("an action carrying a version the row has moved past is refused, with a remedy")
    void staleActionsAreRefused() throws Exception {
        ApplicationField field = store("City", Concepts.CITY.id(), FieldState.RESOLVED,
                AutomationState.FILLED, true, "Mumbai");
        long staleVersion = field.getVersion();

        mvc.perform(post("/api/field/" + field.getId() + "/override")
                        .param("version", String.valueOf(staleVersion))
                        .param("value", "Navi Mumbai"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/field/" + field.getId() + "/override")
                        .param("version", String.valueOf(staleVersion))
                        .param("value", "Thane"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stale"))
                // Two sentences: what happened, and what to do about it.
                .andExpect(jsonPath("$.what").isNotEmpty())
                .andExpect(jsonPath("$.remedy").isNotEmpty());

        assertThat(fields.findById(field.getId()).orElseThrow().getResolvedValue())
                .isEqualTo("Navi Mumbai");
    }

    @Test
    @DisplayName("a forbidden scope is refused by the server, whatever the client sent")
    void forbiddenScopesAreRefusedServerSide() throws Exception {
        ApplicationField field = store("Will you require visa sponsorship?",
                Concepts.SPONSORSHIP_REQUIRED.id(), FieldState.AWAITING_ANSWER,
                AutomationState.NOT_ATTEMPTED, true, null);

        mvc.perform(post("/api/field/" + field.getId() + "/answer")
                        .param("version", String.valueOf(field.getVersion()))
                        .param("value", "Yes")
                        .param("scopeLevel", "GLOBAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unsafe"));

        // Not narrowed quietly. Nothing was stored at all.
        assertThat(assertions.findAll()).isEmpty();
    }

    @Test
    @DisplayName("the offered scopes come from the backend and exclude the unsafe one")
    void scopesAreDecidedByTheServer() throws Exception {
        ApplicationField field = store("Will you require visa sponsorship?",
                Concepts.SPONSORSHIP_REQUIRED.id(), FieldState.AWAITING_ANSWER,
                AutomationState.NOT_ATTEMPTED, true, null);

        mvc.perform(get("/api/field/" + field.getId() + "/scopes"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"level\":\"GLOBAL\""))))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("Germany")));
    }

    @Test
    @DisplayName("the form cannot be opened while a required question has no answer")
    void openingIsRefusedWhileSomethingIsUnanswered() throws Exception {
        store("Kubernetes experience?", null, FieldState.AWAITING_ANSWER,
                AutomationState.NOT_ATTEMPTED, true, null);

        mvc.perform(post("/attempt/" + attemptId + "/open"))
                .andExpect(status().is3xxRedirection());

        // Nothing was queued: the attempt is where it was, not preparing.
        assertThat(attempts.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(AttemptStatus.AWAITING_ANSWER);
    }

    @Test
    @DisplayName("a board that needs a person can be opened even with a question outstanding")
    void theManualPathMayOpenAnyway() throws Exception {
        store("Kubernetes experience?", null, FieldState.AWAITING_ANSWER,
                AutomationState.NOT_ATTEMPTED, true, null);
        ApplicationAttempt attempt = attempts.findById(attemptId).orElseThrow();
        attempt.setManualReason(ManualReason.CAPTCHA);
        attempts.save(attempt);

        mvc.perform(get("/api/preparation/" + attemptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canOpen").value(true))
                .andExpect(jsonPath("$.manualReason").value("CAPTCHA"))
                .andExpect(jsonPath("$.manualExplanation").value("This form has a captcha"))
                .andExpect(jsonPath("$.valuesReusable").value(true));
    }

    @Test
    @DisplayName("the explanation comes from the record, not from what is on screen")
    void explanationsAreStructured() throws Exception {
        ApplicationField field = store("Will you require visa sponsorship?",
                Concepts.SPONSORSHIP_REQUIRED.id(), FieldState.RESOLVED,
                AutomationState.FILLED, true, "Yes");

        mvc.perform(get("/api/field/" + field.getId() + "/explain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conceptId").value("sponsorship.required"))
                .andExpect(jsonPath("$.contextSensitive").value(true))
                .andExpect(jsonPath("$.context").value(
                        org.hamcrest.Matchers.containsString("Germany")))
                .andExpect(jsonPath("$.answer").value("Yes"))
                .andExpect(jsonPath("$.automationState").value("FILLED"));
    }

    @Test
    @DisplayName("the page shows unknown and manual as two different things")
    void thePageKeepsUnknownAndManualApart() throws Exception {
        store("Will you require visa sponsorship?", Concepts.SPONSORSHIP_REQUIRED.id(),
                FieldState.AWAITING_ANSWER, AutomationState.NOT_ATTEMPTED, true, null);
        ApplicationField blocked = store("Earliest start date", Concepts.START_DATE.id(),
                FieldState.RESOLVED, AutomationState.NOT_ATTEMPTED, true, "2027-06-01");
        blocked.blockedBy("the date picker has no text input");
        fields.save(blocked);

        String page = mvc.perform(get("/attempt/" + attemptId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Two headings, two different sentences. The distinction is the point:
        // one is answerable from this screen and the other needs a browser.
        assertThat(page).contains("We need your input");
        assertThat(page).contains("The website would not fill these");
        // And two different colours, which is the other half of saying so.
        assertThat(page).contains("edge-bad").contains("edge-manual");
        // The blocked field's answer is shown, to be copied.
        assertThat(page).contains("2027-06-01");
        // The scope picker is rendered from the backend's list, so the option
        // the concept forbids is not on the page at all.
        assertThat(page).contains("Save it for future applications?");
        assertThat(page).contains("Save this answer for future applications in Germany?");
        assertThat(page).doesNotContain("every application, everywhere");
        // And nothing offers to submit while a question is open.
        assertThat(page).doesNotContain("Submit to Camunda");
    }

    @Test
    @DisplayName("an attempt that does not exist is a 404, not a stack trace")
    void unknownAttemptsAreNotFound() throws Exception {
        mvc.perform(get("/api/preparation/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/attempt/999999"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("No attempt 999999")));
    }
}
