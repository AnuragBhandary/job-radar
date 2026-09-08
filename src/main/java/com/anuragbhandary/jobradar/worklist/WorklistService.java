package com.anuragbhandary.jobradar.worklist;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.AttemptStatus;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.match.MatchScorer;
import com.anuragbhandary.jobradar.pipeline.JobInterest;
import com.anuragbhandary.jobradar.pipeline.JobInterestRepository;
import com.anuragbhandary.jobradar.pipeline.PipelineStage;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Builds the day's worklist out of things the database already knew.
 *
 * <p>Everything here is a read. Nothing is invented, nothing is fetched, and no
 * number is rounded up to look better: the diagnosis names the narrowest point in
 * the funnel even when that is unflattering, because a dashboard that always says
 * things are going well is a dashboard nobody checks twice.
 */
@Service
public class WorklistService {

    /**
     * Days of silence before an application is worth chasing.
     *
     * <p>Two weeks. Shorter reads as impatience to a recruiter mid-process;
     * much longer and the role is filled before the nudge lands.
     */
    public static final int QUIET_AFTER_DAYS = 14;

    /** What counts as "this week" for the fresh list. */
    private static final int FRESH_DAYS = 7;

    private final PostingRepository postings;
    private final ApplicationAttemptRepository attempts;
    private final JobInterestRepository interests;
    private final BoardTokenRepository boards;
    private final MatchScorer scorer;

    public WorklistService(PostingRepository postings, ApplicationAttemptRepository attempts,
            JobInterestRepository interests, BoardTokenRepository boards, MatchScorer scorer) {
        this.postings = postings;
        this.attempts = attempts;
        this.interests = interests;
        this.boards = boards;
        this.scorer = scorer;
    }

    public Worklist build(LocalDate today) {
        List<JobInterest> board = interests.findAll();
        List<Posting> candidates = postings.findByVerdict(Verdict.CANDIDATE);

        List<Worklist.Task> tasks = new ArrayList<>();
        tasks.addAll(blocked());
        tasks.addAll(unsent());
        tasks.addAll(reminders(board, today));
        tasks.addAll(quiet(board, today));
        tasks.addAll(brokenBoards());
        tasks.sort(Comparator.comparingInt(Worklist.Task::urgency).reversed());

        return new Worklist(tasks, funnel(board, candidates, today), fresh(candidates, board, today));
    }

    // ------------------------------------------------------------------
    // Tasks
    // ------------------------------------------------------------------

    /**
     * Forms that stopped on a question with no answer.
     *
     * <p>The most valuable task on the list and the least obvious. Answering one
     * blocked question does not just unblock that application: the answer goes
     * into the profile and every future form matching that wording fills itself.
     */
    private List<Worklist.Task> blocked() {
        return attempts.findByStatusOrderByStartedAtDesc(AttemptStatus.NEEDS_HUMAN).stream()
                .map(attempt -> {
                    boolean noForm = attempt.getBlockerReason() != null
                            && attempt.getBlockerReason().startsWith("No form found");
                    return new Worklist.Task(
                            noForm ? Worklist.Kind.NO_FORM : Worklist.Kind.BLOCKED,
                            attempt.getCompany(),
                            noForm
                                    ? "The board never showed a form. Your tailored resume is "
                                            + "ready, so this is a five-minute job by hand."
                                    : reason(attempt),
                            "/attempt/" + attempt.getId(),
                            noForm ? "Open it" : "Answer it",
                            // An answerable question outranks a manual application:
                            // answering it also unblocks every future form that
                            // asks the same thing.
                            noForm ? 85 : 100);
                })
                .toList();
    }

    /** Filled and never sent. The work is already paid for. */
    private List<Worklist.Task> unsent() {
        return attempts.findByStatusOrderByStartedAtDesc(AttemptStatus.PREPARED).stream()
                .map(attempt -> new Worklist.Task(
                        Worklist.Kind.UNSENT,
                        attempt.getCompany(),
                        attempt.getRole() == null ? "Filled, waiting to be sent"
                                : attempt.getRole(),
                        "/attempt/" + attempt.getId(),
                        "Read and send",
                        90))
                .toList();
    }

    private List<Worklist.Task> reminders(List<JobInterest> board, LocalDate today) {
        return board.stream()
                .filter(i -> i.getRemindOn() != null && !i.getRemindOn().isAfter(today))
                .map(i -> new Worklist.Task(
                        Worklist.Kind.REMINDER,
                        i.getCompany(),
                        i.getNotes() == null || i.getNotes().isBlank()
                                ? "Due " + i.getRemindOn() : i.getNotes(),
                        "/board",
                        "Open the board",
                        80))
                .toList();
    }

