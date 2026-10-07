package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.pipeline.JobInterest;
import com.anuragbhandary.jobradar.pipeline.JobInterestRepository;
import com.anuragbhandary.jobradar.pipeline.PipelineStage;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.anuragbhandary.jobradar.sheets.SheetsClient;
import com.anuragbhandary.jobradar.strategy.StrategyOutcome;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Decides what goes in a handoff file. Formatting is {@link DigestWriter}'s job. */
@Service
public class DigestService {

    /**
     * Freshest first, then by company to keep a company's roles together.
     *
     * <p>Postings with no date sort last rather than first: an absent date is not
     * evidence of freshness.
     */
    private static final Comparator<Posting> BY_RECENCY_THEN_COMPANY =
            Comparator.comparing(Posting::getPostedDate,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Posting::getBoardToken)
                    .thenComparing(Posting::getTitle);

    private final PostingRepository postings;
    private final BoardTokenRepository boards;
    private final SheetsClient sheets;
    private final JobInterestRepository interests;
    private final AppProperties.SalaryFloors floors;
    private final int staleDays;
    private final java.util.Set<String> staleExempt;

    private final FitScore fitScore;

    public DigestService(
            PostingRepository postings,
            BoardTokenRepository boards,
            SheetsClient sheets,
            JobInterestRepository interests,
            AppProperties properties,
            @Value("${job-radar.digest.stale-days:45}") int staleDays,
            @Value("${job-radar.digest.stale-exempt-boards:}") String staleExempt,
            FitScore fitScore) {
        this.fitScore = fitScore;
        this.postings = postings;
        this.boards = boards;
        this.sheets = sheets;
        this.interests = interests;
        this.floors = properties.salaryFloors();
        this.staleDays = staleDays;
        this.staleExempt = java.util.Arrays.stream(staleExempt.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /** Today's file: candidates that are new or changed since the last fetch. */
    @Transactional(readOnly = true)
    public Digest build(LocalDate date) {
        // Only the statuses a daily file reports on. SEEN is what a posting becomes
        // on its second day, so on a mature database it is nearly every row.
        List<Posting> recent = postings.findByStatusIn(List.of(
                PostingStatus.NEW, PostingStatus.UPDATED, PostingStatus.CLOSED));
        Predicate<Posting> fresh = p -> p.getStatus() == PostingStatus.NEW
                || p.getStatus() == PostingStatus.UPDATED;
        return assemble(date, null, recent, fresh);
    }

    /**
     * Every open candidate first seen on or after {@code since}.
     *
     * <p>A daily file lists only what the last fetch found new, so a missed day's
     * postings are SEEN by the next morning and never appear in a daily file again.
     * This is how they are caught up: by date, not by status.
     */
    @Transactional(readOnly = true)
    public Digest buildSince(LocalDate date, LocalDate since) {
        Instant from = since.atStartOfDay(ZoneId.systemDefault()).toInstant();
        List<Posting> window = postings.findByVerdict(Verdict.CANDIDATE).stream()
                .filter(p -> p.getFirstSeen() != null && !p.getFirstSeen().isBefore(from))
                .toList();
        return assemble(date, since, window, p -> p.getStatus() != PostingStatus.CLOSED);
    }

    /**
     * Everything that became worth reviewing since the last reviewed window.
     *
     * <p>Dated by when a posting became a recommended candidate, not when it was
     * fetched, so a rule change or a strategy change that makes an old posting
     * eligible still reaches the next review. A description that changed since
     * then comes back too.
     */
    @Transactional(readOnly = true)
    public Digest buildOpenings(LocalDate date, Instant since) {
        List<Posting> window = postings.findByVerdict(Verdict.CANDIDATE).stream()
                .filter(p -> {
                    Instant news = p.getNewsworthySince();
                    boolean isNew = news != null && !news.isBefore(since);
                    boolean changed = p.getStatus() == PostingStatus.UPDATED
                            && p.getLastSeen() != null && !p.getLastSeen().isBefore(since);
                    return isNew || changed;
                })
                .toList();
        Digest base = assemble(date, since.atZone(ZoneId.systemDefault()).toLocalDate(),
                window, p -> p.getStatus() != PostingStatus.CLOSED);
        List<JobInterest> shortlisted = interests.findAll()
                .stream()
                .filter(i -> i.getStage() == PipelineStage.SAVED)
                .sorted(Comparator.comparing(
                        JobInterest::getSavedAt))
                .toList();
        Map<String, String> labels = boards.findByActiveTrue().stream()
                .filter(b -> b.getLabel() != null)
                .collect(Collectors.toMap(BoardToken::getToken, BoardToken::getLabel, (a, b) -> a));
        List<Digest.Entry> ranked = ranked(base.candidates(), date, labels);
        List<Digest.Entry> kept = ranked.stream()
                .filter(e -> e.fit().score() >= fitScore.minFit())
                .toList();
        int listed = ranked.stream().mapToInt(e -> e.ids().size()).sum();
        int keptListed = kept.stream().mapToInt(e -> e.ids().size()).sum();
        return new Digest(base.date(), base.since(), kept, base.closed(),
                base.rejections(), base.boards(), base.salaryFloorsNeedReverification(),
                base.alreadyDecided(), base.duplicatesCollapsed(), base.staleSetAside(),
                shortlisted, listed - keptListed);
    }

    /**
     * The openings sorted by fit, best first, with the ones past the configured
     * count marked for a one-line entry. Entries under the fit floor are counted
     * and left out of the file; {@code openings --done} closes the window over
     * them too, since it closes by time.
     */
    private List<Digest.Entry> ranked(List<Digest.Entry> entries, LocalDate today,
            Map<String, String> labels) {
        List<Digest.Entry> scored = entries.stream()
                .map(e -> new Digest.Entry(e.posting(), e.updated(), e.companyApplied(),
                        fitScore.fit(e.posting(), today), false))
                .sorted(Comparator.comparingInt((Digest.Entry e) -> e.fit().score()).reversed())
                .toList();
        List<Digest.Entry> grouped = grouped(scored, labels);
        int full = fitScore.fullDetail();
        List<Digest.Entry> out = new java.util.ArrayList<>(grouped.size());
        for (int i = 0; i < grouped.size(); i++) {
            Digest.Entry e = grouped.get(i);
            out.add(i < full ? e : new Digest.Entry(e.posting(), e.updated(), e.companyApplied(),
                    e.fit(), true, e.similar()));
        }
        return out;
    }

    /**
     * Folds same-employer openings whose titles differ only in brackets under the
     * best-scoring one. Unlike {@link #dedupe} nothing is dropped: they may be
     * different requisitions (Visa's two Data Engineer roles on 2026-10-07 had
     * different bars), so each keeps its id and needs its own decision. It only
     * saves reading the same company's pitch twice.
     */
    static List<Digest.Entry> grouped(List<Digest.Entry> best, Map<String, String> labels) {
        Map<String, Digest.Entry> heads = new LinkedHashMap<>();
        Map<String, List<Digest.Entry>> followers = new LinkedHashMap<>();
        for (Digest.Entry e : best) {
            String key = similarKey(e.posting(), labels);
            if (heads.putIfAbsent(key, e) != null) {
                followers.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(e);
            }
        }
        return heads.entrySet().stream()
                .map(h -> {
                    Digest.Entry e = h.getValue();
                    List<Digest.Entry> similar = followers.getOrDefault(h.getKey(), List.of());
                    return similar.isEmpty() ? e : new Digest.Entry(e.posting(), e.updated(),
                            e.companyApplied(), e.fit(), e.compact(), List.copyOf(similar));
                })
                .toList();
    }

    /** The role key with bracketed qualifiers taken out of the title. */
    static String similarKey(Posting posting, Map<String, String> labels) {
        String title = posting.getTitle() == null ? "" : posting.getTitle()
                .replaceAll("\\([^)]*\\)|\\[[^]]*\\]", " ")
                .replaceAll("\\s+", " ").strip();
        String key = com.anuragbhandary.jobradar.domain.Employer.roleKey(
                labels.getOrDefault(posting.getBoardToken(), posting.getBoardToken()),
                posting.getSource(), title);
        String normalised = key.substring(key.indexOf('|') + 1);
        // As in roleKey: a short generic title is only the same role in one country.
        return normalised.split(" ").length <= 3 ? key + "|" + posting.getCountryCode() : key;
    }

    private Digest assemble(LocalDate date, LocalDate since, List<Posting> all,
            Predicate<Posting> inScope) {
        List<BoardToken> activeBoards = boards.findByActiveTrue();

        // Companies already applied to, flagged on each entry. Empty when Sheets is
        // unconfigured or unreachable, which costs the flag and nothing else.
        Set<String> applied = sheets.appliedCompanies();
        Map<String, String> labels = activeBoards.stream()
                .filter(b -> b.getLabel() != null)
                .collect(Collectors.toMap(BoardToken::getToken, BoardToken::getLabel,
                        (a, b) -> a));
        Predicate<Posting> companyApplied = p -> applied.contains(
                com.anuragbhandary.jobradar.domain.Employer.company(
                                labels.getOrDefault(p.getBoardToken(), p.getBoardToken()),
                                p.getSource(), p.getTitle())
                        .toLowerCase(Locale.ROOT).trim());

        // Marked applied, skipped or shortlisted. Any decision takes a posting out:
        // the file is for postings nobody has looked at yet.
        Set<Long> decided = interests.findAll().stream()
                .map(JobInterest::getPostingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // A decision covers the role, not just the row: a relisted copy of a role
        // already applied to or skipped (new id, other board) is decided too.
        List<Posting> decidedPostings = postings.findAllById(decided);
        Set<String> decidedRoles = decidedPostings.stream()
                .map(p -> roleKey(p, labels))
                .collect(Collectors.toSet());
        // The same Oracle requisition seen through another of the employer's sites.
        Set<String> decidedRequisitions = decidedPostings.stream()
                .map(DigestService::requisitionKey)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toSet());
        Predicate<Posting> undecided = p -> !decided.contains(p.getId())
                && !decidedRoles.contains(roleKey(p, labels))
                && requisitionKey(p).map(k -> !decidedRequisitions.contains(k)).orElse(true);

        // Eligible AND recommended. A null outcome counts as recommended: that is
        // what a row screened before the strategy columns existed looks like.
        Predicate<Posting> candidate = p -> p.getVerdict() == Verdict.CANDIDATE
                && (p.getStrategyOutcome() == null
                        || p.getStrategyOutcome() == StrategyOutcome.RECOMMENDED);

        // Open long enough to be stale: taken out and counted, never re-judged.
        // Employers that keep standing roles open for years (Canonical's are dated
        // 2019-2024) are exempt: for them the posted date says nothing.
        Predicate<Posting> stale = p -> staleDays > 0 && p.getPostedDate() != null
                && !staleExempt.contains(p.getBoardToken().toLowerCase(Locale.ROOT))
                && ChronoUnit.DAYS.between(p.getPostedDate(), date) >= staleDays;

        // A board switched off (a retired Oracle test copy, a board disabled by
        // hand) is never fetched again, so its postings would stay open forever.
        Set<String> switchedOff = boards.findAll().stream()
                .filter(b -> !b.isActive())
                .map(b -> b.getSource() + "|" + b.getToken())
                .collect(Collectors.toSet());
        Predicate<Posting> onLiveBoard = p -> !switchedOff.contains(p.getSource() + "|" + p.getBoardToken());

        List<Posting> eligible = all.stream().filter(inScope).filter(onLiveBoard).filter(candidate).toList();
        List<Posting> open = eligible.stream().filter(undecided).toList();

        List<Posting> raw = open.stream()
                .filter(stale.negate())
                .sorted(BY_RECENCY_THEN_COMPANY)
                .toList();
        List<Posting> deduped = dedupe(raw, labels);

        int staleSetAside = (int) open.stream().filter(stale)
                .map(p -> p.getBoardToken() + " " + p.getTitle())
                .distinct()
                .count();

        List<Digest.Entry> candidates = deduped.stream()
                .map(p -> new Digest.Entry(p, p.getStatus() == PostingStatus.UPDATED,
                        companyApplied.test(p)))
                .toList();

        // Closures and rejections describe a fetch, so only the daily file has them.
        List<Posting> closed = since != null ? List.of() : all.stream()
                .filter(p -> p.getStatus() == PostingStatus.CLOSED)
                .filter(candidate)
                .sorted(BY_RECENCY_THEN_COMPANY)
                .toList();

        Map<String, Long> rejections = since != null ? Map.of() : all.stream()
                .filter(inScope)
                .filter(p -> p.getVerdict() == Verdict.REJECTED)
                .collect(Collectors.groupingBy(DigestService::category, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        return new Digest(date, since, candidates, closed, rejections, activeBoards,
                floors != null && floors.needsReverification(date),
                (int) eligible.stream().filter(undecided.negate()).count(),
                raw.size() - deduped.size(),
                staleSetAside);
    }

    private static String category(Posting posting) {
        String reason = posting.getRejectReason();
        if (reason == null) {
            return "unknown";
        }
        int cut = reason.indexOf(": ");
        return (cut > 0 ? reason.substring(0, cut) : reason)
                .replaceFirst("^\\d+ years", "N years");
    }

    /**
     * Collapses repeat listings of the same role at the same company.
     *
     * <p>AWS advertises one Dublin role three times under three requisition ids:
     * three vacancies, one application. Keyed on company and title, and input order
     * is preserved, so the first listing of a role survives.
     */
    private static List<Posting> dedupe(List<Posting> postings, Map<String, String> labels) {
        Map<String, Posting> byRole = new LinkedHashMap<>();
        // One Oracle requisition is listed on every candidate site of its employer
        // (external, alumni, veterans...), each a board of its own here.
        Set<String> requisitions = new java.util.HashSet<>();
        for (Posting posting : postings) {
            java.util.Optional<String> requisition = requisitionKey(posting);
            if (requisition.isPresent() && !requisitions.add(requisition.get())) {
                continue;
            }
            // A direct board's copy wins over an aggregator's, whichever came first:
            // it carries the employer's own link.
            String key = roleKey(posting, labels);
            Posting kept = byRole.get(key);
            if (kept == null || (isAggregator(kept) && !isAggregator(posting))) {
                byRole.put(key, posting);
            }
        }
        return List.copyOf(byRole.values());
    }

    private static java.util.Optional<String> requisitionKey(Posting posting) {
        return com.anuragbhandary.jobradar.fetch.OracleTenants.requisitionKey(
                posting.getSource(), posting.getBoardToken(), posting.getExternalId());
    }

    private static String roleKey(Posting posting, Map<String, String> labels) {
        String key = com.anuragbhandary.jobradar.domain.Employer.roleKey(
                labels.getOrDefault(posting.getBoardToken(), posting.getBoardToken()),
                posting.getSource(), posting.getTitle());
        // "Software Engineer" at a large employer is many different jobs. Short,
        // generic titles only count as the same role in the same country.
        String title = key.substring(key.indexOf('|') + 1);
        return title.split(" ").length <= 3
                ? key + "|" + posting.getCountryCode()
                : key;
    }

    private static boolean isAggregator(Posting posting) {
        return switch (posting.getSource()) {
            case ARBEITNOW, JOBICY, WE_WORK_REMOTELY, HACKER_NEWS -> true;
            default -> false;
        };
    }
}
