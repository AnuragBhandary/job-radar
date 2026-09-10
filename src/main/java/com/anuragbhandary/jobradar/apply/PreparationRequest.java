package com.anuragbhandary.jobradar.apply;

import java.util.function.BooleanSupplier;

/**
 * What one run of {@link ApplyService#apply} is being asked to do.
 *
 * <p>A record rather than four more parameters. The call already carried two
 * flags whose combination decides whether an application is sent, and adding
 * "resume this attempt", "use these answers" and "leave the window open" as
 * positional booleans is how a caller ends up submitting by accident.
 *
 * @param resumeAttemptId the attempt to continue rather than start. Its rendered
 *                        resume and its drafted letter are reused where they are
 *                        still on disk, which is what {@link #preserveWork} means
 *                        in practice: preparation picks up rather than starting
 *                        over.
 * @param settled         answers the applicant has already decided on, which
 *                        override anything the mapper would work out
 * @param leaveOpen       keep the browser on screen when filling is done, for a
 *                        form that has to be finished by hand. Never submits -
 *                        there is no path from here to {@code Submitter} that
 *                        does not go through {@link #confirmSubmit}.
 * @param wantsSubmit     whether the caller intends to submit at all
 * @param confirmSubmit   asked once, after the form is filled. Supplying a
 *                        supplier that always returns true is possible and is the
 *                        documented way to get this wrong.
 * @param reporter        where progress is written. Never null; use
 *                        {@link StageReporter#NONE}.
 */
public record PreparationRequest(
        Long resumeAttemptId,
        PreparedAnswers settled,
        boolean leaveOpen,
        boolean wantsSubmit,
        BooleanSupplier confirmSubmit,
        StageReporter reporter) {

    public PreparationRequest {
        settled = settled == null ? PreparedAnswers.none() : settled;
        confirmSubmit = confirmSubmit == null ? () -> false : confirmSubmit;
        reporter = reporter == null ? StageReporter.NONE : reporter;
    }

    /** A first preparation, nothing sent. */
    public static PreparationRequest fresh() {
        return new PreparationRequest(null, PreparedAnswers.none(), false, false,
                () -> false, StageReporter.NONE);
    }

    /** Continues an attempt, keeping the work it already did. */
    public static PreparationRequest resuming(Long attemptId, PreparedAnswers settled,
            StageReporter reporter) {
        return new PreparationRequest(attemptId, settled, false, false, () -> false, reporter);
    }

    /** Re-fills the form and leaves it on screen for a person to finish. */
    public static PreparationRequest opening(Long attemptId, PreparedAnswers settled,
            StageReporter reporter) {
        return new PreparationRequest(attemptId, settled, true, false,
                () -> false, reporter);
    }

    /** True when there is an earlier attempt whose documents can be reused. */
    public boolean preserveWork() {
        return resumeAttemptId != null;
    }

    public PreparationRequest withReporter(StageReporter newReporter) {
        return new PreparationRequest(resumeAttemptId, settled, leaveOpen, wantsSubmit,
                confirmSubmit, newReporter);
    }
}
