package com.anuragbhandary.jobradar.domain;

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
import java.util.Objects;

/**
 * One job posting as seen on one board, at one point in time.
 *
 * <p>Identity is the triple {@code (source, boardToken, externalId)} rather than
 * the URL, because several ATSs rewrite their public URLs without the posting
 * having changed.
 */
@Entity
@Table(
        name = "posting",
        // The composite unique key only survives into the schema because of the
        // custom SqliteDialect - see the note there. Stock hibernate-community-dialects
        // discards it silently.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_posting_natural_id",
                columnNames = {"source", "board_token", "external_id"}),
        indexes = {
                @Index(name = "ix_posting_verdict", columnList = "verdict"),
                @Index(name = "ix_posting_last_seen", columnList = "last_seen")
        })
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Source source;

    /** The board's own handle, e.g. {@code stripe}. */
    @Column(name = "board_token", nullable = false, length = 128)
    private String boardToken;

    /** The ATS's own identifier for this posting. */
    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    @Column(nullable = false, length = 512)
    private String title;

    /** Raw location string exactly as the board gave it. Parsing happens in the filter. */
    @Column(length = 512)
    private String location;

    /**
     * SHA-256 of the normalised description. This is the only thing change
     * detection is allowed to diff on - see the Celonis note in the README.
     */
    @Column(name = "description_hash", length = 64)
    private String descriptionHash;

    /**
     * Description with tags stripped.
     *
     * <p>Not {@code @Lob}: Hibernate maps a CLOB through {@code setClob}, which
     * sqlite-jdbc does not implement. Not LONGVARCHAR either - that resolves to
     * {@code varchar(32600)}, which SQLite ignores but PostgreSQL would enforce as
     * a hard cap, and Amazon's basic_qualifications blocks exceed it.
     *
     * <p>{@code text} is a native type in both SQLite and PostgreSQL and is
     * unbounded in both, so an explicit columnDefinition is the portable choice
     * here despite looking like the less portable one.
     */
    @Column(name = "description_text", columnDefinition = "text")
    private String descriptionText;

    /** Official apply URL. */
    @Column(length = 1024)
    private String url;

    /** Nullable - many boards do not publish one. */
    @Column(name = "posted_date")
    private LocalDate postedDate;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Verdict verdict = Verdict.UNSCREENED;

    /**
     * What the last fetch found. See the note on graduateSignal for why the
     * default is spelled out in the column definition.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32,
            columnDefinition = "varchar(32) not null default 'SEEN'")
    private PostingStatus status = PostingStatus.NEW;

    /**
     * The phrase that disqualified this posting, verbatim where possible.
     * A rejection without a reason is not a useful rejection.
     */
    @Column(name = "reject_reason", length = 512)
    private String rejectReason;

    /**
     * Minimum years of experience found in the description.
     * {@code null} means not yet screened; {@code -1} means screened and none stated.
     * Those are different states and must not be collapsed.
     */
    @Column(name = "min_years")
    private Integer minYears;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Country country;

    /**
     * The posting says "new grad", "graduated within the last 24 months" or
     * similar.
     *
     * <p>A boost in the digest, never a requirement. Historically these have been
     * the only reliable source of eligible roles, but requiring the signal would
     * discard every posting that is open to a new graduate without saying so.
     */
    // An explicit default is required, not cosmetic: SQLite cannot ADD COLUMN
    // NOT NULL without one, so on an existing database the migration would fail
    // - and ddl-auto swallows DDL errors, so it would fail silently and only
    // surface later as "no such column". "false" is valid in both SQLite and
    // PostgreSQL, unlike "0".
    @Column(name = "graduate_signal", nullable = false,
            columnDefinition = "boolean not null default false")
    private boolean graduateSignal;

    protected Posting() {
        // for JPA
    }

    public Posting(Source source, String boardToken, String externalId, String title) {
        this.source = source;
        this.boardToken = boardToken;
        this.externalId = externalId;
        this.title = title;
    }

    public Long getId() {
        return id;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public String getBoardToken() {
        return boardToken;
    }

    public void setBoardToken(String boardToken) {
        this.boardToken = boardToken;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getDescriptionHash() {
        return descriptionHash;
    }

    public void setDescriptionHash(String descriptionHash) {
        this.descriptionHash = descriptionHash;
    }

    public String getDescriptionText() {
        return descriptionText;
    }

    public void setDescriptionText(String descriptionText) {
        this.descriptionText = descriptionText;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public LocalDate getPostedDate() {
        return postedDate;
    }

    public void setPostedDate(LocalDate postedDate) {
        this.postedDate = postedDate;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(Instant firstSeen) {
        this.firstSeen = firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public PostingStatus getStatus() {
        return status;
    }

    public void setStatus(PostingStatus status) {
        this.status = status;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public void setVerdict(Verdict verdict) {
        this.verdict = verdict;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Integer getMinYears() {
        return minYears;
    }

    public void setMinYears(Integer minYears) {
        this.minYears = minYears;
    }

    public Country getCountry() {
        return country;
    }

    public void setCountry(Country country) {
        this.country = country;
    }

    public boolean isGraduateSignal() {
        return graduateSignal;
    }

    public void setGraduateSignal(boolean graduateSignal) {
        this.graduateSignal = graduateSignal;
    }

    /** Equality is the natural key, not the surrogate id. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Posting other)) {
            return false;
        }
        return source == other.source
                && Objects.equals(boardToken, other.boardToken)
                && Objects.equals(externalId, other.externalId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, boardToken, externalId);
    }

    @Override
    public String toString() {
        return "Posting[" + source + "/" + boardToken + "/" + externalId + " " + title + "]";
    }
}
