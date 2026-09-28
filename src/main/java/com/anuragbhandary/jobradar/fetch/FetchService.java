package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.diff.ChangeDetector;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs fetchers over boards and writes the results to the database.
 *
 * <p>The network half runs concurrently, one virtual thread per board; the
 * database half runs on the calling thread, one board at a time, as fetches
 * finish. Concurrency is safe for the servers because {@link HttpFetchClient}
 * keeps its delay per site: the twelve Workday boards still reach Workday one
 * request at a time, but no longer wait behind Greenhouse. It is safe for the
 * database because SQLite has one writer, and only this thread writes.
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

    /** What one board's network half produced: a batch, or the reason there is none. */
    private record Fetched(BoardToken board, FetchBatch batch, String error, Duration took) {
    }

    private List<FetchResult> fetch(List<BoardToken> targets) {
        List<FetchResult> results = new ArrayList<>(targets.size());
        List<Fetched> timings = new ArrayList<>(targets.size());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletionService<Fetched> done = new ExecutorCompletionService<>(pool);
            for (BoardToken board : targets) {
                done.submit(() -> download(board));
            }
            for (int i = 0; i < targets.size(); i++) {
                Fetched fetched = take(done);
                results.add(transaction.execute(status -> store(fetched)));
                timings.add(fetched);
                log.info("{} in {}s", results.getLast(), fetched.took().toSeconds());
            }
        }
        logSlowest(timings);

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

    /** The network half of one board. Runs on its own virtual thread; touches no database. */
    private Fetched download(BoardToken board) {
        long start = System.nanoTime();
        AtsFetcher fetcher = fetchers.get(board.getSource());
        if (fetcher == null) {
            // A source with a seeded token but no implementation yet. Expected
            // between milestones; recorded rather than thrown.
            return new Fetched(board, null, "no fetcher implemented for " + board.getSource(),
                    Duration.ZERO);
        }
        try {
            return new Fetched(board, fetcher.fetch(board.getToken()), null,
                    Duration.ofNanos(System.nanoTime() - start));
        } catch (FetchException | RuntimeException e) {
            // A RuntimeException too: on a worker thread an unexpected one would
            // otherwise surface only as an ExecutionException, and lose the board.
            return new Fetched(board, null, String.valueOf(e.getMessage()),
                    Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private static Fetched take(CompletionService<Fetched> done) {
        try {
            return done.take().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching boards", e);
        } catch (ExecutionException e) {
            // download() catches everything it can; this is an Error.
            throw new IllegalStateException(e.getCause());
        }
    }

    /** The slowest boards, so the ones that cost the most time are visible. */
    private static void logSlowest(List<Fetched> timings) {
        String slowest = timings.stream()
                .sorted(Comparator.comparing(Fetched::took).reversed())
                .limit(5)
                .map(f -> f.board().getSource() + "/" + f.board().getToken() + " "
                        + f.took().toSeconds() + "s")
                .collect(Collectors.joining(", "));
        if (!slowest.isEmpty()) {
            log.info("Slowest boards: {}", slowest);
        }
    }

    /** The database half of one board. Always invoked inside {@link #transaction}. */
    private FetchResult store(Fetched fetched) {
        BoardToken board = boards.findById(fetched.board().getId()).orElse(fetched.board());
        Instant now = Instant.now();
        if (fetched.error() != null) {
            return recordFailure(board, fetched.error(), now);
        }
        FetchBatch batch = fetched.batch();

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
