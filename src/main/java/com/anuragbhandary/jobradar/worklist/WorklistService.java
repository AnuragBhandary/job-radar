package com.anuragbhandary.jobradar.worklist;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.AttemptStatus;
import com.anuragbhandary.jobradar.apply.ApplicationFieldRepository;
import com.anuragbhandary.jobradar.apply.ManualReason;
import com.anuragbhandary.jobradar.apply.Readiness;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.match.MatchScorer;
import com.anuragbhandary.jobradar.pipeline.JobInterest;
import com.anuragbhandary.jobradar.pipeline.JobInterestRepository;
import com.anuragbhandary.jobradar.pipeline.PipelineStage;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** How far each attempt actually got, so a queue row can say so. */
    private final ApplicationFieldRepository fields;

    public WorklistService(PostingRepository postings, ApplicationAttemptRepository attempts,
            JobInterestRepository interests, BoardTokenRepository boards, MatchScorer scorer,
            ApplicationFieldRepository fields) {
        this.postings = postings;
        this.attempts = attempts;
        this.interests = interests;
        this.boards = boards;
        this.scorer = scorer;
        this.fields = fields;
    }

    public Worklist build(LocalDate today) {
        List<JobInterest> board = interests.findAll();
        List<Posting> candidates = postings.findRecommended();

        List<Worklist.Task> tasks = new ArrayList<>();
        tasks.addAll(applicationTasks());
        tasks.addAll(reminders(board, today));
        tasks.addAll(quiet(board, today));
        tasks.addAll(brokenBoards());
        tasks.sort(Comparator.comparingInt(Worklist.Task::urgency).reversed());

        return new Worklist(tasks, funnel(board, candidates, today),
                fresh(candidates, board, today), lanes(candidates));
    }

    // ------------------------------------------------------------------
    // Tasks
    // ------------------------------------------------------------------

    /**
     * One task per application, from the attempt's own state.
     *
     * <p>Two things this fixes. It reads {@code AttemptStatus} and
     * {@link ManualReason} rather than the shape of a prose sentence, so a
     * captcha is no longer labelled "Answer this" - which is what the home page
     * said until this phase, about a task that has nothing to answer.
     *
     * <p>And it is one row per <em>posting</em>. Preparing the same job twice
     * produces two attempts and produced two identical queue rows, so the list
     * said seven things needed him when four did. The newest attempt wins: an
     * earlier one is history, not a task.
     */
    private List<Worklist.Task> applicationTasks() {
        Map<Long, ApplicationAttempt> newestByPosting = new LinkedHashMap<>();
        for (AttemptStatus status : List.of(
                AttemptStatus.AWAITING_ANSWER, AttemptStatus.AWAITING_APPROVAL,
                AttemptStatus.MANUAL_REQUIRED, AttemptStatus.READY_FOR_REVIEW,
                AttemptStatus.NEEDS_HUMAN, AttemptStatus.PREPARED)) {

            for (ApplicationAttempt attempt : attempts.findByStatusOrderByStartedAtDesc(status)) {
                newestByPosting.merge(attempt.getPostingId(), attempt,
                        (kept, other) -> kept.getStartedAt().isAfter(other.getStartedAt())
                                ? kept : other);
            }
        }
        return newestByPosting.values().stream().map(this::taskFor).toList();
    }

    private Worklist.Task taskFor(ApplicationAttempt attempt) {
        Posting posting = postings.findById(attempt.getPostingId()).orElse(null);
        Readiness ready = Readiness.of(fields.findByAttemptIdOrderByIdAsc(attempt.getId()));
        String href = "/attempt/" + attempt.getId();
        String progress = ready.total() == 0 ? null
                : ready.prepared() + " of " + ready.total() + " fields prepared";

        Worklist.Kind kind;
        String detail;
        String action;
        int urgency;

        switch (attempt.getStatus()) {
            case AWAITING_ANSWER -> {
                kind = Worklist.Kind.ANSWER;
                detail = ready.awaitingAnswer() == 1
                        ? "One required question has no reliable answer."
                        : ready.awaitingAnswer() + " required questions have no reliable "
                                + "answer.";
                if (ready.total() == 0) {
                    detail = reason(attempt);
                }
                action = "Answer it";
                urgency = 100;
            }
            case AWAITING_APPROVAL -> {
                kind = Worklist.Kind.APPROVAL;
                detail = ready.awaitingApproval() == 1
                        ? "A drafted answer is waiting for you to read it."
                        : ready.awaitingApproval() + " drafted answers are waiting for you.";
                action = "Read and approve";
                urgency = 95;
            }
            case READY_FOR_REVIEW, PREPARED -> {
                kind = Worklist.Kind.UNSENT;
                detail = "Filled and nothing outstanding. It has not been sent.";
                action = "Read and send";
                urgency = 90;
            }
            case MANUAL_REQUIRED -> {
                kind = Worklist.Kind.MANUAL;
                ManualReason why = attempt.getManualReason();
                detail = why == null ? reason(attempt)
                        : why.explanation() + " - " + why.detail()
                                + (why.valuesReusable()
                                        ? ". Your answers and tailored resume are kept." : ".");
                action = why != null && why.resumable() ? "Finish it" : "Apply by hand";
                urgency = 85;
            }
            // Recorded before the states were separated, so the prose is genuinely
            // all these rows have. Kept rather than rewritten: an attempt's
            // history is not revised to fit a newer model.
            default -> {
                boolean noForm = attempt.getBlockerReason() != null
                        && attempt.getBlockerReason().startsWith("No form found");
                kind = noForm ? Worklist.Kind.MANUAL : Worklist.Kind.ANSWER;
                detail = noForm
                        ? "The board never showed a form. Your tailored resume is ready, "
                                + "so this is a five-minute job by hand."
                        : reason(attempt);
                action = noForm ? "Open it" : "Answer it";
                urgency = noForm ? 85 : 100;
            }
        }

        return new Worklist.Task(kind, attempt.getCompany(), attempt.getRole(), detail,
                href, action, urgency, progress,
                posting == null ? null : posting.getStrategicClass(),
                posting == null ? null : posting.getLocation());
    }

    private List<Worklist.Task> reminders(List<JobInterest> board, LocalDate today) {
        return board.stream()
                .filter(i -> i.getRemindOn() != null && !i.getRemindOn().isAfter(today))
                .map(i -> Worklist.Task.simple(
                        Worklist.Kind.REMINDER,
                        i.getCompany(),
                        i.getNotes() == null || i.getNotes().isBlank()
                                ? "Due " + i.getRemindOn() : i.getNotes(),
                        "/board", 80))
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
                .map(row -> Worklist.Task.simple(
                        Worklist.Kind.QUIET,
                        row.interest.getCompany(),
                        "Silent for " + row.days + " days since you applied",
                        "/board",
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
        return List.of(Worklist.Task.simple(
                Worklist.Kind.BROKEN_BOARD,
                broken.size() + (broken.size() == 1 ? " board is failing" : " boards are failing"),
                names + (broken.size() > 4 ? " and others" : "")
                        + ". Nothing from these reaches the feed.",
                "/setup", 60));
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

    /**
     * Live opportunities per strategic lane.
     *
     * <p>On the home page so the search can be seen to be pursuing the strategy.
     * Counted from what is recommended right now rather than from what was
     * screened ever, because the question it answers is "is there anything in the
     * lane I care about today".
     *
     * <p>A posting screened before the lanes existed has none, and is counted
     * under {@link StrategicClass#UNCLASSIFIED} rather than guessed at.
     */
    private static Map<StrategicClass, Long> lanes(List<Posting> candidates) {
        Map<StrategicClass, Long> counts = new EnumMap<>(StrategicClass.class);
        for (Posting posting : candidates) {
            StrategicClass lane = posting.getStrategicClass() == null
                    ? StrategicClass.UNCLASSIFIED : posting.getStrategicClass();
            counts.merge(lane, 1L, Long::sum);
        }
        return counts;
    }

    private static boolean isFresh(Posting posting, LocalDate today) {
        // An undated posting is not fresh. Roughly a third of the corpus has no
        // posted date, and treating absence as "today" would fill the one list
        // that is supposed to be short.
        return posting.getPostedDate() != null
                && !posting.getPostedDate().isBefore(today.minusDays(FRESH_DAYS));
    }
}
