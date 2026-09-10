package com.anuragbhandary.jobradar.knowledge;

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
import java.time.LocalDate;
import java.util.List;

/**
 * One thing known about the applicant, valid somewhere, from somewhere.
 *
 * <p>Replaces {@code ApplicantProfile.ExtraAnswer}, which was a
 * {@code {match, answer}} pair in a YAML file. That pair had no country, no
 * company, no source, no date and no approval, so it applied everywhere and
 * forever the moment it was written - and two separate code paths wrote it by
 * splicing strings into the file the applicant maintains by hand.
 *
 * <p>Its own table rather than columns on the profile, because there are now
 * several of these per concept - one per country, one per company - and the
 * questions worth asking of them ("which concept blocks the most applications?",
 * "what did I tell them in March?") are queries.
 *
 * <h2>Nothing is overwritten</h2>
 * A changed answer writes a new row and points the old one at it through
 * {@link #supersededById}. The history is the point: an answer sent to an
 * employer cannot be un-sent, so the record of what was sent has to survive the
 * answer changing.
 */
@Entity
@Table(
        name = "assertion",
        indexes = {
                @Index(name = "ix_assertion_concept", columnList = "concept_id"),
                @Index(name = "ix_assertion_scope", columnList = "scope_level, scope_value"),
                @Index(name = "ix_assertion_superseded", columnList = "superseded_by_id")
        })
