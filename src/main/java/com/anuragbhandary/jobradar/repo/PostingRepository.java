package com.anuragbhandary.jobradar.repo;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    /** Lookup by natural key. This is what change detection uses to decide NEW vs seen. */
    Optional<Posting> findBySourceAndBoardTokenAndExternalId(
            Source source, String boardToken, String externalId);

    /**
     * Everything eligible, whatever the strategy thinks of it.
     *
     * <p>Includes the US relocation roles and the countries nobody has written a
     * policy for. Use it for counting and for an explicit "show me everything"
     * view; {@link #findRecommended()} is what a feed should ask for.
     */
    List<Posting> findByVerdict(Verdict verdict);

    /**
     * Eligible <em>and</em> recommended by the current strategy.
     *
     * <p>The two axes this phase separated, in one query. Screening now keeps
     * every posting it can classify - 277 are eligible where 56 used to be - so a
     * feed that asks only for CANDIDATE gets 137 American roles it has been told
     * not to recommend.
     *
     * <p>A null outcome counts as recommended on purpose. That is what every row
     * screened before this phase looks like, so a database that has not been
     * re-screened yet behaves exactly as it did before rather than going empty.
     */
    @Query("""
            select p from Posting p
            where p.verdict = com.anuragbhandary.jobradar.domain.Verdict.CANDIDATE
              and (p.strategyOutcome is null
                   or p.strategyOutcome
                        = com.anuragbhandary.jobradar.strategy.StrategyOutcome.RECOMMENDED)
            """)
    List<Posting> findRecommended();

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
