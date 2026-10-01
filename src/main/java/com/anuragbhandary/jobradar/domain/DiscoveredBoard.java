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

/**
 * A board found by {@code discover}, and what its survey found.
 *
 * <p>Kept apart from {@link BoardToken} on purpose. Discovery surveys tens of
 * thousands of boards and adds the few hundred with roles in a target country;
 * the rest are recorded here, so the next run does not survey them again, and
 * {@code --recheck} can look at them later. Putting them in {@code board_token}
 * as inactive rows would bury the boards that are actually fetched.
 */
@Entity
@Table(
        name = "discovered_board",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_discovered_board_natural_id",
                columnNames = {"source", "token"}))
public class DiscoveredBoard {

    /** What the survey decided. */
    public enum Outcome {
        /** Had roles in a target country; added to {@code board_token}. */
        ADDED,
        /** Reachable, but nothing in India, Ireland, Germany, the Netherlands or open remote. */
        NO_TARGET_ROLES,
        /** Could not be read: gone, renamed, or never a board. */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Source source;

    @Column(nullable = false, length = 128)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Outcome outcome;

    @Column(name = "surveyed_at", nullable = false)
    private Instant surveyedAt;

    @Column(name = "board_total")
    private Integer boardTotal;

    @Column(name = "india_count")
    private Integer indiaCount;

    /** Ireland, Germany and the Netherlands together. */
    @Column(name = "relocation_count")
    private Integer relocationCount;

    @Column(name = "remote_count")
    private Integer remoteCount;

    @Column(length = 512)
    private String note;

    protected DiscoveredBoard() {
        // for JPA
    }

    public DiscoveredBoard(Source source, String token) {
        this.source = source;
        this.token = token;
    }

    public void record(Outcome outcome, Instant at, Integer total, Integer india,
            Integer relocation, Integer remote, String note) {
        this.outcome = outcome;
        this.surveyedAt = at;
        this.boardTotal = total;
        this.indiaCount = india;
        this.relocationCount = relocation;
        this.remoteCount = remote;
        this.note = note == null || note.length() <= 512 ? note : note.substring(0, 512);
    }

    public Source getSource() {
        return source;
    }

    public String getToken() {
        return token;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public Instant getSurveyedAt() {
        return surveyedAt;
    }
}
