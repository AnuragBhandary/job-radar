package com.anuragbhandary.jobradar.pipeline;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A posting the applicant has taken an interest in.
 *
 * <p>The row that was missing. Until now a posting was either ignored or fully
 * prepared, with nothing between: no way to say "this one, later", no place for a
 * note, and no record of a job being pursued that this tool did not fill the form
 * for. Nine thousand postings and no bookmark.
 *
 * <p>Separate from {@code Posting}, which is what a board said and should stay
 * replaceable by re-fetching, and separate from {@code ApplicationAttempt}, which
 * is what the automation did. This is what the human decided, and it is the only
 * one of the three that cannot be reconstructed.
 */
@Entity
@Table(
        name = "job_interest",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_interest_posting", columnNames = {"posting_id"}),
        indexes = {
                @Index(name = "ix_interest_stage", columnList = "stage"),
                @Index(name = "ix_interest_remind", columnList = "remind_on")
        })
public class JobInterest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The posting this came from, or null.
     *
     * <p>Nullable, because most of the applications already in the tracker were
     * made before this tool existed and name companies whose boards it does not
     * read. A board that could only show the jobs the scraper found would be
     * missing two thirds of the real pipeline, which makes it a worse record than
     * the spreadsheet it replaces.
     */
    @Column(name = "posting_id")
    private Long postingId;

    /**
     * Company and role, copied rather than joined.
     *
     * <p>Denormalised on purpose: an entry with no posting has nowhere to join to,
     * and one whose posting is later deleted as closed should not lose its name.
     * This is the record of what was applied to, and it has to survive the board
     * it came from going away.
     */
    @Column(nullable = false, length = 256)
    private String company;

    @Column(nullable = false, length = 512)
    private String role;

    @Column(length = 1024)
    private String url;

    /** Row number in the spreadsheet, when this came from there or was written to it. */
    @Column(name = "tracker_row")
    private Integer trackerRow;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PipelineStage stage = PipelineStage.SAVED;

    /** Free text, the applicant's own. Never generated, never overwritten. */
    @Column(columnDefinition = "text")
    private String notes;

    /** A date to be reminded, or null. Surfaced by the board and by the digest. */
    @Column(name = "remind_on")
    private LocalDate remindOn;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The score when it was saved.
     *
     * <p>Kept even though the score is recomputed on every page, because the
     * resume changes and it is worth being able to see that something scored 82
     * when it was saved and scores 61 now. A number that silently rewrites its own
     * history explains nothing.
     */
    @Column(name = "score_when_saved")
    private Integer scoreWhenSaved;

    protected JobInterest() {
        // for JPA
    }

    public JobInterest(Long postingId, String company, String role, String url,
            PipelineStage stage, Integer score) {
        this.postingId = postingId;
        this.company = company;
        this.role = role;
        this.url = url;
        this.stage = stage;
        this.scoreWhenSaved = score;
        this.savedAt = Instant.now();
        this.updatedAt = this.savedAt;
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

    public String getUrl() {
        return url;
    }

    public Integer getTrackerRow() {
        return trackerRow;
    }

    public void setTrackerRow(Integer trackerRow) {
        this.trackerRow = trackerRow;
        this.updatedAt = Instant.now();
    }

    /** True when this came from a board rather than being typed in by hand. */
    public boolean hasPosting() {
        return postingId != null;
    }

    public PipelineStage getStage() {
        return stage;
    }

    public void setStage(PipelineStage stage) {
        this.stage = stage;
        this.updatedAt = Instant.now();
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
        this.updatedAt = Instant.now();
    }

    public LocalDate getRemindOn() {
        return remindOn;
    }

    public void setRemindOn(LocalDate remindOn) {
        this.remindOn = remindOn;
        this.updatedAt = Instant.now();
    }

    public Instant getSavedAt() {
        return savedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Integer getScoreWhenSaved() {
        return scoreWhenSaved;
    }

    /** True when the reminder is today or overdue. */
    public boolean isDue(LocalDate today) {
        return remindOn != null && !remindOn.isAfter(today);
    }
}
