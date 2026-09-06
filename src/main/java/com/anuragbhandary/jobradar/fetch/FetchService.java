package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.diff.ChangeDetector;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs fetchers over boards and writes the results to the database.
 *
 * <p>Boards are fetched sequentially. That is not an oversight: the throttle in
 * {@link HttpFetchClient} is global, so running four threads against a 500ms
 * inter-request delay would produce exactly the same wall-clock time with four
 * times the ways to go wrong.
 *
 * <p>Each board commits in its own transaction, so one board failing halfway
 * cannot roll back the boards that already succeeded.
 */
@Service
public class FetchService {

    private static final Logger log = LoggerFactory.getLogger(FetchService.class);

    private final Map<Source, AtsFetcher> fetchers = new EnumMap<>(Source.class);
    private final PostingRepository postings;
    private final BoardTokenRepository boards;
    private final ChangeDetector changes;
    private final TransactionTemplate transaction;

    public FetchService(
            List<AtsFetcher> fetchers,
            PostingRepository postings,
            BoardTokenRepository boards,
            ChangeDetector changes,
            PlatformTransactionManager transactionManager) {
        fetchers.forEach(f -> this.fetchers.put(f.source(), f));
        this.postings = postings;
        this.boards = boards;
        this.changes = changes;
        // An explicit template rather than @Transactional on fetchBoard: that
        // method is called from another method of this same bean, so the
        // proxy would never see the call and the annotation would silently do
        // nothing. REQUIRES_NEW so one board's failure cannot roll back the
        // boards that already succeeded.
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Fetches every active board. */
    public List<FetchResult> fetchAll() {
        return fetch(boards.findByActiveTrue());
    }

    /** Fetches every active board belonging to one ATS. */
    public List<FetchResult> fetchSource(Source source) {
        return fetch(boards.findBySourceAndActiveTrue(source));
    }

    /** Fetches a single board, whether or not it is marked active. */
    public List<FetchResult> fetchOne(Source source, String token) {
        Optional<BoardToken> board = boards.findBySourceAndToken(source, token);
        if (board.isEmpty()) {
            return List.of(FetchResult.failure(source, token, "not a known board token"));
        }
        return fetch(List.of(board.get()));
    }

    private List<FetchResult> fetch(List<BoardToken> targets) {
        List<FetchResult> results = new ArrayList<>(targets.size());
        for (BoardToken board : targets) {
            results.add(transaction.execute(status -> fetchBoard(board)));
            log.info("{}", results.getLast());
        }

        // Only boards that actually answered may have their postings closed. A
        // board that failed makes all of its postings look absent, and closing
        // them would turn a broken token into "this company stopped hiring".
        List<BoardToken> healthy = targets.stream()
                .filter(b -> results.stream().anyMatch(r ->
                        !r.failed() && r.source() == b.getSource()
                                && r.boardToken().equals(b.getToken())))
                .toList();
        transaction.executeWithoutResult(status ->
                changes.closeStale(healthy, Instant.now()));

        return results;
    }

    /** One board's work. Always invoked inside {@link #transaction}. */
    private FetchResult fetchBoard(BoardToken board) {
        AtsFetcher fetcher = fetchers.get(board.getSource());
        Instant now = Instant.now();

        if (fetcher == null) {
            // A source with a seeded token but no implementation yet. Expected
            // between milestones; recorded rather than thrown.
            return recordFailure(board, "no fetcher implemented for " + board.getSource(), now);
        }

        FetchBatch batch;
        try {
            batch = fetcher.fetch(board.getToken());
        } catch (FetchException e) {
            return recordFailure(board, e.getMessage(), now);
        }

        int created = 0;
        int updated = 0;
        int unchanged = 0;

        for (RawPosting posting : batch.postings()) {
            Posting existing = postings.findBySourceAndBoardTokenAndExternalId(
                    board.getSource(), board.getToken(), posting.externalId()).orElse(null);

            Posting saved = postings.save(changes.record(
                    existing, board.getSource(), board.getToken(), posting, now));

            switch (saved.getStatus()) {
                case NEW -> created++;
                case UPDATED -> updated++;
                default -> unchanged++;
            }
        }

        // Board health records what the board advertises; the digest counts
        // what we kept. Conflating them would make a filtered board look dead.
        board.recordSuccess(batch.boardTotal(), now);
        boards.save(board);
        return new FetchResult(board.getSource(), board.getToken(),
                batch.boardTotal(), created, updated, unchanged, null);
    }

    private FetchResult recordFailure(BoardToken board, String error, Instant now) {
        board.recordFailure(error, now);
        boards.save(board);
        return FetchResult.failure(board.getSource(), board.getToken(), error);
    }
}
