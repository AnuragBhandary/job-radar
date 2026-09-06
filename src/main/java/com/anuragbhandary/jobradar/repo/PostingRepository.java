package com.anuragbhandary.jobradar.repo;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    /** Lookup by natural key. This is what change detection uses to decide NEW vs seen. */
    Optional<Posting> findBySourceAndBoardTokenAndExternalId(
            Source source, String boardToken, String externalId);

    List<Posting> findByVerdict(Verdict verdict);

    List<Posting> findBySourceAndBoardToken(Source source, String boardToken);

    /** Postings not observed since the given instant - candidates for CLOSED. */
    List<Posting> findByLastSeenBefore(Instant cutoff);

    /**
     * Stale postings on one specific board. Scoped to a board on purpose: only
     * boards that fetched successfully may have their postings closed.
     */
    List<Posting> findBySourceAndBoardTokenAndLastSeenBeforeAndStatusNot(
            Source source, String boardToken, Instant cutoff, PostingStatus status);

    List<Posting> findByStatusIn(List<PostingStatus> statuses);

    long countBySourceAndBoardToken(Source source, String boardToken);
}
