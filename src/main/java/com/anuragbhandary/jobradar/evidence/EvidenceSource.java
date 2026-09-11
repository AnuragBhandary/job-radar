package com.anuragbhandary.jobradar.evidence;

import java.util.List;

/**
 * Where a piece of evidence comes from: one job, or one project.
 *
 * @param name  the company or project name exactly as the resume writes it. This is
 *              how the planner finds the header - title, dates, stack line - to print
 *              above the evidence, so a mismatch is reported rather than guessed at.
 * @param stack technologies true of the whole source. An item may list one of these
 *              without its own sentence naming it; nothing is inherited
 *              automatically. When a resume is loaded, every entry must be backed by
 *              it - the project's stack line, or the tags on that job's bullets.
 * @param note  for whoever reads the file. Never rendered and never matched.
 */
public record EvidenceSource(String id, Kind kind, String name, List<String> stack, String note) {

    public enum Kind {
        /** A job. Ranked slightly above project work for the same match. */
        EMPLOYMENT,
        /** A personal project. */
        PROJECT
    }

    public EvidenceSource {
        stack = EvidenceItem.clean(stack);
    }
}
