package com.anuragbhandary.jobradar.filter;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the filters and records a verdict on every posting.
 *
 * <p>The filters run in order - geography, then title, then years - and the
 * first one to reject wins. That order is deliberate: geography and title are
 * cheap string checks over a few dozen characters, while the years extractor
 * scans a description that averages several kilobytes. Running the cheap ones
 * first means the expensive one only sees postings that could still qualify.
 *
 * <p>It also produces better reject reasons. A senior role in Buenos Aires is
 * more usefully reported as "country-locked remote" than as "5+ years", because
 * the geography is the reason no amount of experience would help.
 */
@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    private final PostingRepository postings;
    private final GeoFilter geoFilter;
    private final TitleFilter titleFilter;
    private final YearsExtractor yearsExtractor;
    private final AppProperties.Screening screening;

    public ScreeningService(
            PostingRepository postings,
            GeoFilter geoFilter,
            TitleFilter titleFilter,
            YearsExtractor yearsExtractor,
            AppProperties properties) {
        this.postings = postings;
        this.geoFilter = geoFilter;
        this.titleFilter = titleFilter;
        this.yearsExtractor = yearsExtractor;
        this.screening = properties.screening();
    }

    /**
     * Screens every posting in the database.
     *
     * <p>Everything, not only the unscreened ones. The rules live in
     * application.yml precisely so they can be edited, and an edited rule that
     * only applied to postings fetched afterwards would give a database whose
     * verdicts were decided by several different versions of the rules.
     */
    @Transactional
    public ScreenSummary screenAll() {
        List<Posting> all = postings.findAll();
        all.forEach(this::screen);
        postings.saveAll(all);

        Map<String, Long> byReason = all.stream()
                .filter(p -> p.getVerdict() == Verdict.REJECTED)
                .collect(Collectors.groupingBy(
                        p -> reasonCategory(p.getRejectReason()), Collectors.counting()));

        ScreenSummary summary = new ScreenSummary(
                all.size(),
                (int) all.stream().filter(p -> p.getVerdict() == Verdict.CANDIDATE).count(),
                (int) all.stream().filter(p -> p.getVerdict() == Verdict.REJECTED).count(),
                (int) all.stream().filter(ScreeningService::needsHumanReview).count(),
                (int) all.stream().filter(p -> p.getVerdict() == Verdict.CANDIDATE
                        && p.isGraduateSignal()).count(),
                byReason.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                (a, b) -> a, LinkedHashMap::new)));

        log.info("Screened {} postings: {} candidates, {} rejected, {} need human review",
                summary.screened(), summary.candidates(),
                summary.rejected(), summary.needsHumanReview());
        return summary;
    }

    /** A candidate whose description states no years requirement at all. */
    public static boolean needsHumanReview(Posting posting) {
        return posting.getVerdict() == Verdict.CANDIDATE
                && posting.getMinYears() != null
                && posting.getMinYears() == YearsExtraction.NONE_STATED;
    }

    /** Screens one posting in place. Previous verdicts are overwritten. */
    public void screen(Posting posting) {
        posting.setRejectReason(null);
        posting.setMinYears(null);
        posting.setGraduateSignal(false);

        // Board-level exclusion first: if the whole company is unavailable, the
        // geography and title of an individual posting are beside the point.
        String boardExclusion = screening.excludedBoards() == null
                ? null : screening.excludedBoards().get(posting.getBoardToken());
        if (boardExclusion != null) {
            posting.setCountry(geoFilter.classify(
                    posting.getLocation(), posting.getTitle()).country());
            reject(posting, "board excluded: " + boardExclusion);
            return;
        }

        GeoFilter.GeoResult geo = geoFilter.classify(posting.getLocation(), posting.getTitle());
        posting.setCountry(geo.country());
        if (!geo.verdict().accepted()) {
            reject(posting, geo.verdict().reason());
            return;
        }

        FilterVerdict title = titleFilter.screen(posting.getTitle());
        if (!title.accepted()) {
            reject(posting, title.reason());
            return;
        }

        posting.setGraduateSignal(
                titleFilter.hasGraduateSignal(posting.getTitle(), posting.getDescriptionText()));

        YearsExtraction years = yearsExtractor.extract(posting.getDescriptionText());
        posting.setMinYears(years.minYears());

        if (years.hasNonInternshipRequirement()) {
            // Amazon's phrasing excludes internship time explicitly, which is the
            // whole of the experience being screened for. Disqualifying on its
            // own, whatever the extracted minimum works out to.
            reject(posting, "non-internship experience required: \"" + years.nonInternship() + "\"");
            return;
        }

        if (!years.isNoneStated() && years.minYears() > screening.maxMinYears()) {
            reject(posting, years.minYears() + " years required: \"" + years.evidence() + "\"");
            return;
        }

        posting.setVerdict(Verdict.CANDIDATE);
    }

    private static void reject(Posting posting, String reason) {
        posting.setVerdict(Verdict.REJECTED);
        posting.setRejectReason(reason);
    }

    /**
     * Groups reasons for the summary. The reasons carry quoted text from the
     * posting so a human can argue with them, which makes every one unique - the
     * part before the quote is the part worth counting.
     */
    private static String reasonCategory(String reason) {
        if (reason == null) {
            return "unknown";
        }
        int cut = reason.indexOf(": ");
        String category = cut > 0 ? reason.substring(0, cut) : reason;
        // "5 years required" and "7 years required" are the same category.
        return category.replaceFirst("^\\d+ years", "N years");
    }
}
