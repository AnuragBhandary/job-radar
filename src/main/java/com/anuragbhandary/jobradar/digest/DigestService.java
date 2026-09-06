package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.filter.ScreeningService;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.anuragbhandary.jobradar.sheets.SheetsClient;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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

    public DigestService(
            PostingRepository postings,
            BoardTokenRepository boards,
            SheetsClient sheets,
            AppProperties properties) {
        this.postings = postings;
        this.boards = boards;
        this.sheets = sheets;
        this.floors = properties.salaryFloors();
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
        Predicate<Posting> candidate = p -> p.getVerdict() == Verdict.CANDIDATE;

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
                .filter(p -> !ScreeningService.needsHumanReview(p))
                .sorted(BY_RECENCY_THEN_COMPANY)
                .toList();
        List<Posting> newCandidates = dedupe(newCandidatesRaw);

        List<Posting> reviewRaw = all.stream()
                .filter(fresh)
                .filter(ScreeningService::needsHumanReview)
                .filter(notYetApplied)
                .sorted(BY_RECENCY_THEN_COMPANY)
                .toList();
        List<Posting> review = dedupe(reviewRaw);

        List<Posting> updatedRaw = all.stream()
                .filter(p -> p.getStatus() == PostingStatus.UPDATED)
                .filter(candidate)
                .filter(notYetApplied)
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

        return new Digest(date, newCandidates, review, updated, closed, rejections,
                activeBoards,
                floors != null && floors.needsReverification(date),
                (int) suppressed,
                collapsed);
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