public class Assertion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@link Concept#id()}. A string, not an enum: concepts outlive schemas. */
    @Column(name = "concept_id", nullable = false, length = 64)
    private String conceptId;

    /**
     * The answer, as it would be typed.
     *
     * <p>Nullable, and null means something: a deliberate decline on a voluntary
     * question is a real answer and not a gap, exactly as
     * {@code Answer.Origin.DECLINED} already recorded.
     */
    @Column(columnDefinition = "text")
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_level", nullable = false, length = 32)
    private Scope.Level scopeLevel;

    /** Null for GLOBAL, which matches everything and has nothing to match on. */
    @Column(name = "scope_value", length = 128)
    private String scopeValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KnowledgeSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Confidence confidence = Confidence.HIGH;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ApprovalState approval = ApprovalState.UNAPPROVED;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** Which screen or command the approval came through. For the audit trail. */
    @Column(name = "approved_via", length = 64)
    private String approvedVia;

    /**
     * The applicant rewrote what a model drafted.
     *
     * <p>Kept alongside {@code source = AI_PROPOSED} rather than replacing it.
     * What the model wrote is still part of the story, and "he edited this" is a
     * different fact from "he wrote this".
     */
    @Column(name = "user_edited", nullable = false,
            columnDefinition = "boolean not null default false")
    private boolean userEdited;

    /** Set on the older row when a newer one replaces it. Never deleted. */
    @Column(name = "superseded_by_id")
    private Long supersededById;

    /** {@link Evidence#serialise}. */
    @Column(name = "evidence", columnDefinition = "text")
    private String evidence;

    /**
     * The question as the form actually worded it.
     *
     * <p>Migration provenance, and useful long after: a concept is an
     * abstraction, and "this came from a Channable radio button that offered
     * three sentences" is what makes a wrong assertion diagnosable.
     */
    @Column(name = "source_question", length = 512)
    private String sourceQuestion;

    /** Re-check by this date, for a volatile fact. Null when it does not age. */
    @Column(name = "verify_by")
    private LocalDate verifyBy;

    /**
     * Migrated with a scope nobody chose, and worth a human look.
     *
     * <p>Set by the {@code ExtraAnswer} migration for anything context-sensitive.
     * Those answers were written when there was no such thing as scope, so the
     * scope they "had" is an artefact of the old model rather than a decision.
     */
    @Column(name = "needs_review", nullable = false,
            columnDefinition = "boolean not null default false")
    private boolean needsReview;

    @Column(length = 512)
    private String note;

    /**
     * Why the stored value is not a usable answer for its concept. Null when it is.
     *
     * <p>Its own column rather than a check at read time, because the answer is a
     * property of the row and the row outlives the code that wrote it. Two rows in
     * the live database hold a rule written in prose where a yes/no belongs; they
     * were approved, live, and outranking the derivation that gets sponsorship
     * right, and nothing anywhere noticed.
     *
     * <p>Null on every row written before this existed, and treated as valid - the
     * repair pass is what fills it in, and an unchecked row is not the same as a
     * bad one.
     */
    @Column(name = "invalid_reason", length = 256)
    private String invalidReason;

    protected Assertion() {
        // for JPA
    }

    public Assertion(String conceptId, String value, Scope scope, KnowledgeSource source) {
        this.conceptId = conceptId;
        this.value = value;
        this.scopeLevel = scope.level();
        this.scopeValue = scope.value();
        this.source = source;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Scope scope() {
        return new Scope(scopeLevel, scopeValue);
    }

    public List<Evidence> evidenceList() {
        return Evidence.parse(evidence);
    }

    public void setEvidenceList(List<Evidence> items) {
        this.evidence = Evidence.serialise(items);
        touch();
    }

    /** True when this row is still the live one for its concept and scope. */
    public boolean isLive() {
        return supersededById == null;
    }

    /**
     * True when the value is not a usable answer for its concept.
     *
     * <p>Kept apart from {@link ApprovalState#REJECTED}: he rejected that one, and
     * this one is malformed. Both stop the row being used and they are not the
     * same fact about it.
     */
    public boolean isInvalid() {
        return invalidReason != null;
    }

    /**
     * Records that the value cannot be an answer, keeping the value.
     *
     * <p>Never deletes and never rewrites. "What did I tell them in March?" has to
     * stay answerable, and a value that was wrong is still what was there.
     */
    public void markInvalid(String reason) {
        this.invalidReason = reason;
        this.needsReview = true;
        touch();
    }

    public String getInvalidReason() {
        return invalidReason;
    }

    /**
     * Whether this may be used without a human reading it first.
     *
     * <p>Approval and liveness both, because an unapproved AI draft is stored,
     * resolvable and explicitly not usable - that separation is the whole reason
     * {@link ApprovalState} exists apart from {@link KnowledgeSource}.
     */
    public boolean isUsable() {
        return isLive() && !isInvalid() && approval == ApprovalState.APPROVED;
    }

    /** Past its own re-check date. Never a reason to delete, only to downgrade. */
    public boolean isStale(LocalDate today) {
        return verifyBy != null && today != null && !today.isBefore(verifyBy);
    }

    public void approve(String via) {
        this.approval = ApprovalState.APPROVED;
        this.approvedAt = Instant.now();
        this.approvedVia = via;
        touch();
    }

    public void reject(String via) {
        this.approval = ApprovalState.REJECTED;
        this.approvedVia = via;
        touch();
    }

    public void supersededBy(Assertion replacement) {
        this.supersededById = replacement == null ? null : replacement.getId();
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // ------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getConceptId() {
        return conceptId;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
        touch();
    }

    public Scope.Level getScopeLevel() {
        return scopeLevel;
    }

    public String getScopeValue() {
        return scopeValue;
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

    public ApprovalState getApproval() {
        return approval;
    }

    public void setApproval(ApprovalState approval) {
        this.approval = approval;
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getApprovedVia() {
        return approvedVia;
    }

    public boolean isUserEdited() {
        return userEdited;
    }

    public void setUserEdited(boolean userEdited) {
        this.userEdited = userEdited;
        touch();
    }

    public Long getSupersededById() {
        return supersededById;
    }

    public void setSupersededById(Long supersededById) {
        this.supersededById = supersededById;
        touch();
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
        touch();
    }

    public String getSourceQuestion() {
        return sourceQuestion;
    }

    public void setSourceQuestion(String sourceQuestion) {
        this.sourceQuestion = sourceQuestion;
        touch();
    }

    public LocalDate getVerifyBy() {
        return verifyBy;
    }

    public void setVerifyBy(LocalDate verifyBy) {
        this.verifyBy = verifyBy;
        touch();
    }

    public boolean isNeedsReview() {
        return needsReview;
    }

    public void setNeedsReview(boolean needsReview) {
        this.needsReview = needsReview;
        touch();
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
        touch();
    }

    @Override
    public String toString() {
        return "Assertion[" + conceptId + " @ " + scope().describe()
                + " = " + value + " (" + source + "/" + approval + ")]";
    }
}
