package com.anuragbhandary.jobradar.apply;

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

/**
 * One occasion on which a board asked a question.
 *
 * <p>What {@code AnswerBank} was reconstructing at read time by parsing
 * tab-separated values out of {@code ApplicationAttempt.openQuestions} and
 * grouping them on a six-word prefix. That worked, and it could only ever answer
 * one question - which phrases recur - because it had no company, no board, no
 * country and no concept.
 *
 * <p>A row per sighting rather than a counter per concept, so the interesting
 * questions stay answerable later: which boards ask this, which companies, how
 * often it blocks rather than merely appears, and what the wording was each time.
 */
@Entity
@Table(
        name = "question_sighting",
        indexes = {
                @Index(name = "ix_sighting_concept", columnList = "concept_id"),
                @Index(name = "ix_sighting_attempt", columnList = "attempt_id"),
                @Index(name = "ix_sighting_state", columnList = "state")
        })
public class QuestionSighting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null when the question could not be classified - which is itself worth counting. */
    @Column(name = "concept_id", length = 64)
    private String conceptId;

    /** Verbatim. Normalising is for matching; this is the record. */
    @Column(name = "raw_label", nullable = false, length = 512)
    private String rawLabel;

    @Column(name = "attempt_id")
    private Long attemptId;

    @Column(name = "posting_id")
    private Long postingId;

    @Column(length = 256)
    private String company;

    @Column(name = "board_token", length = 128)
    private String boardToken;

    /** Which ATS asked it. The same question is worded differently on each. */
    @Column(name = "ats_source", length = 32)
    private String atsSource;

    /** ISO code of the employment country at the time, when it was known. */
    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean required;

    /** How it ended: resolved, awaiting an answer, skipped. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FieldState state;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;

    protected QuestionSighting() {
        // for JPA
    }

    public QuestionSighting(String rawLabel, FieldState state) {
        this.rawLabel = rawLabel;
        this.state = state;
        this.seenAt = Instant.now();
    }

    /** True when this sighting actually stopped an application rather than just appearing. */
    public boolean blockedTheForm() {
        return required && state == FieldState.AWAITING_ANSWER;
    }

    public Long getId() {
        return id;
    }

    public String getConceptId() {
        return conceptId;
    }

    public void setConceptId(String conceptId) {
        this.conceptId = conceptId;
    }

    public String getRawLabel() {
        return rawLabel;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(Long attemptId) {
        this.attemptId = attemptId;
    }

    public Long getPostingId() {
        return postingId;
    }

    public void setPostingId(Long postingId) {
        this.postingId = postingId;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getBoardToken() {
        return boardToken;
    }

    public void setBoardToken(String boardToken) {
        this.boardToken = boardToken;
    }

    public String getAtsSource() {
        return atsSource;
    }

    public void setAtsSource(String atsSource) {
        this.atsSource = atsSource;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public FieldState getState() {
        return state;
    }

    public void setState(FieldState state) {
        this.state = state;
    }

    public Instant getSeenAt() {
        return seenAt;
    }
}
