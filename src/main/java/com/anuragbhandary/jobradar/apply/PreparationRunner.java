package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs preparation behind the request that asked for it.
 *
 * <p>{@code POST /prepare} used to hold the connection open for the forty seconds
 * it takes to render a PDF, launch Chromium, read a form and fill it. That was
 * defensible while a person was watching the browser window do the work and
 * indefensible the moment there was a screen worth showing instead: a button that
 * looks untouched for forty seconds reads as a page that has died.
 *
 * <h2>One at a time, on purpose</h2>
 * A single worker thread, and applications queue behind each other. Not a
 * limitation to be lifted later: the browser profile is one persistent Chromium
 * user-data directory holding live board sessions, and two runs against it at
 * once is not slow, it is corrupt. Queuing is also honest about the machine -
 * each run is a quarter of a gigabyte of browser.
 *
 * <p>No job table, no scheduler, no message broker. The queue is an
 * {@link ExecutorService} and the progress is a column on the row the work is
 * about, which is where a screen was going to read it from anyway.
 *
 * <h2>What survives a restart</h2>
 * The attempt row and its stage, because they are in the database. The queue does
 * not: a run interrupted by a restart leaves an attempt stuck at whichever stage
 * it reached, which {@link #isRunning} reports as not running - so the screen
 * offers to start it again rather than polling something that will never move.
 */
@Service
public class PreparationRunner {

    private static final Logger log = LoggerFactory.getLogger(PreparationRunner.class);

    private final ApplyService applications;
    private final PostingRepository postings;
    private final ApplicationAttemptRepository attempts;
    private final ApplicationFieldRepository fields;
    private final ExecutorService worker;
    /** Attempt ids this process is working on right now. */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public PreparationRunner(ApplyService applications, PostingRepository postings,
            ApplicationAttemptRepository attempts, ApplicationFieldRepository fields) {
        this.applications = applications;
        this.postings = postings;
        this.attempts = attempts;
        this.fields = fields;
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "preparation");
            // Daemon: a queued application must never be the reason the process
            // will not exit. The CLI runs in the same JVM.
            thread.setDaemon(true);
            return thread;
        };
        this.worker = Executors.newSingleThreadExecutor(factory);
    }

    /**
     * Starts a first preparation and returns at once.
     *
     * @return the attempt to send the browser to. Already saved, so the caller can
     *         redirect to a page that exists.
     */
    public ApplicationAttempt start(Posting posting) {
        ApplicationAttempt attempt = applications.beginAttempt(posting);
        if (attempt.getStatus() == AttemptStatus.SKIPPED) {
            return attempt;
        }
        submit(attempt.getId(), posting.getId(), PreparationRequest.fresh()
                .withReporter(StageReporter.NONE), "prepare");
        return attempt;
    }

    /**
     * Picks an attempt up where it stopped.
     *
     * <p>Everything already settled goes back in - see {@link PreparedAnswers} -
     * and the rendered resume and drafted letter are reused. What is redone is the
     * part that cannot be carried: reading and filling a form whose page closed.
     */
    public Optional<ApplicationAttempt> resume(Long attemptId) {
        return queue(attemptId, false);
    }

    /**
     * Re-fills the form and leaves it on screen for the applicant to finish.
     *
     * <p>The end of the workflow, and the thing that is deliberately not called
     * "submit". Nothing on this path can send an application: it fills, it
     * screenshots, and it waits for the window to be closed.
     */
    public Optional<ApplicationAttempt> open(Long attemptId) {
        return queue(attemptId, true);
    }

    private Optional<ApplicationAttempt> queue(Long attemptId, boolean leaveOpen) {
        Optional<ApplicationAttempt> found = attempts.findById(attemptId);
        if (found.isEmpty() || isRunning(attemptId) || !found.get().isResumable()) {
            return found;
        }
        ApplicationAttempt attempt = applications.requeue(found.get());
        PreparedAnswers settled = PreparedAnswers.from(
                fields.findByAttemptIdOrderByIdAsc(attemptId));
        PreparationRequest request = leaveOpen
                ? PreparationRequest.opening(attemptId, settled, StageReporter.NONE)
                : PreparationRequest.resuming(attemptId, settled, StageReporter.NONE);
        submit(attemptId, attempt.getPostingId(), request, leaveOpen ? "open" : "resume");
        return Optional.of(attempt);
    }

    /** True while this process is actually working on that attempt. */
    public boolean isRunning(Long attemptId) {
        return attemptId != null && running.contains(attemptId);
    }

    // ------------------------------------------------------------------

    private void submit(Long attemptId, Long postingId, PreparationRequest request, String what) {
        running.add(attemptId);
        try {
            worker.submit(() -> run(attemptId, postingId, request, what));
        } catch (RuntimeException e) {
            // The executor refused it - shutting down. The attempt must not be
            // left claiming to be running, because nothing would ever clear it.
            running.remove(attemptId);
            markFailed(attemptId, "could not be queued: " + e.getMessage());
            throw e;
        }
    }

    private void run(Long attemptId, Long postingId, PreparationRequest request, String what) {
        try {
            Optional<Posting> posting = postings.findById(postingId);
            if (posting.isEmpty()) {
                markFailed(attemptId, "posting " + postingId + " no longer exists");
                return;
            }
            log.info("Preparation {} starting for attempt {}", what, attemptId);
            ApplyOutcome outcome = applications.apply(posting.get(),
                    new PreparationRequest(attemptId, request.settled(), request.leaveOpen(),
                            false, () -> false, StageReporter.NONE));
            log.info("Preparation {} for attempt {} finished as {}", what, attemptId,
                    outcome.attempt().getStatus());
        } catch (RuntimeException | Error e) {
            // ApplyService catches its own exceptions and records them, so
            // reaching here means something went wrong outside the run itself.
            // It still has to leave a row that says so rather than a page that
            // polls a stage which will never move again.
            log.error("Preparation {} for attempt {} threw", what, attemptId, e);
            markFailed(attemptId, e.getMessage());
        } finally {
            running.remove(attemptId);
        }
    }

    private void markFailed(Long attemptId, String why) {
        try {
            attempts.findById(attemptId).ifPresent(attempt -> {
                attempt.setStatus(AttemptStatus.FAILED);
                attempt.setStage(PreparationStage.FAILED, "preparation could not run");
                attempt.setBlockerReason(why);
                attempt.setFinishedAt(Instant.now());
                attempts.save(attempt);
            });
        } catch (RuntimeException e) {
            log.warn("Could not record the failure of attempt {}: {}", attemptId,
                    e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdown();
        try {
            // Long enough for a fill in progress to finish and record itself,
            // short enough not to hang a shutdown. A run killed mid-fill leaves a
            // browser window open and an attempt stuck at FILLING, which is
            // recoverable; a shutdown that never completes is not.
            if (!worker.awaitTermination(20, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }
}
