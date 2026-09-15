package com.anuragbhandary.jobradar.evidence;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Where a piece of evidence comes from: one job, or one project.
 *
 * <p>The bank owns a project outright - its name, its stack line and its bullets.
 * A job is shared: applicant.yml owns its title, dates, location and arrangement
 * note, and this source owns what was done there. The two are joined by name.
 *
 * @param name      the company as applicant.yml writes it, or the project's name as
 *                  it is printed. A job is found by this name, so a mismatch is
 *                  reported rather than guessed at.
 * @param stack     technologies true of the whole source. An item may list one of
 *                  these without its own sentence naming it; nothing is inherited
 *                  automatically.
 * @param stackLine how a project's stack is printed on the resume, when it is not
 *                  simply the stack joined with " · " - "Java 25 · SQLite/JPA". It
 *                  may name nothing that is not in the stack, and must name
 *                  everything that is.
 * @param note      for whoever reads the file. Never printed and never matched.
 */
public record EvidenceSource(
        String id,
        Kind kind,
        String name,
        List<String> stack,
        @JsonProperty("stack-line") String stackLine,
        String note) {

    public enum Kind {
        /** A job. Ranked slightly above project work for the same match. */
        EMPLOYMENT,
        /** A personal project. */
        PROJECT
    }

    public EvidenceSource {
        stack = EvidenceItem.clean(stack);
        stackLine = stackLine == null || stackLine.isBlank() ? null : stackLine.strip();
    }

    /** The stack as the resume prints it. */
    public String displayStack() {
        return stackLine != null ? stackLine : String.join(" · ", stack);
    }
}
