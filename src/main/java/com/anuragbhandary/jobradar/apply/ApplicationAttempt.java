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
 * The record of one attempt at one posting.
 *
 * <p>Separate from {@link com.anuragbhandary.jobradar.domain.Posting} and from the
 * Google Sheet, and both separations are deliberate.
 *
 * <p>It is not a column on {@code Posting} because a posting can be attempted more
 * than once - prepared today, blocked on an unanswerable question, prepared again
 * next week once the profile knows the answer - and the earlier attempt is the
 * thing that tells you what was missing.
 *
 * <p>It is not the Google Sheet because the sheet is the human record of
 * applications actually sent, maintained by hand since before this tool existed.
 * Twelve rows saying PREPARED would make it useless for the one question it
 * answers. Only {@link AttemptStatus#SUBMITTED} reaches the sheet.
 *
 * <p>Being a new table, it has none of the CHECK-constraint migration problem that
 * {@code Posting.source} and {@code Posting.country} have: Hibernate creates it
 * once with the full enum. Adding a value later will need the same rebuild.
 */
@Entity
@Table(
        name = "application_attempt",
        indexes = {
                @Index(name = "ix_attempt_posting", columnList = "posting_id"),
                @Index(name = "ix_attempt_status", columnList = "status")
        })
public class ApplicationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "posting_id", nullable = false)
    private Long postingId;

    /** Denormalised so the attempt is readable without joining a 9,000-row table. */
    @Column(nullable = false, length = 256)
    private String company;

    @Column(nullable = false, length = 512)
    private String role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AttemptStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Where the tailored PDF was written. Kept: it is what was actually sent. */
    @Column(name = "resume_path", length = 1024)
    private String resumePath;

    /**
     * The letter as submitted, in full.
     *
     * <p>Stored rather than regenerated because a model does not produce the same
     * text twice, and the question after an interview is "what did I actually
     * tell them?" - which a fresh draft cannot answer.
     */
    @Column(name = "cover_letter", columnDefinition = "text")
    private String coverLetter;

    /** What the tailor changed, and why. */
    @Column(name = "tailoring_note", length = 1024)
    private String tailoringNote;

    /**
     * The fill report, rendered. The audit trail: every field, its answer and
     * where the answer came from.
     */
    @Column(name = "field_log", columnDefinition = "text")
    private String fieldLog;

    /** Why it is NEEDS_HUMAN or FAILED. Null on the happy paths. */
    @Column(name = "blocker_reason", length = 1024)
    private String blockerReason;

    /** Path to the screenshot taken before submitting. The proof of what was on screen. */
    @Column(name = "screenshot_path", length = 1024)
    private String screenshotPath;

    /** Row number in the Google Sheet, once written. Null until SUBMITTED. */
    @Column(name = "tracker_row")
    private Integer trackerRow;

    protected ApplicationAttempt() {
        // for JPA
    }

    public ApplicationAttempt(Long postingId, String company, String role) {
        this.postingId = postingId;
        this.company = company;
        this.role = role;
        this.status = AttemptStatus.PREPARED;
        this.startedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPostingId() {
        return postingId;
    }

    public String getCompany() {
        return company;
    }

    public String getRole() {
        return role;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public void setStatus(AttemptStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getResumePath() {
        return resumePath;
    }

    public void setResumePath(String resumePath) {
        this.resumePath = resumePath;
    }

    public String getCoverLetter() {
        return coverLetter;
    }

    public void setCoverLetter(String coverLetter) {
        this.coverLetter = coverLetter;
    }

    public String getTailoringNote() {
        return tailoringNote;
    }

    public void setTailoringNote(String tailoringNote) {
        this.tailoringNote = tailoringNote;
    }

    public String getFieldLog() {
        return fieldLog;
    }

    public void setFieldLog(String fieldLog) {
        this.fieldLog = fieldLog;
    }

    public String getBlockerReason() {
        return blockerReason;
    }

    public void setBlockerReason(String blockerReason) {
        this.blockerReason = blockerReason;
    }

    public String getScreenshotPath() {
        return screenshotPath;
    }

    public void setScreenshotPath(String screenshotPath) {
        this.screenshotPath = screenshotPath;
    }

    public Integer getTrackerRow() {
        return trackerRow;
    }

    public void setTrackerRow(Integer trackerRow) {
        this.trackerRow = trackerRow;
    }
}
