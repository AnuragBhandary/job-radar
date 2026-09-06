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

    private static final Comparator<Posting> BY_COMPANY_THEN_TITLE =
            Comparator.comparing(Posting::getBoardToken).thenComparing(Posting::getTitle);

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
        List<Posting> all = postings.findAll();
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

        List<Posting> newCandidates = all.stream()
                .filter(p -> p.getStatus() == PostingStatus.NEW)
                .filter(candidate)
                .filter(notYetApplied)
                .filter(p -> !ScreeningService.needsHumanReview(p))
                .sorted(BY_COMPANY_THEN_TITLE)
                .toList();

        List<Posting> review = all.stream()
                .filter(fresh)
                .filter(ScreeningService::needsHumanReview)
                .filter(notYetApplied)
                .sorted(BY_COMPANY_THEN_TITLE)
                .toList();

        List<Posting> updated = all.stream()
                .filter(p -> p.getStatus() == PostingStatus.UPDATED)
                .filter(candidate)
                .filter(notYetApplied)
                .filter(p -> !ScreeningService.needsHumanReview(p))
                .sorted(BY_COMPANY_THEN_TITLE)
                .toList();

        List<Posting> closed = all.stream()
                .filter(p -> p.getStatus() == PostingStatus.CLOSED)
                .filter(candidate)
                .sorted(BY_COMPANY_THEN_TITLE)
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

        return new Digest(date, newCandidates, review, updated, closed, rejections,
                activeBoards,
                floors != null && floors.needsReverification(date),
                (int) suppressed);
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
}