    /**
     * Sent, and silent since.
     *
     * <p>Only stages that are genuinely waiting on someone else. A card in
     * Screening or Interview is mid-process and its silence means something
     * different from an application nobody acknowledged.
     */
    private List<Worklist.Task> quiet(List<JobInterest> board, LocalDate today) {
        return board.stream()
                .filter(i -> i.getStage() == PipelineStage.APPLIED)
                .map(i -> new Object() {
                    final JobInterest interest = i;
                    final long days = i.daysSinceApplied(today).orElse(-1L);
                })
                .filter(row -> row.days >= QUIET_AFTER_DAYS)
                .map(row -> new Worklist.Task(
                        Worklist.Kind.QUIET,
                        row.interest.getCompany(),
                        "Silent for " + row.days + " days since you applied",
                        "/board",
                        "Chase or drop",
                        // Longer silences sort above shorter ones, and all of them
                        // below anything that needs a decision today.
                        (int) Math.min(70, 40 + row.days / 7)))
                .toList();
    }

    /**
     * Boards that have stopped answering.
     *
     * <p>On the list because a broken board is invisible by nature: it does not
     * produce a bad posting, it produces no postings, and the feed looks normal.
     * Eight of these were failing silently.
     */
    private List<Worklist.Task> brokenBoards() {
        // Only boards that actually broke. Eight boards here carry a lastError
        // saying they were retired on purpose - mostly because the same company
        // is already covered on another ATS - and reporting those as failures is
        // a red banner about nothing, which teaches the reader to ignore red.
        List<com.anuragbhandary.jobradar.domain.BoardToken> broken =
                boards.findByLastErrorIsNotNull().stream()
                        .filter(com.anuragbhandary.jobradar.domain.BoardToken::isBroken)
                        .toList();
        if (broken.isEmpty()) {
            return List.of();
        }
        String names = broken.stream()
                .limit(4)
                .map(com.anuragbhandary.jobradar.domain.BoardToken::getToken)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return List.of(new Worklist.Task(
                Worklist.Kind.BROKEN_BOARD,
                broken.size() + (broken.size() == 1 ? " board is failing" : " boards are failing"),
                names + (broken.size() > 4 ? " and others" : "")
                        + ". Nothing from these reaches the feed.",
                "/setup",
                "See which",
                60));
    }

    private static String reason(ApplicationAttempt attempt) {
        String blocker = attempt.getBlockerReason();
        if (blocker == null || blocker.isBlank()) {
            return "Stopped on a question with no answer in your profile";
        }
        return blocker.length() > 110 ? blocker.substring(0, 109) + "…" : blocker;
    }

    // ------------------------------------------------------------------
    // Funnel and fresh
    // ------------------------------------------------------------------

    private Worklist.Funnel funnel(List<JobInterest> board, List<Posting> candidates,
            LocalDate today) {

        long applied = board.stream().filter(i -> i.getStage().isSent()).count();
        long answered = board.stream()
                .filter(i -> i.getStage() != PipelineStage.APPLIED)
                .filter(i -> i.getStage().isSent())
                .count();
        long interviewing = board.stream()
                .filter(i -> i.getStage() == PipelineStage.SCREENING
                        || i.getStage() == PipelineStage.INTERVIEW
                        || i.getStage() == PipelineStage.OFFER)
                .count();

        return new Worklist.Funnel(
                postings.count(),
                candidates.size(),
                candidates.stream().filter(p -> isFresh(p, today)).count(),
                applied, answered, interviewing);
    }

    /**
     * This week's arrivals, best first.
     *
     * <p>The stated point of the whole tool: which few of thousands were not
     * there yesterday. Anything already on the board is dropped, because a job
     * already applied to is not news.
     */
    private List<Worklist.Scored> fresh(List<Posting> candidates, List<JobInterest> board,
            LocalDate today) {

        List<Long> tracked = board.stream()
                .map(JobInterest::getPostingId)
                .filter(java.util.Objects::nonNull)
                .toList();

        return candidates.stream()
                .filter(p -> isFresh(p, today))
                .filter(p -> !tracked.contains(p.getId()))
                .map(p -> new Worklist.Scored(p, scorer.score(p)))
                .sorted(Comparator.comparingInt((Worklist.Scored s) -> s.score().score()).reversed())
                .toList();
    }

    private static boolean isFresh(Posting posting, LocalDate today) {
        // An undated posting is not fresh. Roughly a third of the corpus has no
        // posted date, and treating absence as "today" would fill the one list
        // that is supposed to be short.
        return posting.getPostedDate() != null
                && !posting.getPostedDate().isBefore(today.minusDays(FRESH_DAYS));
    }
}
