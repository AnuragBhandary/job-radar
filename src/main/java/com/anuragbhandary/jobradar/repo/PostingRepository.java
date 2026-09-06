package com.anuragbhandary.jobradar.repo;

import com.anuragbhandary.jobradar.domain.Posting;
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

    long countBySourceAndBoardToken(Source source, String boardToken);
}
