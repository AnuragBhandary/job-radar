package com.anuragbhandary.jobradar.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.Verdict;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Milestone 1 proof: the SQLite dialect, the entity mappings and the natural-key
 * unique constraint all actually work against a real database file.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PostingRepositoryTest {

    @Autowired
    private PostingRepository postings;

    @Autowired
    private BoardTokenRepository boards;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a posting round-trips through SQLite with every column intact")
    void savesAndReadsBackAPosting() {
        Posting p = new Posting(Source.GREENHOUSE, "stripe", "6123456", "Software Engineer, New Grad");
        p.setLocation("Dublin, Ireland");
        p.setUrl("https://boards.greenhouse.io/stripe/jobs/6123456");
        p.setDescriptionText("We are looking for a new grad engineer. 0-1 years of experience.");
        p.setDescriptionHash("a".repeat(64));
        p.setPostedDate(LocalDate.of(2026, 9, 1));
        p.setFirstSeen(Instant.parse("2026-09-06T01:30:00Z"));
        p.setLastSeen(Instant.parse("2026-09-06T01:30:00Z"));
        p.setCountry(Country.IRELAND);
        p.setMinYears(0);

        postings.saveAndFlush(p);

        Posting found = postings
                .findBySourceAndBoardTokenAndExternalId(Source.GREENHOUSE, "stripe", "6123456")
                .orElseThrow();

        assertThat(found.getId()).isNotNull();
        assertThat(found.getTitle()).isEqualTo("Software Engineer, New Grad");
        assertThat(found.getLocation()).isEqualTo("Dublin, Ireland");
        assertThat(found.getCountry()).isEqualTo(Country.IRELAND);
        assertThat(found.getPostedDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(found.getFirstSeen()).isEqualTo(Instant.parse("2026-09-06T01:30:00Z"));
        assertThat(found.getDescriptionText()).contains("0-1 years");
        assertThat(found.getMinYears()).isZero();
        // Default, not something the test set - a freshly fetched posting is unscreened.
        assertThat(found.getVerdict()).isEqualTo(Verdict.UNSCREENED);
    }

    @Test
    @DisplayName("the same posting cannot be stored twice under its natural key")
    void rejectsDuplicateNaturalKey() {
        Posting first = new Posting(Source.ASHBY, "notion", "abc-123", "Software Engineer");
        first.setFirstSeen(Instant.now());
        first.setLastSeen(Instant.now());
        postings.saveAndFlush(first);

        Posting duplicate = new Posting(Source.ASHBY, "notion", "abc-123", "Software Engineer (renamed)");
        duplicate.setFirstSeen(Instant.now());
        duplicate.setLastSeen(Instant.now());

        assertThatThrownBy(() -> postings.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the same external id on a different board is a different posting")
    void naturalKeyIncludesSourceAndToken() {
        Posting greenhouse = new Posting(Source.GREENHOUSE, "wise", "999", "Backend Engineer");
        greenhouse.setFirstSeen(Instant.now());
        greenhouse.setLastSeen(Instant.now());

        Posting lever = new Posting(Source.LEVER, "wise", "999", "Backend Engineer");
        lever.setFirstSeen(Instant.now());
        lever.setLastSeen(Instant.now());

        postings.saveAndFlush(greenhouse);
        postings.saveAndFlush(lever);

        assertThat(postings.countBySourceAndBoardToken(Source.GREENHOUSE, "wise")).isEqualTo(1);
        assertThat(postings.countBySourceAndBoardToken(Source.LEVER, "wise")).isEqualTo(1);
    }

    @Test
    @DisplayName("dates are stored as ISO text, not as timezone-dependent millis")
    void storesDatesAsIsoText() {
        Posting p = new Posting(Source.GREENHOUSE, "adyen", "date-check", "Engineer");
        p.setFirstSeen(Instant.now());
        p.setLastSeen(Instant.now());
        p.setPostedDate(LocalDate.of(2026, 8, 31));
        postings.saveAndFlush(p);

        // Read the raw column, bypassing Hibernate. sqlite-jdbc's default binding
        // would put local midnight here as epoch millis, which a JVM in another
        // timezone reads back as the previous day - so asserting the round-trip
        // through Hibernate alone would not catch the bug on this machine.
        String stored = jdbc.queryForObject(
                "select posted_date from posting where external_id = 'date-check'", String.class);
        assertThat(stored).isEqualTo("2026-08-31");

        assertThat(postings.findBySourceAndBoardTokenAndExternalId(
                        Source.GREENHOUSE, "adyen", "date-check")
                .orElseThrow()
                .getPostedDate())
                .isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    @DisplayName("board health is recorded so a board that starts failing stays visible")
    void recordsBoardHealth() {
        BoardToken token = new BoardToken(Source.GREENHOUSE, "celonis", "Celonis");
        token.recordSuccess(412, Instant.parse("2026-09-06T01:30:00Z"));
        boards.saveAndFlush(token);

        BoardToken healthy = boards.findBySourceAndToken(Source.GREENHOUSE, "celonis").orElseThrow();
        assertThat(healthy.getLastPostingCount()).isEqualTo(412);
        assertThat(healthy.getLastError()).isNull();
        assertThat(boards.findByLastErrorIsNotNull()).isEmpty();

        healthy.recordFailure("404 Not Found", Instant.parse("2026-09-07T01:30:00Z"));
        boards.saveAndFlush(healthy);

        assertThat(boards.findByLastErrorIsNotNull()).hasSize(1);
        // The last known good count survives the failure, so the digest can say
        // "was 412, now erroring" rather than "0 postings".
        assertThat(boards.findByLastErrorIsNotNull().getFirst().getLastPostingCount()).isEqualTo(412);
    }
}
