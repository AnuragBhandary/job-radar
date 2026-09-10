package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.knowledge.Confidence;
import com.anuragbhandary.jobradar.knowledge.Evidence;
import com.anuragbhandary.jobradar.knowledge.KnowledgeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;

/**
 * One field of one application, with everything that was decided about it.
 *
 * <p>Replaces {@code ApplicationAttempt.fieldLog}, which stored this as prose -
 * the rendered output of {@code FillReport.Entry.describe()} - and which
 * {@code web/FieldRow} then parsed back with {@code lastIndexOf("   [")} to draw
 * a table. One format that read well in a terminal, at the cost of the audit
 * trail being unqueryable and Java parsing its own prose.
 *
 * <p>"Which concept blocks the most applications?" is now a query rather than a
 * full-table scan and a string split.
 *
 * <h2>Two states, on purpose</h2>
 * {@link #state} is what is known; {@link #automationState} is what the browser
 * managed. A field can be {@code RESOLVED} and {@code BLOCKED} together, and the
 * resolved value is kept when it is - Job Radar knowing the answer and the page
 * refusing it is not the same as Job Radar not knowing.
 */
@Entity
@Table(
        name = "application_field",
        indexes = {
                @Index(name = "ix_field_attempt", columnList = "attempt_id"),
                @Index(name = "ix_field_concept", columnList = "concept_id"),
                @Index(name = "ix_field_state", columnList = "state")
        })
