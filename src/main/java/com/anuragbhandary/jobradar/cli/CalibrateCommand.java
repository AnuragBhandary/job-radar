package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.digest.FitScore;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.pipeline.JobInterest;
import com.anuragbhandary.jobradar.pipeline.PipelineStage;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code calibrate} - which fit scores, sources and lanes the review actually
 * picks from.
 *
 * <p>The fit cut ({@code ranking.min-fit}) was set by eye on 2026-10-02. Every
 * review since has recorded a decision for every posting it showed, so the
 * decisions themselves can say where the cut belongs. Each decided posting is
 * scored again with today's {@link FitScore} and counted three ways:
 * <ul>
 *   <li><b>picked</b>: shortlisted, or any stage after (an employer's rejection
 *       still means the review picked it);</li>
 *   <li><b>judged</b>: dropped after being read;</li>
 *   <li><b>bulk</b>: dropped unread, with the "below the fit cut" note. These
 *       say nothing about fit, only about the cut, so they are shown apart.</li>
 * </ul>
 */
@Component
public class CalibrateCommand {

    /** The note the review writes when it skips the lower-fit lines unread. */
    static final String BULK_NOTE = "below the fit cut";

    private final EntityManager entities;
    private final PostingRepository postings;
    private final FitScore fitScore;

    public CalibrateCommand(EntityManager entities, PostingRepository postings, FitScore fitScore) {
        this.entities = entities;
        this.postings = postings;
        this.fitScore = fitScore;
    }

    enum Outcome { PICKED, JUDGED, BULK }

    /** One decided posting, scored today. */
    record Decision(int fit, String source, String lane, Outcome outcome) {
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

        /** Picks over everything read: bulk skips were never read. */
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
        LocalDate today = LocalDate.now();
        List<Decision> decisions = new ArrayList<>();
        for (JobInterest interest : interests) {
            Posting posting = postings.findById(interest.getPostingId()).orElse(null);
            if (posting == null) {
                continue;
            }
            decisions.add(new Decision(
                    fitScore.fit(posting, today).score(),
                    String.valueOf(posting.getSource()),
                    posting.getStrategicClass() == null ? "UNKNOWN" : posting.getStrategicClass().name(),
                    outcome(interest)));
        }
        System.out.print(report(decisions, fitScore.minFit()));
    }

    static Outcome outcome(JobInterest interest) {
        if (interest.getStage() != PipelineStage.DROPPED) {
            return Outcome.PICKED;
        }
        String notes = interest.getNotes() == null ? "" : interest.getNotes().toLowerCase(Locale.ROOT);
        return notes.contains(BULK_NOTE) ? Outcome.BULK : Outcome.JUDGED;
    }

    /** The whole report as text, so a test can read it. */
    static String report(List<Decision> decisions, int minFit) {
        StringBuilder out = new StringBuilder();
        long picked = decisions.stream().filter(d -> d.outcome() == Outcome.PICKED).count();
        out.append("%d decisions: %d picked, %d dropped after reading, %d dropped unread below the cut%n"
                .formatted(decisions.size(), picked,
                        decisions.stream().filter(d -> d.outcome() == Outcome.JUDGED).count(),
                        decisions.stream().filter(d -> d.outcome() == Outcome.BULK).count()));
        out.append("Fit is today's score; the cut is min-fit ").append(minFit).append(".\n\n");

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
}
