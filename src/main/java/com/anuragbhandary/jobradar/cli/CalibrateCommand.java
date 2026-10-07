package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.digest.FitModel;
import com.anuragbhandary.jobradar.digest.FitScore;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.pipeline.JobInterest;
import com.anuragbhandary.jobradar.pipeline.PipelineStage;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code calibrate [--learn] [--force]} - what the review's decisions say about
 * the ranking, the filters and the sources.
 *
 * <p>Every review records a decision for every posting it showed, so the
 * decisions can be read back. Four reports:
 * <ol>
 *   <li>picks against fit score, source and lane, with each decided posting
 *       scored again today;</li>
 *   <li>the hand weights against learned ones, by out-of-sample AUC. With
 *       {@code --learn} the learned weights are saved when they do better (or
 *       always, with {@code --force}), and {@link FitScore} uses them from then on;</li>
 *   <li>title words common among roles dropped after reading and absent from
 *       picks, and the commonest skip reasons: candidates for a new filter rule,
 *       for a person to approve;</li>
 *   <li>once there are applications, what happened after applying, by source,
 *       lane and resume: picks measure the review's taste, replies measure the
 *       market.</li>
 * </ol>
 * Outcomes:
 * <ul>
 *   <li><b>picked</b>: shortlisted, or any stage after (an employer's rejection
 *       still means the review picked it);</li>
 *   <li><b>judged</b>: dropped after being read;</li>
 *   <li><b>unread</b>: dropped with the "below the fit cut" note. These say
 *       nothing about fit, only about the cut, so they are kept apart and never
 *       trained on.</li>
 * </ul>
 */
@Component
public class CalibrateCommand {

    /** The note the review writes when it skips the lower-fit lines unread. */
    static final String BULK_NOTE = "below the fit cut";

    /** Learned weights must beat the hand ones by this much AUC to be saved. */
    static final double MIN_IMPROVEMENT = 0.02;

    private static final int FOLDS = 5;

    private final EntityManager entities;
    private final PostingRepository postings;
    private final FitScore fitScore;
    private final AppProperties.Screening screening;

    public CalibrateCommand(EntityManager entities, PostingRepository postings, FitScore fitScore,
            AppProperties properties) {
        this.entities = entities;
        this.postings = postings;
        this.fitScore = fitScore;
        this.screening = properties.screening();
    }

    enum Outcome { PICKED, JUDGED, BULK }

    /**
     * One decided posting.
     *
     * @param fit      today's score, learned or hand, whichever is in use
     * @param hand     today's hand-weighted score
     * @param features the model's inputs
     * @param stage    where the application got to
     * @param title    the posting's title, for the word report
     * @param notes    the review's notes on it
     */
    record Decision(int fit, int hand, double[] features, int lanePoints, String source, String lane,
            Outcome outcome, PipelineStage stage, String title, String notes) {

        Decision(int fit, String source, String lane, Outcome outcome) {
            this(fit, fit, new double[0], 0, source, lane, outcome, null, "", "");
        }
    }

    /** Counts for one row of a table. */
    static final class Tally {
        int picked;
        int judged;
        int bulk;

        void add(Outcome outcome) {
            switch (outcome) {
                case PICKED -> picked++;
                case JUDGED -> judged++;
                case BULK -> bulk++;
            }
        }

        /** Picks over everything read: unread skips were never read. */
        String rate() {
            int read = picked + judged;
            return read == 0 ? "-" : Math.round(100.0 * picked / read) + "%";
        }
    }

