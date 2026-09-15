package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.filter.ScreeningService;
import com.anuragbhandary.jobradar.match.MatchProperties;
import com.anuragbhandary.jobradar.match.MatchScore;
import com.anuragbhandary.jobradar.match.MatchScorer;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.anuragbhandary.jobradar.sheets.SheetsClient;
import com.anuragbhandary.jobradar.strategy.StrategyOutcome;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Decides what goes in the digest. Formatting is {@link DigestWriter}'s job. */
@Service
public class DigestService {

    /**
     * Freshest first, then alphabetical to keep a company's roles together.
     *
     * <p>Alphabetical alone put a requisition open for 479 days above one posted
     * yesterday, and nothing on the line said which was which. Age is the field
     * that separates two otherwise identical postings, and it was being captured
     * from the first milestone and never read.
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
    private final AppProperties.SalaryFloors floors;
    private final MatchScorer scorer;
    private final MatchProperties match;

    /** How many ranked rows open the digest. A shortlist, not a second copy of the lists. */
    static final int START_HERE = 5;

    public DigestService(
            PostingRepository postings,
            BoardTokenRepository boards,
            SheetsClient sheets,
            AppProperties properties,
            MatchScorer scorer,
            MatchProperties match) {
        this.postings = postings;
        this.boards = boards;
        this.sheets = sheets;
        this.floors = properties.salaryFloors();
        this.scorer = scorer;
        this.match = match;
    }

