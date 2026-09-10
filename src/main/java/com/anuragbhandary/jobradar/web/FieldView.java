package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.ApplicationField;
import com.anuragbhandary.jobradar.apply.FieldState;
import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.Concepts;
import com.anuragbhandary.jobradar.knowledge.Evidence;
import com.anuragbhandary.jobradar.knowledge.KnowledgeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One field, as the preparation screen needs it.
 *
 * <p>A response record rather than the entity. The entity carries an attempt id,
 * timestamps, a superseded link and a serialised evidence blob, none of which a
 * screen has any use for - and serialising it directly would make every column
 * name part of the interface, so renaming one would break the page.
 *
 * <h2>Two states, kept apart here too</h2>
 * {@link #state} is what Job Radar knows; {@link #automationState} is what the
 * browser managed. They are separate fields in the response for the same reason
 * they are separate columns: "we do not know the answer" and "the website would
 * not take it" are different problems with different fixes, and a single status
 * string would force a client to invent the distinction back.
 *
 * @param version  the token an action has to carry back. See
 *                 {@link com.anuragbhandary.jobradar.apply.FieldActions}.
 * @param actions  what may be done to this field right now, decided by the server
 * @param conflict two stored answers disagree about this question. Never resolved
 *                 by picking one.
 */
public record FieldView(
        long id,
        long version,
        String question,
        String conceptId,
        String conceptLabel,
        boolean contextSensitive,
        String answer,
        String source,
        String sourceLabel,
        String confidence,
        String state,
        String stateLabel,
        String automationState,
        String automationLabel,
        String outcome,
        boolean required,
        String controlType,
        List<String> options,
        String explanation,
        List<String> evidence,
        Long pendingAssertionId,
        String failureReason,
        boolean userEdited,
        String originalValue,
        boolean conflict,
        List<String> actions) {

    public static FieldView of(ApplicationField field, boolean conflict) {
        Optional<Concept> concept = field.getConceptId() == null
                ? Optional.empty() : Concepts.byId(field.getConceptId());
        return new FieldView(
                field.getId(),
                field.getVersion(),
                field.getRawLabel(),
                field.getConceptId(),
                concept.map(Concept::label).orElse("Not classified"),
                concept.map(Concept::contextSensitive).orElse(false),
                field.getResolvedValue(),
                field.getSource() == null ? null : field.getSource().name(),
                sourceLabel(field.getSource()),
                field.getConfidence() == null ? null : field.getConfidence().name(),
                field.getState().name(),
                stateLabel(field.getState()),
                field.getAutomationState().name(),
                automationLabel(field),
                field.outcome().name(),
                field.isRequired(),
                field.getControlType(),
                field.optionList(),
                field.getExplanation(),
                field.evidenceList().stream().map(Evidence::describe).toList(),
                field.getPendingAssertionId(),
                field.getFailureReason(),
                field.isUserEdited(),
                field.getOriginalValue(),
                conflict,
                actions(field, concept));
    }

    /**
     * What the server will accept for this field.
     *
     * <p>Sent rather than inferred by the page, so a button that would be refused
     * is never drawn. The rule that a rejected draft cannot be approved lives in
     * one place and this is that place's output.
     */
    private static List<String> actions(ApplicationField field, Optional<Concept> concept) {
        List<String> actions = new ArrayList<>();
        switch (field.getState()) {
            case AWAITING_APPROVAL -> {
                actions.add("approve");
                actions.add("edit");
                if (concept.map(Concept::aiEligible).orElse(false)) {
                    actions.add("regenerate");
                }
                actions.add("reject");
            }
            case AWAITING_ANSWER, SKIPPED_OPTIONAL -> actions.add("answer");
            case RESOLVED, DECLINED -> actions.add("override");
            default -> { }
        }
        actions.add("explain");
        return List.copyOf(actions);
    }

    /** Words rather than an enum name. The enum is sent too, for a client to switch on. */
    private static String sourceLabel(KnowledgeSource source) {
        if (source == null) {
            return "no answer";
        }
        return switch (source) {
            case SESSION -> "answered during this preparation";
            case USER_INPUT -> "you answered this";
            case USER_RULE -> "a rule you approved";
            case PROFILE -> "your profile";
            case RESUME -> "your resume";
            case DERIVED -> "worked out from this application";
            case HISTORICAL -> "an earlier application";
            case AI_PROPOSED -> "drafted by the assistant";
        };
    }

    private static String stateLabel(FieldState state) {
        return switch (state) {
            case RESOLVED -> "Prepared";
            case AWAITING_APPROVAL -> "Awaiting your approval";
            case AWAITING_ANSWER -> "Your input required";
            case SKIPPED_OPTIONAL -> "Optional, left blank";
            case DECLINED -> "Declined on purpose";
        };
    }

    /**
     * What the browser did, said plainly.
     *
     * <p>"Blocked" alone reads as a failure of the answer. It is not: the answer
     * is right there on the row, and this is the sentence that says so.
     */
    private static String automationLabel(ApplicationField field) {
        return switch (field.getAutomationState()) {
            case NOT_ATTEMPTED -> "Not typed in yet";
            case FILLED -> "Typed into the form";
            case BLOCKED -> field.getState() == FieldState.RESOLVED
                    ? "Known, but the website would not take it"
                    : "The website would not take it";
            case NOT_APPLICABLE -> "Nothing to type";
        };
    }
}
