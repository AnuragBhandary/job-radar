package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.apply.form.FillReport;
import java.nio.file.Path;

/**
 * What one {@code apply} run produced.
 *
 * @param reviewFile the human-readable summary written to disk. The actual
 *                   deliverable of a prepare run - the browser window closes, and
 *                   this is what is left to decide from.
 */
public record ApplyOutcome(
        ApplicationAttempt attempt,
        FillReport report,
        Path reviewFile,
        String message) {

    public boolean submitted() {
        return attempt.getStatus() == AttemptStatus.SUBMITTED;
    }
}
