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

    /**
     * Which summary variant the resume opened with.
     *
     * <p>Its own column rather than being read back out of {@code tailoringNote},
     * because the note is prose written for a human and parsing it to count
     * outcomes would make the wording load-bearing. This is the only field here
     * that exists to be grouped by.
     */
    @Column(name = "summary_id", length = 64)
    private String summaryId;

    /** What the tailor changed, and why. */
    @Column(name = "tailoring_note", length = 1024)
    private String tailoringNote;

    /**
     * The fill report, rendered.
     *
     * <p><b>An archive, no longer the record.</b> {@link ApplicationField} is
     * authoritative: one queryable row per field, with the state, the source and
     * the evidence in columns rather than in a sentence. This is still written
     * because it is the right thing to read in a terminal and in review.md, and
     * because parsing it back was how the review page worked until now.
     */
    @Column(name = "field_log", columnDefinition = "text")
    private String fieldLog;

    /** Why it is NEEDS_HUMAN or FAILED. Null on the happy paths. */
    @Column(name = "blocker_reason", length = 1024)
    private String blockerReason;

    /**
     * Which kind of manual intervention this needs, when it needs one.
     *
     * <p>Structured, alongside {@link #blockerReason} rather than replacing it:
     * the prose is still the best thing to show a person, and the enum is what
     * the worklist should have been switching on instead of testing whether that
     * prose began with "No form found".
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "manual_reason", length = 32)
    private ManualReason manualReason;

    /**
     * Questions this form asked that the profile could not answer, tab-separated.
     *
     * <p><b>An archive, no longer the record.</b> {@link QuestionSighting} is
     * authoritative and carries the company, the board, the country and the
     * concept - none of which fitted in a tab-separated column.
     *
     * <p>Still written, because {@code learn} reads it and because it is the
     * shape the answers page was built on.
     */
    @Column(name = "open_questions", columnDefinition = "text")
    private String openQuestions;

    /** Path to the screenshot taken before submitting. The proof of what was on screen. */
    @Column(name = "screenshot_path", length = 1024)
    private String screenshotPath;

    /** Row number in the Google Sheet, once written. Null until SUBMITTED. */
    @Column(name = "tracker_row")
    private Integer trackerRow;

    /**
     * Which part of preparation is running.
     *
     * <p>Separate from {@link #status} rather than folded into it, because they
     * answer different questions and only one of them is history. The status says
     * why this attempt stopped and is still worth reading next year; the stage
     * says what is happening now and is meaningless the moment the run ends.
     *
     * <p>Persisted rather than held in a map, so the progress endpoint is a row
     * read and a restart mid-run leaves evidence rather than a page that polls
     * forever.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PreparationStage stage;

    /**
     * One line about this particular run: which board, how many fields.
     *
     * <p>Never a field value. It is rendered into a page and written to a log,
     * and the values on these forms are addresses, salary expectations and visa
     * status.
     */
    @Column(name = "stage_detail", length = 256)
    private String stageDetail;

    @Column(name = "stage_updated_at")
    private Instant stageUpdatedAt;

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

    public String getSummaryId() {
        return summaryId;
    }

    public void setSummaryId(String summaryId) {
        this.summaryId = summaryId;
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

    public ManualReason getManualReason() {
        return manualReason;
    }

    public void setManualReason(ManualReason manualReason) {
        this.manualReason = manualReason;
    }

    public String getBlockerReason() {
        return blockerReason;
    }

    public void setBlockerReason(String blockerReason) {
        this.blockerReason = blockerReason;
    }

    public String getOpenQuestions() {
        return openQuestions;
    }

    public void setOpenQuestions(String openQuestions) {
        this.openQuestions = openQuestions;
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

    public PreparationStage getStage() {
        return stage;
    }

    /** Moves the stage and stamps the time, which is what makes progress visible. */
    public void setStage(PreparationStage stage, String detail) {
        this.stage = stage;
        this.stageDetail = detail;
        this.stageUpdatedAt = Instant.now();
    }

    public String getStageDetail() {
        return stageDetail;
    }

    public Instant getStageUpdatedAt() {
        return stageUpdatedAt;
    }

    /**
     * True when this attempt could be picked up where it left off.
     *
     * <p>Not the same as "something is wrong with it". A submitted attempt is
     * finished, a failed one is worth another go, and an attempt waiting on an
     * answer is the ordinary case this whole phase is built around.
     */
    public boolean isResumable() {
        return status != AttemptStatus.SUBMITTED && status != AttemptStatus.SKIPPED
                && (stage == null || !stage.isRunning());
    }
}
