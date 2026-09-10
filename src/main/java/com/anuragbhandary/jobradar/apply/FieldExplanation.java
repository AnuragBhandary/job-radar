package com.anuragbhandary.jobradar.apply;

import java.util.List;

/**
 * Why one field says what it says.
 *
 * <p>Assembled from the record and from the resolver, never from what is on
 * screen. That distinction is the whole point: the old review page reconstructed
 * its "Check these" panel by parsing the prose of {@code FillReport.Entry.describe()}
 * with {@code lastIndexOf("   [")}, so the explanation could only ever be as good
 * as a string that had already been formatted for a terminal - and any change to
 * the wording silently changed the explanation.
 *
 * @param context        the application context in one line: which country's rules
 *                       apply, which work mode, which employer. The part of the
 *                       reasoning a person can actually check.
 * @param evidence       what was read to reach the answer, one line each
 * @param alsoApplied    knowledge that applied and lost, so "why not my German
 *                       rule?" has an answer
 * @param conflict       the competing values when two equally applicable answers
 *                       disagree. Empty otherwise, and never resolved by picking.
 * @param secondOpinion  what the new resolver would say about the same question.
 *                       Shown, never used: it is not authoritative until its own
 *                       gate, and this is how the difference stays visible.
 * @param originalValue  what was resolved before the applicant overrode it
 * @param sourceLabel    the source in words. {@code source} is the enum name, for
 *                       a client to switch on; a panel that showed "PROFILE" to a
 *                       person would be dumping an implementation detail into an
 *                       explanation.
 */
public record FieldExplanation(
        String question,
        String conceptId,
        String conceptLabel,
        boolean contextSensitive,
        String answer,
        String source,
        String sourceLabel,
        String confidence,
        String explanation,
        String context,
        List<String> evidence,
        List<String> alsoApplied,
        List<String> conflict,
        String automationState,
        String failureReason,
        boolean userEdited,
        String originalValue,
        String originalSource,
        String secondOpinion) {

    public FieldExplanation {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        alsoApplied = alsoApplied == null ? List.of() : List.copyOf(alsoApplied);
        conflict = conflict == null ? List.of() : List.copyOf(conflict);
    }

    public boolean hasConflict() {
        return !conflict.isEmpty();
    }
}
