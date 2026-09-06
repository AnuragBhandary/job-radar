package com.anuragbhandary.jobradar.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.fetch.PostingMapper;
import com.anuragbhandary.jobradar.fetch.RawPosting;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChangeDetectorTest {

    private static final Instant DAY_ONE = Instant.parse("2026-09-01T02:00:00Z");
    private static final Instant DAY_TWO = Instant.parse("2026-09-02T02:00:00Z");

    @Mock
    private PostingRepository postings;

    private ChangeDetector detector() {
        return new ChangeDetector(new PostingMapper(), postings);
    }

    private static RawPosting raw(String description) {
        return new RawPosting("123", "Software Engineer", "Dublin, Ireland",
                description, "https://example.com/123", LocalDate.of(2026, 9, 1));
    }

    @Test
    @DisplayName("a posting never seen before is NEW")
    void firstSightingIsNew() {
        Posting result = detector()
                .record(null, Source.GREENHOUSE, "stripe", raw("hello"), DAY_ONE);

        assertThat(result.getStatus()).isEqualTo(PostingStatus.NEW);
        assertThat(result.getFirstSeen()).isEqualTo(DAY_ONE);
    }

    @Test
    @DisplayName("a posting whose description changed is UPDATED")
    void changedDescriptionIsUpdated() {
        ChangeDetector detector = detector();
        Posting stored = detector.record(null, Source.GREENHOUSE, "stripe", raw("v1"), DAY_ONE);

        Posting result = detector.record(
                stored, Source.GREENHOUSE, "stripe", raw("v2 - requirements changed"), DAY_TWO);

        assertThat(result.getStatus()).isEqualTo(PostingStatus.UPDATED);
        assertThat(result.getLastSeen()).isEqualTo(DAY_TWO);
        // firstSeen answers "how long has this been open", which a re-fetch
        // cannot recover once overwritten.
        assertThat(result.getFirstSeen()).isEqualTo(DAY_ONE);
    }

    @Test
    @DisplayName("an unchanged posting is SEEN and stays out of the digest")
    void unchangedIsSeen() {
        ChangeDetector detector = detector();
        Posting stored = detector.record(null, Source.GREENHOUSE, "stripe", raw("same"), DAY_ONE);

        Posting result = detector.record(
                stored, Source.GREENHOUSE, "stripe", raw("same"), DAY_TWO);

        assertThat(result.getStatus()).isEqualTo(PostingStatus.SEEN);
        assertThat(result.getLastSeen()).isEqualTo(DAY_TWO);
    }

    @Test
    @DisplayName("whitespace reformatting alone is not a change")
    void reformattingIsNotAChange() {
        ChangeDetector detector = detector();
        Posting stored = detector.record(
                null, Source.GREENHOUSE, "stripe", raw("1-3 years of experience"), DAY_ONE);

        Posting result = detector.record(stored, Source.GREENHOUSE, "stripe",
                raw("1-3 years  of\n experience"), DAY_TWO);

        assertThat(result.getStatus()).isEqualTo(PostingStatus.SEEN);
    }

    @Test
    @DisplayName("postings missing from a healthy board for a week are CLOSED")
    void closesStalePostings() {
        BoardToken board = new BoardToken(Source.GREENHOUSE, "stripe", "Stripe");
        Posting stale = new Posting(Source.GREENHOUSE, "stripe", "old", "Software Engineer");
        when(postings.findBySourceAndBoardTokenAndLastSeenBeforeAndStatusNot(
                eq(Source.GREENHOUSE), eq("stripe"), any(), eq(PostingStatus.CLOSED)))
                .thenReturn(List.of(stale));

        int closed = detector().closeStale(List.of(board), Instant.now());

        assertThat(closed).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo(PostingStatus.CLOSED);
    }

    @Test
    @DisplayName("a board that failed to fetch never closes its postings")
    void doesNotCloseUnhealthyBoards() {
        // A 404 makes every posting on a board look absent. Closing them would
        // silently turn a broken token into "this company stopped hiring", which
        // is exactly the confusion board health exists to prevent.
        int closed = detector().closeStale(List.of(), Instant.now());

        assertThat(closed).isZero();
        verify(postings, org.mockito.Mockito.never())
                .findBySourceAndBoardTokenAndLastSeenBeforeAndStatusNot(
                        any(), any(), any(), any());
    }
}