    @Transactional(readOnly = true)
    public void run(Map<String, String> options) {
        List<JobInterest> interests = entities.createQuery(
                "select i from JobInterest i where i.postingId is not null", JobInterest.class)
                .getResultList();
        Map<Long, Posting> byId = new HashMap<>();
        postings.findAllById(interests.stream().map(JobInterest::getPostingId).toList())
                .forEach(p -> byId.put(p.getId(), p));
        LocalDate today = LocalDate.now();
        List<Decision> decisions = new ArrayList<>();
        for (JobInterest interest : interests) {
            Posting posting = byId.get(interest.getPostingId());
            if (posting == null) {
                continue;
            }
            decisions.add(new Decision(
                    fitScore.fit(posting, today).score(),
                    fitScore.handScore(posting, today),
                    fitScore.features(posting),
                    fitScore.lanePoints(posting),
                    String.valueOf(posting.getSource()),
                    posting.getStrategicClass() == null ? "UNKNOWN" : posting.getStrategicClass().name(),
                    outcome(interest), interest.getStage(),
                    posting.getTitle() == null ? "" : posting.getTitle(),
                    interest.getNotes() == null ? "" : interest.getNotes()));
        }
        System.out.print(report(decisions, fitScore.minFit(), fitScore.learned()));
        System.out.println();
        System.out.print(learning(decisions, options.containsKey("learn"), options.containsKey("force")));
        System.out.println();
        System.out.print(suggestions(decisions, excludedWords()));
        String outcomes = outcomes(decisions);
        if (!outcomes.isEmpty()) {
            System.out.println();
            System.out.print(outcomes);
        }
    }

    static Outcome outcome(JobInterest interest) {
        if (interest.getStage() != PipelineStage.DROPPED) {
            return Outcome.PICKED;
        }
        String notes = interest.getNotes() == null ? "" : interest.getNotes().toLowerCase(Locale.ROOT);
        return notes.contains(BULK_NOTE) ? Outcome.BULK : Outcome.JUDGED;
    }

    // ------------------------------------------------------------------
    // 1. Picks by score, source and lane
    // ------------------------------------------------------------------

    /** The first report as text, so a test can read it. */
    static String report(List<Decision> decisions, int minFit, boolean learned) {
        StringBuilder out = new StringBuilder();
        long picked = decisions.stream().filter(d -> d.outcome() == Outcome.PICKED).count();
        out.append("%d decisions: %d picked, %d dropped after reading, %d dropped unread below the cut%n"
                .formatted(decisions.size(), picked,
                        decisions.stream().filter(d -> d.outcome() == Outcome.JUDGED).count(),
                        decisions.stream().filter(d -> d.outcome() == Outcome.BULK).count()));
        out.append("Fit is today's score (").append(learned ? "learned" : "hand").append(" weights); ")
                .append("the cut is min-fit ").append(minFit).append(".\n\n");

        table(out, "fit", decisions, d -> band(d.fit()), Comparator.reverseOrder());
        table(out, "source", decisions, Decision::source, Comparator.naturalOrder());
        table(out, "lane", decisions, Decision::lane, Comparator.naturalOrder());

        decisions.stream().filter(d -> d.outcome() == Outcome.PICKED)
                .mapToInt(Decision::fit).min()
                .ifPresent(lowest -> {
                    out.append("Lowest-scoring pick: ").append(lowest).append(". ");
                    long unreadAbove = decisions.stream()
                            .filter(d -> d.outcome() == Outcome.BULK && d.fit() >= lowest).count();
                    out.append(unreadAbove).append(" unread skips scored at least that.\n");
                });
        return out.toString();
    }

    private static void table(StringBuilder out, String heading, List<Decision> decisions,
            Function<Decision, String> key, Comparator<String> order) {
        Map<String, Tally> rows = new TreeMap<>(order);
        for (Decision d : decisions) {
            rows.computeIfAbsent(key.apply(d), k -> new Tally()).add(d.outcome());
        }
        out.append("%-24s %7s %7s %7s %6s%n".formatted(heading, "picked", "judged", "unread", "rate"));
        rows.forEach((name, t) -> out.append("%-24s %7d %7d %7d %6s%n"
                .formatted(name, t.picked, t.judged, t.bulk, t.rate())));
        out.append('\n');
    }

    /** "60-69", with 90-100 as one band. */
    static String band(int fit) {
        int low = Math.min(90, fit / 10 * 10);
        return low == 90 ? "90-100" : "%02d-%02d".formatted(low, low + 9);
    }

    // ------------------------------------------------------------------
    // 2. Hand weights against learned ones
    // ------------------------------------------------------------------

