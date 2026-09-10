package com.anuragbhandary.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Starting, queueing and picking up again - without a browser.
 *
 * <p>The part of asynchronous preparation that can be tested is the part before
 * the browser opens: an attempt row has to exist, immediately, with a stage on
 * it, so the screen the caller is redirected to has something to show. What
 * happens inside the browser needs a browser and a person, and is not what breaks.
 *
 * <p>Nothing here queues real work. Every call is either one that only touches
 * the database, or one that is refused before it reaches the worker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PreparationLifecycleTest {

    @Autowired
    private ApplyService applications;
    @Autowired
    private PreparationRunner runner;
    @Autowired
    private ApplicationAttemptRepository attempts;
    @Autowired
    private PostingRepository postings;

    private Posting posting;

    @BeforeEach
    void setUp() {
        clean();
        Posting fixture = new Posting(Source.GREENHOUSE, "lifecycle-test", "req-life",
                "Backend Engineer");
        fixture.setUrl("https://example.invalid/life");
        fixture.setLocation("Berlin, Germany");
        fixture.setCountryCode("DE");
        fixture.setFirstSeen(Instant.now());
        fixture.setLastSeen(Instant.now());
        fixture.setStatus(PostingStatus.NEW);
        fixture.setVerdict(Verdict.CANDIDATE);
        posting = postings.save(fixture);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        postings.findAll().stream()
                .filter(row -> "lifecycle-test".equals(row.getBoardToken()))
                .forEach(row -> {
                    attempts.findAll().stream()
                            .filter(attempt -> row.getId().equals(attempt.getPostingId()))
                            .forEach(attempts::delete);
                    postings.delete(row);
                });
    }

    @Test
    @DisplayName("an attempt exists before any browser does, so there is a page to redirect to")
    void theAttemptRowComesFirst() {
        ApplicationAttempt attempt = applications.beginAttempt(posting);

        assertThat(attempt.getId()).isNotNull();
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.PREPARING);
        assertThat(attempt.getStage()).isEqualTo(PreparationStage.QUEUED);
        assertThat(attempt.getStageUpdatedAt()).isNotNull();
        // Not running yet: this process has not picked it up. The screen offers
        // to start it rather than polling something that will never move.
        assertThat(runner.isRunning(attempt.getId())).isFalse();
    }

    @Test
    @DisplayName("a posting already applied to gets a skipped attempt, not a browser")
    void alreadySubmittedPostingsAreSkipped() {
        ApplicationAttempt sent =
                new ApplicationAttempt(posting.getId(), "Camunda", "Backend Engineer");
        sent.setStatus(AttemptStatus.SUBMITTED);
        sent.setFinishedAt(Instant.now());
        attempts.save(sent);

        ApplicationAttempt second = applications.beginAttempt(posting);

        assertThat(second.getStatus()).isEqualTo(AttemptStatus.SKIPPED);
        assertThat(second.getBlockerReason()).contains("already submitted");
        assertThat(second.getStage()).isEqualTo(PreparationStage.FINISHED);
    }

    @Test
    @DisplayName("requeueing clears the last run's blocker so the new one is not read as stale")
    void requeueClearsTheLastRunsVerdict() {
        ApplicationAttempt attempt =
                new ApplicationAttempt(posting.getId(), "Camunda", "Backend Engineer");
        attempt.setStatus(AttemptStatus.MANUAL_REQUIRED);
        attempt.setManualReason(ManualReason.CAPTCHA);
        attempt.setBlockerReason("this form has a captcha");
        attempt.setFinishedAt(Instant.now());
        attempt.setResumePath("/tmp/does-not-matter.pdf");
        ApplicationAttempt saved = attempts.save(attempt);

        ApplicationAttempt requeued = applications.requeue(saved);

        assertThat(requeued.getStatus()).isEqualTo(AttemptStatus.PREPARING);
        assertThat(requeued.getStage()).isEqualTo(PreparationStage.QUEUED);
        assertThat(requeued.getManualReason()).isNull();
        assertThat(requeued.getBlockerReason()).isNull();
        assertThat(requeued.getFinishedAt()).isNull();
        // What it already produced is kept. That is the difference between
        // resuming and starting over.
        assertThat(requeued.getResumePath()).isEqualTo("/tmp/does-not-matter.pdf");
    }

    @Test
    @DisplayName("a submitted attempt is never picked up again")
    void submittedAttemptsAreNotResumable() {
        ApplicationAttempt sent =
                new ApplicationAttempt(posting.getId(), "Camunda", "Backend Engineer");
        sent.setStatus(AttemptStatus.SUBMITTED);
        sent.setStage(PreparationStage.FINISHED, "sent");
        ApplicationAttempt saved = attempts.save(sent);

        assertThat(saved.isResumable()).isFalse();

        // Handed back unchanged rather than queued. An application cannot be
        // unsent, and re-preparing one that was is the shape of a mistake nobody
        // recovers from.
        Optional<ApplicationAttempt> result = runner.resume(saved.getId());
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(runner.isRunning(saved.getId())).isFalse();
    }

    @Test
    @DisplayName("an attempt still mid-run is not picked up a second time")
    void runningAttemptsAreNotRequeued() {
        ApplicationAttempt running =
                new ApplicationAttempt(posting.getId(), "Camunda", "Backend Engineer");
        running.setStatus(AttemptStatus.PREPARING);
        running.setStage(PreparationStage.FILLING, "entering the answers");
        ApplicationAttempt saved = attempts.save(running);

        assertThat(saved.isResumable()).isFalse();
        assertThat(runner.resume(saved.getId())).isPresent();
        assertThat(attempts.findById(saved.getId()).orElseThrow().getStage())
                .isEqualTo(PreparationStage.FILLING);
    }

    @Test
    @DisplayName("resuming an attempt that does not exist is empty, not an exception")
    void unknownAttemptsAreEmpty() {
        assertThat(runner.resume(999_999L)).isEmpty();
        assertThat(runner.open(999_999L)).isEmpty();
        assertThat(runner.isRunning(null)).isFalse();
    }

    @Test
    @DisplayName("the stages say whether anything is still happening, and how far along")
    void stagesReportProgress() {
        assertThat(PreparationStage.QUEUED.isRunning()).isTrue();
        assertThat(PreparationStage.FILLING.isRunning()).isTrue();
        assertThat(PreparationStage.FINISHED.isRunning()).isFalse();
        assertThat(PreparationStage.FAILED.isRunning()).isFalse();

        assertThat(PreparationStage.QUEUED.step()).isZero();
        assertThat(PreparationStage.TAILORING.step()).isEqualTo(1);
        assertThat(PreparationStage.CAPTURED.step()).isEqualTo(PreparationStage.steps());
        assertThat(PreparationStage.FINISHED.step()).isEqualTo(PreparationStage.steps());
        // Each one says what it is doing, because a bar that stops says only that
        // something stopped.
        assertThat(PreparationStage.OPENING.label()).isEqualTo("Opening the application");
    }
}
