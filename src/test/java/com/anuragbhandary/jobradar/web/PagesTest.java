package com.anuragbhandary.jobradar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.AttemptStatus;
import com.anuragbhandary.jobradar.apply.ManualReason;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.domain.WorkMode;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * Every page renders, and says the things the redesign is for.
 *
 * <p>Not screenshot tests - those would assert on pixels and break on every
 * spacing change. These assert on the handful of statements each page exists to
 * make: that a captcha is not called an unanswered question, that the home page
 * does not lead with how many rows the scraper has seen, that a filter which
 * finds nothing offers a way out, and that the navigation is the same seven
 * sections everywhere.
 *
 * <p>Not transactional. Every action in the running application commits at the
 * end of its request, and a test wrapped in one long transaction would not see
 * what a second request sees.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PagesTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PostingRepository postings;
    @Autowired
    private ApplicationAttemptRepository attempts;

    private Long postingId;

    @BeforeEach
    void setUp() {
        clean();
        Posting berlin = new Posting(Source.GREENHOUSE, "pages-test", "req-pages",
                "Backend Engineer");
        berlin.setUrl("https://example.invalid/pages");
        berlin.setLocation("Berlin, Germany");
        berlin.setCountryCode("DE");
        berlin.setEmployerCountryCode("DE");
        berlin.setWorkMode(WorkMode.ONSITE);
        berlin.setStrategicClass(StrategicClass.INTERNATIONAL_RELOCATION);
        berlin.setPostedDate(LocalDate.now().minusDays(2));
        berlin.setFirstSeen(Instant.now());
        berlin.setLastSeen(Instant.now());
        berlin.setStatus(PostingStatus.NEW);
        berlin.setVerdict(Verdict.CANDIDATE);
        postingId = postings.save(berlin).getId();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        postings.findAll().stream()
                .filter(row -> "pages-test".equals(row.getBoardToken()))
                .forEach(row -> {
                    attempts.findAll().stream()
                            .filter(attempt -> row.getId().equals(attempt.getPostingId()))
                            .forEach(attempts::delete);
                    postings.delete(row);
                });
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private ApplicationAttempt attempt(AttemptStatus status, ManualReason reason) {
        ApplicationAttempt attempt =
                new ApplicationAttempt(postingId, "Camunda", "Backend Engineer");
        attempt.setStatus(status);
        attempt.setManualReason(reason);
        attempt.setBlockerReason("this form has a captcha");
        return attempts.save(attempt);
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every page carries the same seven sections and marks the current one")
    void navigationIsConsistent() throws Exception {
        for (String url : List.of("/", "/jobs", "/board", "/knowledge", "/strategy")) {
            String html = page(url);
            assertThat(html)
                    .as("navigation on " + url)
                    .contains(">Today<").contains(">Jobs<").contains(">Applications<")
                    .contains(">Knowledge<").contains(">Strategy<")
                    .contains(">Assistant<").contains(">Settings<");
            // Said to a screen reader as well as drawn, so "where am I" is
            // answerable without looking at the underline.
            assertThat(html).as("current section on " + url).contains("aria-current=\"page\"");
            // And a way past seven links for anyone arriving by keyboard.
            assertThat(html).as("skip link on " + url).contains("class=\"skip\"");
        }
    }

    // ------------------------------------------------------------------
    // Today
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a captcha is a manual task, not a question to answer")
    void theQueueNamesTheRightAction() throws Exception {
        attempt(AttemptStatus.MANUAL_REQUIRED, ManualReason.CAPTCHA);

        String html = page("/");

        assertThat(html).contains("Manual application");
        assertThat(html).contains("This form has a captcha");
        // The bug this replaced: the home page told him to answer a captcha,
        // because a captcha and an unanswerable question were the same kind.
        assertThat(html).doesNotContain("Answer it</a>");
    }

    @Test
    @DisplayName("one application produces one queue row, however many times it was tried")
    void repeatedAttemptsDoNotDuplicate() throws Exception {
        attempt(AttemptStatus.MANUAL_REQUIRED, ManualReason.CAPTCHA);
        attempt(AttemptStatus.MANUAL_REQUIRED, ManualReason.CAPTCHA);

        String html = page("/");

        // Twice in the DOM would mean two identical rows offering the same work.
        assertThat(html.split("This form has a captcha", -1).length - 1).isEqualTo(1);
    }

    @Test
    @DisplayName("the home page leads with outcomes, not with how many rows were scraped")
    void theMetricsAreOutcomes() throws Exception {
        String html = page("/");

        assertThat(html).contains("Applications sent").contains("Replies")
                .contains("In process").contains("New this week");
        // "Screened, all time" was the largest number on this page and measured
        // the scraper rather than the search.
        assertThat(html).doesNotContain(">Screened<");
    }

    @Test
    @DisplayName("the strategy lanes are on the home page, with live counts")
    void theStrategyIsVisible() throws Exception {
        String html = page("/");

        assertThat(html).contains("International — Relocation")
                .contains("Primary objective")
                .contains("Financial safety net");
    }

    // ------------------------------------------------------------------
    // Jobs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("jobs filters on the structured columns, not the five-value legacy enum")
    void jobsFiltersOnRealData() throws Exception {
        String html = page("/jobs");

        assertThat(html).contains("Strategic lane").contains("Work mode")
                .contains("Freshness");
        // The old filter row bucketed every British, Australian and Canadian
        // role under "Other". Countries are ISO codes now and name themselves.
        assertThat(html).contains("Germany");
    }

    @Test
    @DisplayName("a lane filter narrows the list and stays selected")
    void laneFilterWorks() throws Exception {
        String html = page("/jobs?lane=INTERNATIONAL_RELOCATION");

        assertThat(html).contains("filter is-on");
        assertThat(html).contains("Backend Engineer");
    }

    @Test
    @DisplayName("filters that match nothing offer a way back rather than a blank page")
    void emptyFiltersExplainThemselves() throws Exception {
        String html = page("/jobs?country=ZZ");

        assertThat(html).contains("Nothing matches those filters.");
        assertThat(html).contains("Clear all filters");
    }

    @Test
    @DisplayName("the action on a job says what pressing it will do")
    void theCallToActionReflectsState() throws Exception {
        assertThat(page("/jobs")).contains(">Prepare<");

        attempt(AttemptStatus.AWAITING_ANSWER, null);
        assertThat(page("/jobs")).contains(">Resolve<");
    }

    // ------------------------------------------------------------------
    // Strategy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the strategy page shows the tiers, and says where there is no floor")
    void strategyShowsWhatIsKnownAndWhatIsNot() throws Exception {
        String html = page("/strategy");

        assertThat(html).contains("Primary").contains("Move here.");
        assertThat(html).contains("Will relocate").contains("Remote OK");
        // A country with no verified threshold is not a country with a threshold
        // of zero, and the page says so rather than omitting the row.
        assertThat(html).contains("No floor established");
    }

    @Test
    @DisplayName("relocation and remote are shown as two separate decisions")
    void relocationAndRemoteAreSeparate() throws Exception {
        String html = page("/strategy");

        // The United States is the case worth reading: excluded for relocation
        // and enabled for remote, which the pre-country model could not express.
        assertThat(html).contains("Not relocating");
        assertThat(html).contains("Remote from these employers may still be.");
    }

    // ------------------------------------------------------------------
    // Knowledge
    // ------------------------------------------------------------------

    @Test
    @DisplayName("knowledge separates what needs a decision from what is in use")
    void knowledgeIsStructured() throws Exception {
        String html = page("/knowledge");

        assertThat(html).contains("Needs review");
        assertThat(html).contains("What Job Radar knows about you");
    }
}