    private String learning(List<Decision> decisions, boolean save, boolean force) {
        List<Decision> read = decisions.stream().filter(d -> d.outcome() != Outcome.BULK).toList();
        StringBuilder out = new StringBuilder("Learned weights\n");
        long picks = read.stream().filter(d -> d.outcome() == Outcome.PICKED).count();
        if (picks < 10 || read.size() - picks < 10) {
            return out.append("Too few decisions to learn from (").append(picks)
                    .append(" picks).\n").toString();
        }
        List<double[]> xs = read.stream().map(Decision::features).toList();
        List<Boolean> ys = read.stream().map(d -> d.outcome() == Outcome.PICKED).toList();
        boolean[] labels = new boolean[ys.size()];
        double[] hand = new double[ys.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = ys.get(i);
            hand[i] = read.get(i).hand();
        }
        double handAuc = FitModel.auc(hand, labels);
        // The score as it would be used: the out-of-fold chance plus the lane bonus.
        double[] probabilities = FitModel.crossValidatedProbabilities(xs, ys, FOLDS);
        double[] learned = new double[probabilities.length];
        for (int i = 0; i < learned.length; i++) {
            learned[i] = FitScore.learnedScore(probabilities[i], read.get(i).lanePoints());
        }
        double learnedAuc = FitModel.auc(learned, labels);
        out.append("AUC on %d read decisions (0.5 is a coin, 1.0 perfect): hand %.3f, learned %.3f (%d-fold, out of sample)%n"
                .formatted(read.size(), handAuc, learnedAuc, FOLDS));

        FitModel model = FitModel.train(xs, ys);
        out.append("Weights trained on all of them, largest effect first:\n");
        model.describe().forEach(line -> out.append("  ").append(line).append('\n'));

        boolean better = learnedAuc >= handAuc + MIN_IMPROVEMENT;
        Path path = fitScore.modelPath();
        if (!save) {
            out.append(better ? "Learned beats hand; save with: calibrate --learn\n"
                    : "Learned does not beat hand by " + MIN_IMPROVEMENT + "; keeping the hand weights.\n");
        } else if (!better && !force) {
            out.append("Not saved: learned does not beat hand by ").append(MIN_IMPROVEMENT)
                    .append(" (--force saves anyway).\n");
        } else if (path == null) {
            out.append("Not saved: ranking.model-path is not set.\n");
        } else {
            try {
                model.save(path, "calibrate --learn on %s: %d decisions, %d picks, AUC hand %.3f learned %.3f"
                        .formatted(LocalDate.now(), read.size(), picks, handAuc, learnedAuc));
                out.append("Saved to ").append(path).append("; the next openings file uses it.\n");
            } catch (IOException e) {
                out.append("Could not save to ").append(path).append(": ").append(e.getMessage()).append('\n');
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // 3. Words and reasons behind the skips
    // ------------------------------------------------------------------

    private static final Set<String> STOPWORDS = Set.of(
            "and", "the", "for", "with", "of", "in", "to", "at", "on", "an", "a", "or",
            "engineer", "software", "developer", "data", "analyst", "india", "remote",
            // Markup and boilerplate in Hacker News headers and German titles.
            "https", "http", "www", "com", "all", "genders", "gender");

    /** "reviewed 2026-10-07: needs 3+ years" - the reason, without the date. */
    private static final Pattern REASON = Pattern.compile(
            "reviewed\\s+\\d{4}-\\d{2}-\\d{2}\\s*:\\s*([^;\\n]+)", Pattern.CASE_INSENSITIVE);

    private Set<String> excludedWords() {
        Set<String> words = new HashSet<>();
        if (screening.titleExclude() != null) {
            screening.titleExclude().forEach(w -> words.add(w.strip().toLowerCase(Locale.ROOT)));
        }
        if (screening.domainExclude() != null) {
            screening.domainExclude().forEach(w -> words.add(w.strip().toLowerCase(Locale.ROOT)));
        }
        return words;
    }

    /**
     * Title words in at least six roles dropped after reading and in no pick, not
     * already excluded; then the commonest skip reasons. Suggestions only: a word
     * is a rule once a person has looked at the roles it would drop.
     */
    static String suggestions(List<Decision> decisions, Set<String> alreadyExcluded) {
        Map<String, Integer> judged = new HashMap<>();
        Set<String> inPicks = new HashSet<>();
        for (Decision d : decisions) {
            Set<String> words = titleWords(d.title());
            if (d.outcome() == Outcome.PICKED) {
                inPicks.addAll(words);
            } else if (d.outcome() == Outcome.JUDGED) {
                words.forEach(w -> judged.merge(w, 1, Integer::sum));
            }
        }
        StringBuilder out = new StringBuilder("Possible filter words (in 6+ roles dropped after reading, in no pick)\n");
        List<Map.Entry<String, Integer>> words = judged.entrySet().stream()
                .filter(e -> e.getValue() >= 6 && !inPicks.contains(e.getKey())
                        && !alreadyExcluded.contains(e.getKey()))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(20)
                .toList();
        if (words.isEmpty()) {
            out.append("  none\n");
        }
        words.forEach(e -> out.append("  %-24s %d%n".formatted(e.getKey(), e.getValue())));

        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (Decision d : decisions) {
            if (d.outcome() != Outcome.JUDGED) {
                continue;
            }
            Matcher m = REASON.matcher(d.notes());
            while (m.find()) {
                String reason = m.group(1).strip().toLowerCase(Locale.ROOT)
                        .replaceAll("\\s*[(:].*$", "");
                if (!reason.isEmpty()) {
                    reasons.merge(reason, 1, Integer::sum);
                }
            }
        }
        out.append("\nCommonest reasons for dropping a role after reading\n");
        reasons.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(12)
                .forEach(e -> out.append("  %4d  %s%n".formatted(e.getValue(), e.getKey())));
        return out.toString();
    }

    static Set<String> titleWords(String title) {
        Set<String> words = new HashSet<>();
        for (String w : title.toLowerCase(Locale.ROOT).split("[^\\p{L}+#]+")) {
            if (w.length() >= 3 && !STOPWORDS.contains(w)) {
                words.add(w);
            }
        }
        return words;
    }

    // ------------------------------------------------------------------
    // 4. After applying
    // ------------------------------------------------------------------

    private static boolean applied(PipelineStage stage) {
        return stage != null && switch (stage) {
            case APPLIED, SCREENING, INTERVIEW, OFFER, REJECTED -> true;
            default -> false;
        };
    }

    /** Which resume the pick named in its note, as the review writes it. */
    static String resume(String notes) {
        String n = notes.toLowerCase(Locale.ROOT);
        if (n.contains("data resume")) {
            return "data resume";
        }
        if (n.contains("software resume")) {
            return "software resume";
        }
        return "resume not noted";
    }

    /** Empty until something has been applied to. */
    static String outcomes(List<Decision> decisions) {
        List<Decision> sent = decisions.stream().filter(d -> applied(d.stage())).toList();
        if (sent.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("After applying (%d applications)%n".formatted(sent.size()));
        outcomeTable(out, "source", sent, Decision::source);
        outcomeTable(out, "lane", sent, Decision::lane);
        outcomeTable(out, "resume", sent, d -> resume(d.notes()));
        return out.toString();
    }

    private static void outcomeTable(StringBuilder out, String heading, List<Decision> sent,
            Function<Decision, String> key) {
        Map<String, int[]> rows = new TreeMap<>();
        for (Decision d : sent) {
            int[] c = rows.computeIfAbsent(key.apply(d), k -> new int[4]);
            c[0]++;
            switch (d.stage()) {
                case SCREENING, INTERVIEW, OFFER -> c[1]++;
                case REJECTED -> c[2]++;
                default -> c[3]++;
            }
        }
        out.append("%-24s %7s %9s %8s %7s%n".formatted(heading, "applied", "heard yes", "rejected", "waiting"));
        rows.forEach((name, c) -> out.append("%-24s %7d %9d %8d %7d%n".formatted(name, c[0], c[1], c[2], c[3])));
        out.append('\n');
    }
}
