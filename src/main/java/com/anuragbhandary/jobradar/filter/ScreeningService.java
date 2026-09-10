package com.anuragbhandary.jobradar.filter;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.anuragbhandary.jobradar.strategy.CountryStrategy;
import com.anuragbhandary.jobradar.strategy.StrategyOutcome;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Classifies every posting, then decides which of them are eligible.
 *
 * <p>Those are two jobs, and this class now does them in that order. That is the
 * change this phase is about. Before it, geography was a filter: anything outside
 * four countries became {@code REJECTED - outside target geographies} with no
 * country recorded, so 5,336 rows said nothing about where their job was, and
 * 1,042 of them turned out to be in countries the strategy later wanted. Getting
 * those back meant editing a hundred-and-fifty-entry list of place names.
 *
 * <p>Now every posting is classified - country, work mode, remote eligibility,
 * employer country, strategic lane - and only three things still reject:
 *
 * <ol>
 *   <li>the board is excluded outright;</li>
 *   <li>the work cannot be done from India and cannot be moved to: remote locked
 *       to a country or a region the applicant cannot be in. The expensive
 *       mistake the original filter existed to prevent, preserved exactly;</li>
 *   <li>the title or the years requirement rules it out.</li>
 * </ol>
 *
 * <p>Everything else is eligible. Whether it is <em>recommended</em> is a
 * separate field - {@link StrategyOutcome} - which changes when the configuration
 * changes and never deletes a row.
 *
 * <p>The filters still run cheap-to-expensive, and for the same reason: location
 * and title are string checks over a few dozen characters, while the years
 * extractor scans a description that averages several kilobytes.
 */
