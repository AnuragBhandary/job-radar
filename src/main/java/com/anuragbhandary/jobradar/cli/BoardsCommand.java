package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code boards [--idle] [--disable=SOURCE/token,...] [--enable=...]} - what each
 * board has been worth.
 *
 * <p>On 2026-09-28, 107 of 144 boards had never produced a single recommended
 * posting, while every one of them cost a request or several on each run. This
 * prints per board what it stores, how many ever became recommended, and how many
 * were shortlisted or applied to, so the dead weight can be switched off. The
 * judgement stays with a person: a board of a company worth working for is worth
 * keeping even when it is quiet.
 */
@Component
public class BoardsCommand {

    /** Past this many stored postings with nothing ever recommended, a board is flagged. */
    static final int IDLE_AFTER_STORED = 20;

    private final BoardTokenRepository boards;
    private final EntityManager entities;

    public BoardsCommand(BoardTokenRepository boards, EntityManager entities) {
        this.boards = boards;
        this.entities = entities;
    }

    /** A board younger than this has not had the chance to yield anything. */
    static final java.time.Duration GRACE = java.time.Duration.ofDays(14);

    record Yield(BoardToken board, long stored, long recommended, long pursued,
            java.time.Instant firstSeen) {
        boolean idle() {
            return recommended == 0 && stored >= IDLE_AFTER_STORED && firstSeen != null
                    && firstSeen.isBefore(java.time.Instant.now().minus(GRACE));
        }
    }

    @Transactional
    public void run(Map<String, String> options) {
        if (options.containsKey("disable") || options.containsKey("enable")) {
            toggle(options.get("disable"), false);
            toggle(options.get("enable"), true);
            return;
        }

        List<Yield> yields = yields();
        boolean idleOnly = options.containsKey("idle");
        List<Yield> shown = yields.stream()
                .filter(y -> !idleOnly || y.idle())
                .sorted(Comparator.comparingLong(Yield::recommended)
                        .thenComparing(Comparator.comparingLong(Yield::stored).reversed()))
                .toList();

        System.out.printf("%-52s %8s %7s %5s %7s%n", "board", "listed", "stored", "rec", "pursued");
        for (Yield y : shown) {
            BoardToken b = y.board();
            String name = b.getSource() + "/" + b.getToken();
            System.out.printf("%-52s %8s %7d %5d %7d%s%n", clip(name, 52),
                    b.getLastPostingCount() == null ? "-" : b.getLastPostingCount().toString(),
                    y.stored(), y.recommended(), y.pursued(), y.idle() ? "  idle" : "");
        }
        long idle = yields.stream().filter(Yield::idle).count();
        System.out.println();
        System.out.println(yields.size() + " active boards; " + idle + " idle (" + IDLE_AFTER_STORED
                + "+ stored, never recommended, fetched for " + GRACE.toDays() + "+ days).");
        System.out.println("listed = what the board advertised on its last fetch; stored = kept here;");
        System.out.println("rec = ever recommended for review; pursued = shortlisted or further.");
        if (idle > 0) {
            System.out.println("Switch one off: boards --disable=SOURCE/token[,SOURCE/token...]");
        }
    }

    private List<Yield> yields() {
        Map<String, long[]> counts = new HashMap<>();
        Map<String, java.time.Instant> firstSeen = new HashMap<>();
        List<Object[]> rows = entities.createQuery("""
                select p.source, p.boardToken, count(p),
                       sum(case when p.recommendedSince is not null then 1 else 0 end),
                       min(p.firstSeen)
                from Posting p group by p.source, p.boardToken""", Object[].class).getResultList();
        for (Object[] r : rows) {
            firstSeen.put(r[0] + "/" + r[1], (java.time.Instant) r[4]);
            counts.put(r[0] + "/" + r[1], new long[] {
                    ((Number) r[2]).longValue(), r[3] == null ? 0 : ((Number) r[3]).longValue(), 0});
        }
        List<Object[]> pursued = entities.createQuery("""
                select p.source, p.boardToken, count(i) from JobInterest i, Posting p
                where i.postingId = p.id
                  and i.stage <> com.anuragbhandary.jobradar.pipeline.PipelineStage.DROPPED
                group by p.source, p.boardToken""", Object[].class).getResultList();
        for (Object[] r : pursued) {
            counts.computeIfAbsent(r[0] + "/" + r[1], k -> new long[3])[2] =
                    ((Number) r[2]).longValue();
        }

        List<Yield> yields = new ArrayList<>();
        for (BoardToken b : boards.findByActiveTrue()) {
            long[] c = counts.getOrDefault(b.getSource() + "/" + b.getToken(), new long[3]);
            yields.add(new Yield(b, c[0], c[1], c[2],
                    firstSeen.get(b.getSource() + "/" + b.getToken())));
        }
        return yields;
    }

    private void toggle(String list, boolean active) {
        if (list == null || list.isBlank()) {
            return;
        }
        for (String raw : list.split(",")) {
            String entry = raw.strip();
            int slash = entry.indexOf('/');
            if (slash <= 0) {
                System.out.println("Not SOURCE/token: " + entry);
                continue;
            }
            Source source;
            try {
                source = Source.valueOf(entry.substring(0, slash).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                System.out.println("Unknown source: " + entry.substring(0, slash));
                continue;
            }
            Optional<BoardToken> board = boards.findBySourceAndToken(source, entry.substring(slash + 1));
            if (board.isEmpty()) {
                System.out.println("No board " + entry);
                continue;
            }
            BoardToken b = board.get();
            b.setActive(active);
            // The "retired:" marker keeps a deliberately switched-off board out of
            // the failing-boards line of the handoff file.
            b.setLastError(active ? null : "retired: switched off " + LocalDate.now() + " (no yield)");
            boards.save(b);
            System.out.println((active ? "Enabled " : "Disabled ") + entry);
        }
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
