package com.anuragbhandary.jobradar.knowledge;

import java.util.List;

/**
 * What the resolver worked out for one question, and why.
 *
 * <p>Deliberately says nothing about browsers. A question being {@link State#UNKNOWN}
 * means <em>we do not know the answer</em>; a field the automation could not
 * physically fill is a different problem with a different fix, and collapsing the
 * two is what made {@code AttemptStatus.NEEDS_HUMAN} cover five unrelated
 * situations. That separation is established here and consumed later.
 *
 * @param value      what to enter. Null for every state except KNOWN and DERIVED,
 *                   and for a DECLINED voluntary question where blank is the answer.
 * @param applicable the assertion that won, when one did
 * @param competing  everything that also applied. Non-empty for CONFLICT, and
 *                   kept for the others so a review screen can say what lost.
 */
public record Resolution(
        State state,
        String value,
        Concept concept,
        Confidence confidence,
        KnowledgeSource source,
        List<Evidence> evidence,
        Assertion applicable,
        List<Assertion> competing,
        String explanation) {

    public enum State {
        /** An explicit fact applied. Fill it. */
        KNOWN,
        /** Worked out from context by Java. Fill it, and show the reasoning. */
        DERIVED,
        /** A model drafted it. Never filled without approval. */
        AI_PROPOSED,
        /**
         * Two equally applicable, equally authoritative answers disagree.
         *
         * <p>Never silently broken by picking one. Both survive on the
         * resolution so the applicant can be shown the actual disagreement.
         */
        CONFLICT,
        /** Nothing trustworthy applies. Ask him. */
        UNKNOWN,
        /** A voluntary question, deliberately left blank. A real answer. */
        DECLINED
    }

    public Resolution {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        competing = competing == null ? List.of() : List.copyOf(competing);
    }

    public boolean hasValue() {
        return value != null && !value.isBlank();
    }

    /**
     * Whether this may be typed into a form without a human reading it first.
     *
     * <p>Four conditions, and every one of them has cost something in this
     * project's history: the state has to be settled, the concept has to permit
     * it, the confidence has to be high, and there must be a value. A
     * context-sensitive answer resolved at MEDIUM - a stale notice period, a
     * remote posting that never said where from - is shown rather than sent.
     */
    public boolean isAutoFillable() {
        return (state == State.KNOWN || state == State.DERIVED || state == State.DECLINED)
                && concept != null && concept.autoResolvable()
                && confidence == Confidence.HIGH
                && (state == State.DECLINED || hasValue());
    }

    /** True when a human has to look at this before anything is submitted. */
    public boolean needsReview() {
        return state == State.AI_PROPOSED || state == State.CONFLICT
                || (hasValue() && confidence != Confidence.HIGH);
    }

    // ------------------------------------------------------------------

    public static Resolution known(Concept concept, Assertion winner, List<Assertion> competing,
            Confidence confidence, String explanation) {
        return new Resolution(State.KNOWN, winner.getValue(), concept, confidence,
                winner.getSource(), winner.evidenceList(), winner, competing, explanation);
    }

    public static Resolution derived(Concept concept, String value, Confidence confidence,
            List<Evidence> evidence, String explanation) {
        return new Resolution(State.DERIVED, value, concept, confidence,
                KnowledgeSource.DERIVED, evidence, null, List.of(), explanation);
    }

    public static Resolution proposed(Concept concept, String value, Confidence confidence,
            List<Evidence> evidence, Assertion stored, String explanation) {
        return new Resolution(State.AI_PROPOSED, value, concept, confidence,
                KnowledgeSource.AI_PROPOSED, evidence, stored, List.of(), explanation);
    }

    public static Resolution conflict(Concept concept, List<Assertion> competing) {
        String values = competing.stream()
                .map(a -> "'" + a.getValue() + "' (" + a.scope().describe()
                        + ", " + a.getSource() + ")")
                .reduce((a, b) -> a + " vs " + b)
                .orElse("");
        return new Resolution(State.CONFLICT, null, concept, Confidence.LOW, null,
                List.of(), null, competing,
                "two equally applicable answers disagree: " + values);
    }

    public static Resolution unknown(Concept concept, String explanation) {
        return new Resolution(State.UNKNOWN, null, concept, Confidence.LOW, null,
                List.of(), null, List.of(), explanation);
    }

    public static Resolution declined(Concept concept, String explanation) {
        return new Resolution(State.DECLINED, null, concept, Confidence.HIGH, null,
                List.of(), null, List.of(), explanation);
    }

    /**
     * The "why did Job Radar choose this?" paragraph.
     *
     * <p>Assembled rather than stored, because every part of it is already on
     * the resolution and a stored sentence would be the one thing that could go
     * out of date with the answer it describes.
     */
    public String why() {
        StringBuilder out = new StringBuilder();
        out.append("Answer: ").append(hasValue() ? value
                : state == State.DECLINED ? "(declined)" : "(none)").append('\n');
        out.append("Reason: ").append(explanation == null ? "-" : explanation).append('\n');
        out.append("Source: ").append(source == null ? state.name() : sourceLabel()).append('\n');
        out.append("Confidence: ").append(confidence).append('\n');
        if (applicable != null) {
            out.append("Scope: ").append(applicable.scope().describe()).append('\n');
        }
        if (!evidence.isEmpty()) {
            out.append("Evidence:\n");
            evidence.forEach(item -> out.append("  - ").append(item.describe()).append('\n'));
        }
        if (!competing.isEmpty()) {
            out.append("Also applied:\n");
            competing.forEach(item -> out.append("  - ").append(item).append('\n'));
        }
        return out.toString();
    }

    private String sourceLabel() {
        String base = switch (source) {
            case SESSION -> "answered during this preparation";
            case USER_INPUT -> "answered by you";
            case USER_RULE -> "a rule you approved";
            case PROFILE -> "your profile";
            case RESUME -> "your resume";
            case DERIVED -> "worked out from the application context";
            case HISTORICAL -> "an answer used on an earlier application";
            case AI_PROPOSED -> "drafted by the assistant";
        };
        if (applicable != null && applicable.getSource() == KnowledgeSource.AI_PROPOSED
                && applicable.getApproval() == ApprovalState.APPROVED) {
            // Approved, and still AI-originated. The old system lost exactly this
            // the moment an approved draft was written back as a profile fact.
            return base + ", approved by you on "
                    + (applicable.getApprovedAt() == null ? "an unrecorded date"
                            : applicable.getApprovedAt().toString().substring(0, 10))
                    + (applicable.isUserEdited() ? " and edited" : "");
        }
        return base;
    }
}
