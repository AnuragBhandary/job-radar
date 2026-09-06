package com.anuragbhandary.jobradar.diff;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.fetch.PostingMapper;
import com.anuragbhandary.jobradar.fetch.RawPosting;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Works out what changed since the last run.
 *
 * <p>This is the part of the tool that has any value. Anyone can list a job
 * board; the useful question at seven in the morning is which four of five
 * thousand postings were not there yesterday.
 */
@Component
public class ChangeDetector {

    private static final Logger log = LoggerFactory.getLogger(ChangeDetector.class);

    /** How long a posting may be missing from its board before it is closed. */
    public static final int DAYS_MISSING_BEFORE_CLOSED = 7;

    private final PostingMapper mapper;
    private final PostingRepository postings;

    public ChangeDetector(PostingMapper mapper, PostingRepository postings) {
        this.mapper = mapper;
        this.postings = postings;
    }

    /**
     * Classifies one fetched posting against what is already stored.
     *
     * <p>The comparison is always on the description hash, never on the board's
     * own {@code updated_at}. Celonis bulk-refreshes that field on every posting
     * daily, so on that board it reports change constantly and means nothing -
     * and a field that is wrong in a way that looks like it is working is worse
     * than no field at all.
     *
     * @param existing the stored posting, or null if this is the first sighting
     * @return the posting to save, with its status set
     */
    public Posting record(
            Posting existing, Source source, String boardToken, RawPosting raw, Instant now) {

        if (existing == null) {
            Posting created = mapper.toNewPosting(source, boardToken, raw, now);
            created.setStatus(PostingStatus.NEW);
            return created;
        }

        String previousHash = existing.getDescriptionHash();
        mapper.applyFields(existing, raw, now);
        existing.setStatus(
                Objects.equals(previousHash, existing.getDescriptionHash())
                        ? PostingStatus.SEEN
                        // A posting that reappears after being closed is a real
                        // change worth reporting, so UPDATED rather than SEEN.
                        : PostingStatus.UPDATED);
        return existing;
    }

    /**
     * Marks postings CLOSED when they have been absent from a healthy board for
     * {@link #DAYS_MISSING_BEFORE_CLOSED} days.
     *
     * @param healthyBoards boards whose most recent fetch succeeded. Boards that
     *                      failed are excluded deliberately: a 404 makes every
     *                      posting on that board look absent, and closing them
     *                      would silently convert a broken token into "this
     *                      company stopped hiring".
     * @return how many postings were closed
     */
    public int closeStale(List<BoardToken> healthyBoards, Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(DAYS_MISSING_BEFORE_CLOSED));
        int closed = 0;

        for (BoardToken board : healthyBoards) {
            List<Posting> stale =
                    postings.findBySourceAndBoardTokenAndLastSeenBeforeAndStatusNot(
                            board.getSource(), board.getToken(), cutoff, PostingStatus.CLOSED);
            stale.forEach(p -> p.setStatus(PostingStatus.CLOSED));
            postings.saveAll(stale);
            closed += stale.size();
        }

        if (closed > 0) {
            log.info("Closed {} postings absent for more than {} days",
                    closed, DAYS_MISSING_BEFORE_CLOSED);
        }
        return closed;
    }
}