public class ApplicationField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    /** Null when the question could not be classified. A real answer, not a gap. */
    @Column(name = "concept_id", length = 64)
    private String conceptId;

    /**
     * The question exactly as the board worded it.
     *
     * <p>Never normalised away. A concept is an abstraction and the raw wording is
     * what makes a wrong classification diagnosable - and what lets the alias list
     * grow from what boards actually ask rather than from what they might.
     */
    @Column(name = "raw_label", nullable = false, length = 512)
    private String rawLabel;

    @Column(name = "control_type", length = 32)
    private String controlType;

    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean required;

    /** The choices a select or radio group offered, tab-separated. */
    @Column(columnDefinition = "text")
    private String options;

    /**
     * What was decided, or would have been.
     *
     * <p>Kept even when {@link #automationState} is {@code BLOCKED}: the value is
     * still the right answer, and the person finishing the form by hand needs it.
     */
    @Column(name = "resolved_value", columnDefinition = "text")
    private String resolvedValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FieldState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "automation_state", nullable = false, length = 32)
    private AutomationState automationState = AutomationState.NOT_ATTEMPTED;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private KnowledgeSource source;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Confidence confidence;

    /** {@link Evidence#serialise} - what the answer was based on. */
    @Column(columnDefinition = "text")
    private String evidence;

    /** The resolver's own sentence. Not assembled in a controller. */
    @Column(length = 1024)
    private String explanation;

    /** The applicant overrode what was resolved. */
    @Column(name = "user_edited", nullable = false,
            columnDefinition = "boolean not null default false")
    private boolean userEdited;

    /** Why the browser could not fill it. Null unless the automation state is BLOCKED. */
    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    /** The proposal or conflicting assertion awaiting a decision, when there is one. */
    @Column(name = "pending_assertion_id")
    private Long pendingAssertionId;

    /**
     * What was resolved before the applicant changed it. Null until he does.
     *
     * <p>{@link #userEdited} says that an override happened and could not say what
     * it replaced, which makes the one question worth asking afterwards - "what
     * would it have sent?" - unanswerable. Written once, on the first override, so
     * a second edit does not quietly become the thing being compared against.
     */
    @Column(name = "original_value", columnDefinition = "text")
    private String originalValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "original_source", length = 32)
    private KnowledgeSource originalSource;

    /**
     * Bumped on every save. The token that catches a stale decision.
     *
     * <p>The case it exists for: a draft is on screen, it is regenerated in
     * another tab, and the approve button still carries the old one. Approving it
     * would send prose nobody read. Every action carries the version it saw and is
     * refused if the row has moved on - which is a sentence a person can act on,
     * unlike an approval that silently applied to something else.
     *
     * <p>JPA's own optimistic locking, so a concurrent server-side write is caught
     * too. Distributed locking would be absurd here; there is one user and one
     * process.
     */
    @jakarta.persistence.Version
    @Column(nullable = false, columnDefinition = "bigint not null default 0")
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApplicationField() {
        // for JPA
    }

    public ApplicationField(Long attemptId, String rawLabel, FieldState state) {
        this.attemptId = attemptId;
        this.rawLabel = rawLabel;
        this.state = state;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * The single value a screen wants, folded from the two axes.
     *
     * <p>Derived rather than stored: two independent facts about a field cannot
     * be kept consistent with a third that summarises them.
     */
    public Outcome outcome() {
        if (state == FieldState.AWAITING_APPROVAL || state == FieldState.AWAITING_ANSWER) {
            return state == FieldState.AWAITING_APPROVAL
                    ? Outcome.AWAITING_APPROVAL : Outcome.AWAITING_ANSWER;
        }
        if (state == FieldState.SKIPPED_OPTIONAL) {
            return Outcome.SKIPPED;
        }
        if (state == FieldState.DECLINED) {
            return Outcome.DECLINED;
        }
        return switch (automationState) {
            case FILLED -> Outcome.FILLED;
            case BLOCKED -> Outcome.AUTOMATION_BLOCKED;
            case NOT_ATTEMPTED, NOT_APPLICABLE -> Outcome.READY;
        };
    }

    /** What a person sees in one column. */
    public enum Outcome {
        /** Resolved and typed in. */
        FILLED,
        /** Resolved, not yet attempted. */
        READY,
        /** <b>Known, and the page would not take it.</b> Not the same as unknown. */
        AUTOMATION_BLOCKED,
        AWAITING_APPROVAL,
        AWAITING_ANSWER,
        SKIPPED,
        DECLINED
    }

    public List<Evidence> evidenceList() {
        return Evidence.parse(evidence);
    }

    public void setEvidenceList(List<Evidence> items) {
        this.evidence = Evidence.serialise(items);
        touch();
    }

    public List<String> optionList() {
        return options == null || options.isBlank() ? List.of() : List.of(options.split("\t"));
    }

    public void setOptionList(List<String> items) {
        this.options = items == null || items.isEmpty() ? null
                : String.join("\t", items.stream()
                        .map(item -> item == null ? "" : item.replace('\t', ' ')).toList());
        touch();
    }

    /**
     * Records that the browser could not fill a field whose answer is known.
     *
     * <p>Note what it does not do: it does not clear {@link #resolvedValue} and it
     * does not move {@link #state}. That is the rule this whole class exists for.
     */
    public void blockedBy(String reason) {
        this.automationState = AutomationState.BLOCKED;
        this.failureReason = reason;
        touch();
    }

    public void filled() {
        this.automationState = AutomationState.FILLED;
        this.failureReason = null;
        touch();
    }

    /**
     * Records a value the applicant decided on, keeping what it replaced.
     *
     * <p>The automation state is deliberately untouched. A field that the browser
     * already filled with the old value is still filled with the old value, and
     * saying otherwise would make the record disagree with the page - what makes
     * the new value reach the form is opening the application again, not this.
     */
    public void overriddenBy(String value, KnowledgeSource newSource) {
        if (!userEdited) {
            this.originalValue = this.resolvedValue;
            this.originalSource = this.source;
        }
        this.resolvedValue = value;
        this.source = newSource;
        this.userEdited = true;
        this.state = FieldState.RESOLVED;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // ------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public String getConceptId() {
        return conceptId;
    }

    public void setConceptId(String conceptId) {
        this.conceptId = conceptId;
        touch();
    }

    public String getRawLabel() {
        return rawLabel;
    }

    public String getControlType() {
        return controlType;
    }

    public void setControlType(String controlType) {
        this.controlType = controlType;
        touch();
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
        touch();
    }

    public String getOptions() {
        return options;
    }

    public String getResolvedValue() {
        return resolvedValue;
    }

    public void setResolvedValue(String resolvedValue) {
        this.resolvedValue = resolvedValue;
        touch();
    }

    public FieldState getState() {
        return state;
    }

    public void setState(FieldState state) {
        this.state = state;
        touch();
    }

    public AutomationState getAutomationState() {
        return automationState;
    }

    public void setAutomationState(AutomationState automationState) {
        this.automationState = automationState;
        touch();
    }

    public KnowledgeSource getSource() {
        return source;
    }

    public void setSource(KnowledgeSource source) {
        this.source = source;
        touch();
    }

    public Confidence getConfidence() {
        return confidence;
    }

    public void setConfidence(Confidence confidence) {
        this.confidence = confidence;
        touch();
    }

    public String getEvidence() {
        return evidence;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
        touch();
    }

    public boolean isUserEdited() {
        return userEdited;
    }

    public void setUserEdited(boolean userEdited) {
        this.userEdited = userEdited;
        touch();
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Long getPendingAssertionId() {
        return pendingAssertionId;
    }

    public String getOriginalValue() {
        return originalValue;
    }

    public KnowledgeSource getOriginalSource() {
        return originalSource;
    }

    public long getVersion() {
        return version;
    }

    public void setPendingAssertionId(Long pendingAssertionId) {
        this.pendingAssertionId = pendingAssertionId;
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "ApplicationField[" + rawLabel + " -> " + outcome() + "]";
    }
}
