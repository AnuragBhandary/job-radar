package com.anuragbhandary.jobradar.discover;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.DiscoveredBoard;
import com.anuragbhandary.jobradar.domain.DiscoveredBoard.Outcome;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.fetch.BoardSurveyor;
import com.anuragbhandary.jobradar.fetch.BoardSurveyor.Survey;
import com.anuragbhandary.jobradar.fetch.FetchException;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.DiscoveredBoardRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finds boards nobody named, surveys them, and adds the ones worth fetching.
 *
 * <p>The network half (archive queries and surveys) runs on virtual threads, one
 * site at a time per {@code HttpFetchClient}'s throttle; every database write
 * happens here on the calling thread, as in {@code FetchService}, because SQLite
 * takes one writer at a time.
 *
 * <p>Each board's survey is saved as soon as it finishes, so an interrupted run
 * loses nothing and the next one carries on where it stopped.
 */
@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    /** The sources the archive can find. */
    public static final List<Source> SOURCES = List.of(
            Source.GREENHOUSE, Source.LEVER, Source.ASHBY, Source.RECRUITEE,
            Source.SMARTRECRUITERS, Source.WORKDAY);

    /**
     * A Workday site larger than this is added as one search per target country
     * ({@code tenant/wdN/site/india}), because the fetcher's page limit would stop
     * a whole-site crawl before reaching the India desks.
     */
    static final int WORKDAY_WHOLE_SITE_MAX = 600;

    /**
     * @param sources     which platforms to search
     * @param deep        also page through every archived URL, not just home pages
     * @param recheckDays survey again a board skipped this many days ago; 0 never
     * @param limit       surveys per platform this run; 0 for no limit
     * @param dryRun      survey and report, but write nothing
     */
    public record Options(List<Source> sources, boolean deep, int recheckDays, int limit,
            boolean dryRun) {
    }

    /** What one run did, per platform. */
    public record Report(Source source, int inArchive, int alreadyKnown, int surveyed,
            int added, int failed, List<String> addedBoards, String error) {
    }

    record Surveyed(Source source, String token, Survey survey, String error, String name) {

        Surveyed(Source source, String token, Survey survey, String error) {
            this(source, token, survey, error, null);
        }
    }

    private final ArchiveIndex archive;
    private final BoardSurveyor surveyor;
    private final BoardTokenRepository boards;
    private final DiscoveredBoardRepository discovered;

    public DiscoveryService(ArchiveIndex archive, BoardSurveyor surveyor,
            BoardTokenRepository boards, DiscoveredBoardRepository discovered) {
        this.archive = archive;
        this.surveyor = surveyor;
        this.boards = boards;
        this.discovered = discovered;
    }

    public List<Report> run(Options options) {
        Map<Source, Report> reports = new LinkedHashMap<>();
        Map<Source, List<String>> toSurvey = new LinkedHashMap<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // 1. Ask the archive, every platform at once.
            Map<Source, Future<Map<String, String>>> found = new LinkedHashMap<>();
            for (Source source : options.sources()) {
                found.put(source, pool.submit(() -> archive.boards(source, options.deep())));
            }
            for (Map.Entry<Source, Future<Map<String, String>>> e : found.entrySet()) {
                Source source = e.getKey();
                Map<String, String> inArchive;
                try {
                    inArchive = e.getValue().get();
                } catch (ExecutionException | InterruptedException ex) {
                    Throwable cause = ex instanceof ExecutionException ? ex.getCause() : ex;
                    log.warn("{}: archive query failed: {}", source, cause.getMessage());
                    reports.put(source, new Report(source, 0, 0, 0, 0, 0, List.of(),
                            "archive query failed: " + cause.getMessage()));
                    continue;
                }
                List<String> fresh = notYetSurveyed(source, inArchive, options);
                int known = inArchive.size() - fresh.size();
                if (options.limit() > 0 && fresh.size() > options.limit()) {
                    fresh = fresh.subList(0, options.limit());
                }
                toSurvey.put(source, fresh);
                reports.put(source, new Report(source, inArchive.size(), known, 0, 0, 0,
                        new ArrayList<>(), null));
                log.info("{}: {} boards in the archive, {} known or surveyed, {} to survey now",
                        source, inArchive.size(), known, fresh.size());
            }

            // 2. Survey, every platform at once; the throttle keeps each site polite.
            CompletionService<Surveyed> done = new ExecutorCompletionService<>(pool);
            int submitted = 0;
            for (Map.Entry<Source, List<String>> e : toSurvey.entrySet()) {
                for (String token : e.getValue()) {
                    done.submit(() -> survey(e.getKey(), token));
                    submitted++;
                }
            }
            Instant started = Instant.now();
            for (int i = 0; i < submitted; i++) {
                Surveyed result = take(done);
                Report report = reports.get(result.source());
                List<String> added = options.dryRun() ? addedTokens(result) : record(result);
                reports.put(result.source(), new Report(report.source(), report.inArchive(),
                        report.alreadyKnown(), report.surveyed() + 1,
                        report.added() + (added.isEmpty() ? 0 : 1),
                        report.failed() + (result.error() == null ? 0 : 1),
                        append(report.addedBoards(), added), null));
                if ((i + 1) % 250 == 0) {
                    log.info("Surveyed {}/{} in {} min", i + 1, submitted,
                            Duration.between(started, Instant.now()).toMinutes());
                }
            }
        }
        return new ArrayList<>(reports.values());
    }

    /** Boards in the archive that are neither fetched already nor surveyed recently. */
    private List<String> notYetSurveyed(Source source, Map<String, String> inArchive,
            Options options) {
        Set<String> known = new HashSet<>();
        for (BoardToken b : boards.findAll()) {
            if (b.getSource() == source) {
                known.add(baseToken(source, b.getToken()));
            }
        }
        Map<String, DiscoveredBoard> seen = new HashMap<>();
        for (DiscoveredBoard d : discovered.findBySource(source)) {
            seen.put(d.getToken().toLowerCase(Locale.ROOT), d);
        }
        Instant recheckBefore = options.recheckDays() > 0
                ? Instant.now().minus(Duration.ofDays(options.recheckDays())) : null;
        List<String> fresh = new ArrayList<>();
        for (Map.Entry<String, String> e : inArchive.entrySet()) {
            if (known.contains(e.getKey())) {
                continue;
            }
            DiscoveredBoard before = seen.get(e.getKey());
            if (before != null && (recheckBefore == null
                    || before.getOutcome() == Outcome.ADDED
                    || before.getSurveyedAt().isAfter(recheckBefore))) {
                continue;
            }
            fresh.add(e.getValue());
        }
        return fresh;
    }

    /** A Workday board seeded with a search is the same site as one without. */
    static String baseToken(Source source, String token) {
        String t = token.toLowerCase(Locale.ROOT);
        if (source == Source.WORKDAY) {
            String[] parts = t.split("/");
            return parts.length >= 3 ? parts[0] + "/" + parts[1] + "/" + parts[2] : t;
        }
        return t;
    }

    private Surveyed survey(Source source, String token) {
        try {
            Survey survey = surveyor.survey(source, token);
            // The name lookup costs a request, so only for boards being added.
            String name = survey.hasTargetRoles() ? surveyor.companyName(source, token) : null;
            return new Surveyed(source, token, survey, null, name);
        } catch (FetchException | RuntimeException e) {
            return new Surveyed(source, token, null, e.getMessage());
        }
    }

    /** Saves the survey and adds the board if it qualifies. Returns the tokens added. */
    private List<String> record(Surveyed result) {
        DiscoveredBoard row = discovered.findBySourceAndToken(result.source(), result.token())
                .orElseGet(() -> new DiscoveredBoard(result.source(), result.token()));
        Instant now = Instant.now();
        Survey s = result.survey();
        if (s == null) {
            row.record(Outcome.FAILED, now, null, null, null, null, result.error());
            discovered.save(row);
            return List.of();
        }
        List<String> added = addedTokens(result);
        row.record(added.isEmpty() ? Outcome.NO_TARGET_ROLES : Outcome.ADDED, now,
                s.total(), s.india(), s.relocation(), s.remote(), null);
        discovered.save(row);
        for (String token : added) {
            if (boards.findBySourceAndToken(result.source(), token).isEmpty()) {
                boards.save(new BoardToken(result.source(), token,
                        result.name() != null ? result.name() : label(result.source(), token)));
            }
        }
        return added;
    }

    /** The board tokens to add for a survey: none, the board, or per-country Workday searches. */
    static List<String> addedTokens(Surveyed result) {
        Survey s = result.survey();
        if (s == null || !s.hasTargetRoles()) {
            return List.of();
        }
        if (result.source() != Source.WORKDAY || s.total() <= WORKDAY_WHOLE_SITE_MAX) {
            return List.of(result.token());
        }
        List<String> searches = new ArrayList<>();
        if (s.india() > 0) {
            searches.add(result.token() + "/india");
        }
        for (String code : s.abroad().keySet().stream().sorted().toList()) {
            String search = BoardSurveyor.WORKDAY_SEARCH.get(code);
            if (search != null) {
                searches.add(result.token() + "/" + search);
            }
        }
        return searches;
    }

    /** A readable company name from a token: "stable-money1" to "Stable Money1". */
    static String label(Source source, String token) {
        String name = source == Source.WORKDAY ? token.split("/")[0] : token;
        StringBuilder out = new StringBuilder();
        for (String word : name.split("[-_. ]+")) {
            if (!word.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ")
                        .append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.isEmpty() ? token : out.toString();
    }

    private static List<String> append(List<String> list, List<String> more) {
        List<String> out = new ArrayList<>(list);
        out.addAll(more);
        return out;
    }

    private static Surveyed take(CompletionService<Surveyed> done) {
        try {
            return done.take().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while surveying", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
