package com.anuragbhandary.jobradar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;

/**
 * A board this tool fetches from, plus the health of the last fetch.
 *
 * <p>The health fields exist so that a board which starts returning 404 or an
 * empty array becomes visible in the digest instead of silently dropping out of
 * the results. A board that quietly stops working looks exactly like a board
 * with no matching jobs, and those need to be told apart.
 */
@Entity
@Table(
        name = "board_token",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_board_token_natural_id",
                columnNames = {"source", "token"}))
public class BoardToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Source source;

    @Column(nullable = false, length = 128)
    private String token;

    /** Human-readable company name, for the digest. */
    @Column(length = 256)
    private String label;

    /** Inactive tokens stay in the table as a record; they are simply not fetched. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @Column(name = "last_posting_count")
    private Integer lastPostingCount;

    /** Null when the last fetch succeeded. */
    @Column(name = "last_error", length = 1024)
    private String lastError;

    /**
     * Where this employer is, ISO-3166 alpha-2, or null.
     *
     * <p>On the board rather than on each posting because it is a fact about the
     * company, not about the vacancy - and because it is the only way to tell a
     * US company hiring remotely from India apart from a US relocation, which is
     * the distinction the whole strategic model turns on.
     *
     * <p>Normally inferred from where the board's own onsite postings are, which
     * costs nothing and is right for almost every company here. Set it by hand
     * for the ones it is wrong for: a value already present is never overwritten
     * by the inference.
     */
    @Column(name = "employer_country_code", length = 2)
    private String employerCountryCode;

    protected BoardToken() {
        // for JPA
    }

    public BoardToken(Source source, String token, String label) {
        this.source = source;
        this.token = token;
        this.label = label;
    }

    /** Records a successful fetch and clears any previous error. */
    public void recordSuccess(int postingCount, Instant at) {
        this.lastFetchedAt = at;
        this.lastPostingCount = postingCount;
        this.lastError = null;
    }

    /** Records a failed fetch. The previous posting count is deliberately kept. */
    public void recordFailure(String error, Instant at) {
        this.lastFetchedAt = at;
        this.lastError = error;
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

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getLastFetchedAt() {
        return lastFetchedAt;
    }

    public void setLastFetchedAt(Instant lastFetchedAt) {
        this.lastFetchedAt = lastFetchedAt;
    }

    public Integer getLastPostingCount() {
        return lastPostingCount;
    }

    public void setLastPostingCount(Integer lastPostingCount) {
        this.lastPostingCount = lastPostingCount;
    }

    /**
     * The prefix that marks a board switched off on purpose.
     *
     * <p>{@code lastError} carries two different meanings and always has: a board
     * that broke, and a board deliberately retired because the company moved ATS
     * or is already covered elsewhere. Eight of the boards here are the second
     * kind and none are the first, so counting the field as failures reported
     * "8 boards are failing, jobs are being missed" on a home page when nothing
     * was wrong at all.
     */
    private static final String RETIRED = "retired:";

    /** Switched off deliberately. Worth listing, never worth alarming about. */
    public boolean isRetired() {
        return lastError != null
                && lastError.regionMatches(true, 0, RETIRED, 0, RETIRED.length());
    }

    /** Stopped answering when it was expected to. This one is a problem. */
    public boolean isBroken() {
        return lastError != null && !lastError.isBlank() && !isRetired();
    }

    /** The reason without the marker, for showing next to a retired board. */
    public String retirementReason() {
        return isRetired() ? lastError.substring(RETIRED.length()).trim() : lastError;
    }

    public String getEmployerCountryCode() {
        return employerCountryCode;
    }

    public void setEmployerCountryCode(String employerCountryCode) {
        this.employerCountryCode = employerCountryCode;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BoardToken other)) {
            return false;
        }
        return source == other.source && Objects.equals(token, other.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, token);
    }

    @Override
    public String toString() {
        return "BoardToken[" + source + "/" + token + (active ? "" : " (inactive)") + "]";
    }
}