    @Transactional(readOnly = true)
    public Digest build(LocalDate date) {
        // Only the statuses the digest can report on. Every list below filters to
        // NEW, UPDATED or CLOSED anyway, so SEEN rows were being loaded to be
        // discarded - and SEEN is what a posting becomes on the second day it
        // exists, so on any mature database it is nearly all of them, each
        // carrying a description text averaging six kilobytes.
        List<Posting> all = postings.findByStatusIn(List.of(
                PostingStatus.NEW, PostingStatus.UPDATED, PostingStatus.CLOSED));
        List<com.anuragbhandary.jobradar.domain.BoardToken> activeBoards = boards.findByActiveTrue();

        // Companies already applied to. Empty when Sheets is unconfigured or
        // unreachable, so losing the tracker costs the digest its suppression
        // rather than its existence.
        Set<String> applied = sheets.appliedCompanies();
        Map<String, String> labels = activeBoards.stream()
                .filter(b -> b.getLabel() != null)
                .collect(Collectors.toMap(
                        com.anuragbhandary.jobradar.domain.BoardToken::getToken,
                        com.anuragbhandary.jobradar.domain.BoardToken::getLabel, (a, b) -> a));
        Predicate<Posting> notYetApplied = p -> !applied.contains(
                labels.getOrDefault(p.getBoardToken(), p.getBoardToken())
                        .toLowerCase(Locale.ROOT).trim());

        Predicate<Posting> fresh = p -> p.getStatus() == PostingStatus.NEW
                || p.getStatus() == PostingStatus.UPDATED;
        // Eligible AND recommended. Screening now classifies and keeps every
        // posting it can place instead of discarding whole countries, so a digest
        // filtered on the verdict alone would open with American roles the
        // strategy has been told not to recommend. A null outcome counts as
        // recommended: that is what a row screened before this phase looks like,
        // so an un-migrated database reads exactly as it always has.
        Predicate<Posting> candidate = p -> p.getVerdict() == Verdict.CANDIDATE
                && (p.getStrategyOutcome() == null
                        || p.getStrategyOutcome() == StrategyOutcome.RECOMMENDED);

        // Open long enough to be stale: taken out of the three lists and counted.
        // Never an eligibility decision - the row is untouched and still on /jobs.
        // Guarded so a missing match config cannot make every posting stale.
        Predicate<Posting> stale = p -> match != null && match.staleDays() > 0
                && p.getPostedDate() != null
                && ChronoUnit.DAYS.between(p.getPostedDate(), date) >= match.staleDays();

        // A candidate stating no years goes to review rather than to the
        // candidate list, so the two sections do not report the same posting
        // twice and the candidate list stays worth trusting.
        long suppressed = all.stream()
                .filter(fresh).filter(candidate)
                .filter(notYetApplied.negate())
                .count();

        List<Posting> newCandidatesRaw = all.stream()
                .filter(p -> p.getStatus() == PostingStatus.NEW)
                .filter(candidate)
                .filter(notYetApplied)
                .filter(stale.negate())
                .filter(p -> !ScreeningService.needsHumanReview(p))
                .sorted(BY_RECENCY_THEN_COMPANY)
                .toList();
        List<Posting> newCandidates = dedupe(newCandidatesRaw);

        // The same strategy predicate as the candidates. Without it this list
        // printed every eligible onsite role in a country the strategy excludes
        // or has no policy for - New York, San Francisco, Milan, Tokyo - which on
        // 2026-09-15 was most of its sixty rows.
        List<Posting> reviewRaw = all.stream()
                .filter(fresh)
                .filter(candidate)
                .filter(ScreeningService::needsHumanReview)
                .filter(notYetApplied)
                .filter(stale.negate())
                .sorted(BY_RECENCY_THEN_COMPANY)
                .toList();
        List<Posting> review = dedupe(reviewRaw);

        List<Posting> updatedRaw = all.stream()
                .filter(p -> p.getStatus() == PostingStatus.UPDATED)
                .filter(candidate)
                .filter(notYetApplied)
                .filter(stale.negate())
                .filter(p -> !ScreeningService.needsHumanReview(p))
                .sorted(BY_RECENCY_THEN_COMPANY)
                .toList();
        List<Posting> updated = dedupe(updatedRaw);

        List<Posting> closed = all.stream()
                .filter(p -> p.getStatus() == PostingStatus.CLOSED)
                .filter(candidate)
                .sorted(BY_RECENCY_THEN_COMPANY)
                .toList();

        Map<String, Long> rejections = all.stream()
                .filter(fresh)
                .filter(p -> p.getVerdict() == Verdict.REJECTED)
                .collect(Collectors.groupingBy(
                        DigestService::category, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        int collapsed = (newCandidatesRaw.size() - newCandidates.size())
                + (reviewRaw.size() - review.size())
                + (updatedRaw.size() - updated.size());

        int staleSetAside = (int) all.stream()
                .filter(fresh).filter(candidate).filter(notYetApplied).filter(stale)
                .map(p -> p.getBoardToken() + " " + p.getTitle())
                .distinct()
                .count();

        return new Digest(date, newCandidates, review, updated, closed, rejections,
                activeBoards,
                floors != null && floors.needsReverification(date),
                (int) suppressed,
                collapsed,
                startHere(newCandidates, review, updated),
                staleSetAside);
    }

    /**
     * The strongest few across all three lists, by match score; recency breaks ties.
     *
     * <p>Every list below this is dated, not ranked, so on a day with sixty rows the
     * best role could sit ninth in one of them. The scorer is the one the jobs page
     * already uses, so the digest and the page cannot disagree about what is strong.
     */
    private List<Digest.Pick> startHere(List<Posting> newCandidates, List<Posting> review,
            List<Posting> updated) {
        if (scorer == null) {
            return List.of();
        }
        Map<String, Posting> byRole = new LinkedHashMap<>();
        Stream.of(newCandidates, review, updated).flatMap(List::stream)
                .forEach(p -> byRole.putIfAbsent(p.getBoardToken() + " " + p.getTitle(), p));
        return byRole.values().stream()
                .map(p -> {
                    MatchScore score = scorer.score(p);
                    return new Digest.Pick(p, score.score(), score.band().label());
                })
                .sorted(Comparator.comparingInt(Digest.Pick::score).reversed()
                        .thenComparing(Digest.Pick::posting, BY_RECENCY_THEN_COMPANY))
                .limit(START_HERE)
                .toList();
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
     * <p>AWS advertises "Software Development Engineer, AWS Database Migration
     * Service" in Dublin three times over, under three requisition ids. They are
     * three vacancies and one application, so showing all three costs the reader
     * attention and returns nothing. Ten of the eighty-seven candidates in the
     * first real run were repeats of this kind.
     *
     * <p>Keyed on company and title rather than on the description, because the
     * point is what a human would apply to, not what an ATS considers distinct.
     * Input order is preserved, so the first listing of a role survives.
     */
    private static List<Posting> dedupe(List<Posting> postings) {
        Map<String, Posting> byRole = new LinkedHashMap<>();
        for (Posting posting : postings) {
            byRole.putIfAbsent(
                    posting.getBoardToken() + "\u0000" + posting.getTitle(), posting);
        }
        return List.copyOf(byRole.values());
    }
}
