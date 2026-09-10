package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.AttemptStatus;
import com.anuragbhandary.jobradar.apply.ManualReason;
import com.anuragbhandary.jobradar.apply.PreparationStage;
import com.anuragbhandary.jobradar.apply.Readiness;
import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.Posting;
import java.util.List;
import java.util.Locale;

/**
 * Where one preparation has got to, for a page that is watching it.
 *
 * <p>Everything a progress display needs and nothing else: no resume path, no
 * screenshot path, no cover letter. This is polled every second or two while a
 * browser works, so what it does not carry matters as much as what it does -
 * including the rule that it carries no field values at all.
 *
 * @param running   whether this process is working on it now. False for an
 *                  attempt stuck at a stage because the application restarted
 *                  under it, which is why the page offers to start it again
 *                  rather than polling something that will never move.
 * @param error     what went wrong, in a sentence. Never a stack trace.
 * @param hasScreenshot whether the captured image is still on disk. Checked here
 *                  rather than assumed from the path, because an attempt's output
 *                  directory outlives nothing in particular.
 */
public record PreparationView(
        long attemptId,
        Long postingId,
        String company,
        String role,
        String location,
        String country,
        String workMode,
        String strategicClass,
        String status,
        String statusLabel,
        String stage,
        String stageLabel,
        String stageDetail,
        int step,
        int steps,
        boolean running,
        boolean resumable,
        boolean canOpen,
        String manualReason,
        String manualExplanation,
        String manualDetail,
        boolean valuesReusable,
        boolean hasScreenshot,
        String error,
        ReadinessView readiness) {

    /** The counts, flattened, with the two derived lines the page shows. */
    public record ReadinessView(
            int total,
            int prepared,
            int filled,
            int ready,
            int automationBlocked,
            int awaitingApproval,
            int awaitingAnswer,
            int skippedOptional,
            int declined,
            int conflicts,
            boolean nothingOutstanding,
            String summary,
            List<String> blockers) {

        static ReadinessView of(Readiness readiness) {
            return new ReadinessView(readiness.total(), readiness.prepared(),
                    readiness.filled(), readiness.ready(), readiness.automationBlocked(),
                    readiness.awaitingApproval(), readiness.awaitingAnswer(),
                    readiness.skippedOptional(), readiness.declined(), readiness.conflicts(),
                    readiness.nothingOutstanding(), readiness.summary(),
                    readiness.blockers());
        }
    }

    public static PreparationView of(ApplicationAttempt attempt, Posting posting,
            Readiness readiness, boolean running) {

        PreparationStage stage = attempt.getStage();
        ManualReason manual = attempt.getManualReason();
        return new PreparationView(
                attempt.getId(),
                attempt.getPostingId(),
                attempt.getCompany(),
                attempt.getRole(),
                posting == null ? null : posting.getLocation(),
                posting == null || posting.getCountryCode() == null ? null
                        : CountryCodes.displayName(posting.getCountryCode()),
                posting == null || posting.getWorkMode() == null ? null
                        : readable(posting.getWorkMode().name()),
                posting == null || posting.getStrategicClass() == null ? null
                        : readable(posting.getStrategicClass().name()),
                attempt.getStatus().name(),
                statusLabel(attempt.getStatus()),
                stage == null ? null : stage.name(),
                stage == null ? null : stage.label(),
                attempt.getStageDetail(),
                stage == null ? 0 : stage.step(),
                PreparationStage.steps(),
                running,
                attempt.isResumable(),
                readiness.canOpen(manual),
                manual == null ? null : manual.name(),
                manual == null ? null : manual.explanation(),
                manual == null ? null : manual.detail(),
                manual != null && manual.valuesReusable(),
                // The path is a record of what was captured and the file can be
                // gone - moved, cleaned up, written by a run whose output
                // directory no longer exists. Rendering the frame anyway gave
                // five hundred pixels of broken image, which reads as the page
                // being broken rather than the file.
                attempt.getScreenshotPath() != null
                        && java.nio.file.Files.exists(
                                java.nio.file.Path.of(attempt.getScreenshotPath())),
                attempt.getStatus() == AttemptStatus.FAILED ? attempt.getBlockerReason() : null,
                ReadinessView.of(readiness));
    }

    /**
     * The status in words.
     *
     * <p>Deliberately never "failed" for a preparation that worked and stopped
     * because a website wants a person. That conflation is the one this phase
     * exists to undo.
     */
    private static String statusLabel(AttemptStatus status) {
        return switch (status) {
            case PREPARING -> "Preparing";
            case READY_FOR_REVIEW -> "Ready for your review";
            case AWAITING_APPROVAL -> "Waiting on your approval";
            case AWAITING_ANSWER -> "Waiting on your answer";
            case MANUAL_REQUIRED -> "Needs you at the website";
            case PREPARED -> "Prepared";
            case NEEDS_HUMAN -> "Needs you";
            case SUBMITTED -> "Submitted";
            case FAILED -> "Stopped";
            case SKIPPED -> "Skipped";
        };
    }

    private static String readable(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