@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    private final PostingRepository postings;
    private final BoardTokenRepository boards;
    private final LocationClassifier locations;
    private final StrategicClassifier lanes;
    private final CountryStrategy strategy;
    private final TitleFilter titleFilter;
    private final YearsExtractor yearsExtractor;
    private final SignalExtractor signalExtractor;
    private final AppProperties.Screening screening;

    public ScreeningService(
            PostingRepository postings,
            BoardTokenRepository boards,
            LocationClassifier locations,
            StrategicClassifier lanes,
            CountryStrategy strategy,
            TitleFilter titleFilter,
            YearsExtractor yearsExtractor,
            SignalExtractor signalExtractor,
            AppProperties properties) {
        this.postings = postings;
        this.boards = boards;
        this.locations = locations;
        this.lanes = lanes;
        this.strategy = strategy;
        this.titleFilter = titleFilter;
        this.yearsExtractor = yearsExtractor;
        this.signalExtractor = signalExtractor;
        this.screening = properties.screening();
    }

    /**
     * Screens every posting in the database.
     *
     * <p>Everything, not only the unscreened ones. The rules live in
     * application.yml precisely so they can be edited, and an edited rule that
     * only applied to postings fetched afterwards would give a database whose
     * verdicts were decided by several different versions of the rules.
     *
     * <p>It is also the migration. Country code, work mode and strategic class
     * are all derived from the posting's own location text, so running
     * {@code screen} is what fills the new columns for the nine thousand rows
     * fetched before those columns existed. No separate backfill, and no mapping
     * table that could disagree with the classifier.
     */
    @Transactional
    public ScreenSummary screenAll() {
        List<Posting> all = postings.findAll();

        // Classified once, up front: the employer-country inference is a
        // board-level aggregate over these same results, and classifying twice
        // would double the cost of the only expensive step in the pass.
        Map<Posting, LocationProfile> profiles = new IdentityHashMap<>(all.size());
        for (Posting posting : all) {
            profiles.put(posting, locations.classify(
                    posting.getLocation(), posting.getTitle(), posting.getDescriptionText()));
        }
        Map<String, String> employers = inferEmployerCountries(all, profiles);

        for (Posting posting : all) {
            screen(posting, profiles.get(posting), employers.get(boardKey(posting)));
        }
        postings.saveAll(all);

        ScreenSummary summary = summarise(all);
        log.info("Screened {} postings: {} eligible ({} recommended), {} rejected, "
                        + "{} need human review",
                summary.screened(), summary.candidates(), summary.recommended(),
                summary.rejected(), summary.needsHumanReview());
        return summary;
    }

    /** Screens one posting in place. Previous verdicts are overwritten. */
    public void screen(Posting posting) {
        screen(posting,
                locations.classify(posting.getLocation(), posting.getTitle(),
                        posting.getDescriptionText()),
                employerCountryOf(posting));
    }

    private void screen(Posting posting, LocationProfile location, String employerCountry) {
        posting.setRejectReason(null);
        posting.setMinYears(null);
        posting.setGraduateSignal(false);
        posting.setSponsorshipSignal(null);
        posting.setSalaryText(null);

        // Classification first, and unconditionally. A rejected posting still has
        // a country and a lane: "rejected" is a statement about eligibility, not
        // a reason to know nothing about the row.
        applyLocation(posting, location, employerCountry);

        // Board-level exclusion: if the whole company is unavailable, the
        // geography and title of an individual posting are beside the point.
        String boardExclusion = screening.excludedBoards() == null
                ? null : screening.excludedBoards().get(posting.getBoardToken());
        if (boardExclusion != null) {
            reject(posting, "board excluded: " + boardExclusion);
            return;
        }

        if (!location.verdict().accepted()) {
            reject(posting, location.verdict().reason());
            return;
        }

        FilterVerdict title = titleFilter.screen(posting.getTitle());
        if (!title.accepted()) {
            reject(posting, title.reason());
            return;
        }

        posting.setGraduateSignal(
                titleFilter.hasGraduateSignal(posting.getTitle(), posting.getDescriptionText()));

        // Reported, never decisive. Recorded here rather than at digest time so
        // the description is read once per screen instead of once per render.
        posting.setSponsorshipSignal(
                signalExtractor.sponsorship(posting.getDescriptionText()));
        posting.setSalaryText(signalExtractor.salary(posting.getDescriptionText()));

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

    /**
     * Writes the classification onto the posting, legacy column included.
     *
     * <p>{@code country} is still populated, from the code and the lane. It is
     * what {@code FieldMapper}'s sponsorship and authorisation derivations read,
     * along with the compensation bands, the salary floors, the match scorer and
     * the jobs-page filter - and this phase is explicitly not the one that
     * changes what gets typed into an application form.
     */
    private void applyLocation(Posting posting, LocationProfile location, String employerCountry) {
        posting.setCountryCode(location.countryCode());
        posting.setEmployerCountryCode(CountryCodes.normalise(employerCountry));
        posting.setWorkMode(location.workMode());
        posting.setRemoteEligibleFrom(location.remoteEligibleFromCsv());

        StrategicClass lane = lanes.classify(location, posting.getEmployerCountryCode());
        posting.setStrategicClass(lane);
        posting.setStrategyOutcome(strategy.outcomeFor(
                lane, location.countryCode(), posting.getEmployerCountryCode()));
        posting.setCountry(CountryCodes.toLegacy(location.countryCode(), lane));
    }

    // ------------------------------------------------------------------
    // Employer country
    // ------------------------------------------------------------------

    /**
     * Where each board's company is, inferred from where it puts its desks.
     *
     * <p>A board's onsite postings are the best free evidence of its employer's
     * country: a company with twelve vacancies in Berlin and three remote ones is
     * German. Remote postings are excluded from the vote precisely because they
     * are the ones being classified - counting them would let a "Remote, Global"
     * board vote for whatever country the classifier happened to read out of it.
     *
     * <p>An {@code employerCountryCode} already set on the {@link BoardToken} wins
     * outright and is never overwritten, so a company the inference gets wrong is
     * corrected once by hand rather than argued with on every run.
     */
    private Map<String, String> inferEmployerCountries(
            List<Posting> all, Map<Posting, LocationProfile> profiles) {

        Map<String, Map<String, Integer>> votes = new HashMap<>();
        for (Posting posting : all) {
            LocationProfile profile = profiles.get(posting);
            if (profile == null || profile.countryCode() == null
                    || !profile.workMode().requiresPresence()) {
                continue;
            }
            votes.computeIfAbsent(boardKey(posting), key -> new HashMap<>())
                    .merge(profile.countryCode(), 1, Integer::sum);
        }

        Map<String, String> inferred = new HashMap<>();
        votes.forEach((board, counts) -> inferred.put(board, counts.entrySet().stream()
                // Ties break alphabetically so the answer does not depend on hash
                // order. A board flipping country between runs would silently move
                // every one of its remote postings between strategic lanes.
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey)
                .orElse(null)));

        if (boards != null) {
            for (BoardToken board : boards.findAll()) {
                String code = CountryCodes.normalise(board.getEmployerCountryCode());
                if (code != null) {
                    inferred.put(board.getSource() + "/" + board.getToken(), code);
                }
            }
        }
        return inferred;
    }

    /** The employer country for a single posting, when no board pass has run. */
    private String employerCountryOf(Posting posting) {
        if (boards == null) {
            return null;
        }
        return boards.findBySourceAndToken(posting.getSource(), posting.getBoardToken())
                .map(BoardToken::getEmployerCountryCode)
                .orElse(null);
    }

    private static String boardKey(Posting posting) {
        return posting.getSource() + "/" + posting.getBoardToken();
    }

    // ------------------------------------------------------------------
    // Summary
    // ------------------------------------------------------------------

    /** A candidate whose description states no years requirement at all. */
    public static boolean needsHumanReview(Posting posting) {
        return posting.getVerdict() == Verdict.CANDIDATE
                && posting.getMinYears() != null
                && posting.getMinYears() == YearsExtraction.NONE_STATED;
    }

    private ScreenSummary summarise(List<Posting> all) {
        Map<String, Long> byReason = all.stream()
                .filter(p -> p.getVerdict() == Verdict.REJECTED)
                .collect(Collectors.groupingBy(
                        p -> reasonCategory(p.getRejectReason()), Collectors.counting()));

        List<Posting> candidates = all.stream()
                .filter(p -> p.getVerdict() == Verdict.CANDIDATE)
                .toList();

        return new ScreenSummary(
                all.size(),
                candidates.size(),
                (int) all.stream().filter(p -> p.getVerdict() == Verdict.REJECTED).count(),
                (int) all.stream().filter(ScreeningService::needsHumanReview).count(),
                (int) all.stream().filter(p -> p.getVerdict() == Verdict.CANDIDATE
                        && p.isGraduateSignal()).count(),
                sortedByCount(byReason),
                (int) candidates.stream()
                        .filter(p -> p.getStrategyOutcome() == StrategyOutcome.RECOMMENDED)
                        .count(),
                countBy(candidates, p -> name(p.getStrategicClass())),
                countBy(candidates, p -> name(p.getWorkMode())),
                countBy(candidates, p -> p.getCountryCode() == null ? "??" : p.getCountryCode()),
                countBy(candidates, p -> name(p.getStrategyOutcome())));
    }

    private static String name(Enum<?> value) {
        return value == null ? "UNKNOWN" : value.name();
    }

    private static Map<String, Long> countBy(
            List<Posting> rows, Function<Posting, String> key) {
        return sortedByCount(rows.stream()
                .collect(Collectors.groupingBy(key, Collectors.counting())));
    }

    private static Map<String, Long> sortedByCount(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
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
