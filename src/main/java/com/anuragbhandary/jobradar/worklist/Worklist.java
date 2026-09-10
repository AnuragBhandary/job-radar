package com.anuragbhandary.jobradar.worklist;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.match.MatchScore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What to do today, and how the search is actually going.
 *
 * <p>The queue this replaced answered "which postings are open", which is not a
 * question anyone has in the morning. Nine thousand postings screen down to
 * sixty-five, of which nine arrived this week; the other fifty-six were read days
 * ago and are not news. Meanwhile fifteen applications were sitting in APPLIED
 * with nothing anywhere saying which had gone quiet, two prepared applications
 * were never sent, and three forms were blocked on questions nobody had answered.
 *
 * <p>All of that was already in the database. None of it was on a screen.
 *
 * @param tasks  things waiting on a decision, most urgent first
 * @param funnel the shape of the search, which is the honest headline
 * @param fresh  candidates that arrived this week, the thing the tool exists for
 * @param lanes  how many live opportunities sit in each strategic lane, so the
 *               search can be seen to be pursuing the strategy rather than
 *               drifting away from it
 */
public record Worklist(List<Task> tasks, Funnel funnel, List<Scored> fresh,
        Map<StrategicClass, Long> lanes) {

    /**
     * Why a task is on the list.
     *
     * <p>Seven kinds, and the split between them is the point: they are fixed by
     * different actions and they are not equally urgent. A drafted answer awaiting
     * approval takes ten seconds; a form stopped on a question Job Radar has never
     * seen is worth five minutes, because answering it also answers every future
     * form that asks the same thing.
     *
     * <p>Before this, a captcha and an unanswerable question were both
     * {@code BLOCKED} and both rendered "Answer this" - so the home page told him
     * to answer a captcha. The kinds now come from the attempt's structured state
     * rather than from the shape of its blocker sentence.
     */
    public enum Kind {

        /** A required question nothing could answer. The valuable one. */
        ANSWER("Needs your answer", "Answer it", "bad", "Needs your answer",
                "answer needed", "answers needed"),

        /** A drafted answer nobody has read. Ten seconds, and it cannot be skipped. */
        APPROVAL("Your approval required", "Read and approve", "warn", "Waiting on approval",
                "draft to approve", "drafts to approve"),

        /** Filled, nothing outstanding, never sent. The work is already paid for. */
        UNSENT("Ready to send", "Read and send", "ok", "Ready for review",
                "ready to send", "ready to send"),

        /**
         * The board wants a person: a captcha, a sign-in, a form that never
         * appeared. Nothing to answer and nothing to learn - go and do it.
         */
        MANUAL("The website needs you", "Finish it", "manual", "Manual application",
                "to do by hand", "to do by hand"),

        /** Sent, and silent for long enough to be worth a nudge. */
        QUIET("Chase or drop", "Open the board", "quiet", "Follow up",
                "to chase", "to chase"),

        /** A reminder the applicant set, now due. */
        REMINDER("Reminder due", "Open the board", "quiet", "Follow up",
                "reminder", "reminders"),

        /** A board stopped returning postings, so jobs are being missed silently. */
        BROKEN_BOARD("Jobs are being missed", "See which", "bad", "Housekeeping",
                "broken board", "broken boards");

        private final String label;
        private final String action;
        private final String tone;
        private final String group;
        private final String one;
        private final String many;

        Kind(String label, String action, String tone, String group, String one, String many) {
            this.label = label;
            this.action = action;
            this.tone = tone;
            this.group = group;
            this.one = one;
            this.many = many;
        }

        /**
         * The phrase that follows a number in a summary sentence.
         *
         * <p>Its own field because the heading and the sentence want different
         * words. "Manual application" is a fine heading and reads as broken
         * English after a numeral - "3 manual application" - which is what the
         * first version of the summary line said.
         */
        public String counted(int n) {
            return n + " " + (n == 1 ? one : many);
        }

        public String label() {
            return label;
        }

        /** The default button text. A task may override it. */
        public String action() {
            return action;
        }

        /** One of the six semantic tones. Never a colour name. */
        public String tone() {
            return tone;
        }

        /** The queue heading this kind appears under. Kinds may share one. */
        public String group() {
            return group;
        }
    }

    /**
     * One thing waiting on a decision, with enough on it to decide without opening it.
     *
     * <p>The earlier version carried a company and a sentence, so the queue could
     * say that something needed attention and not what it was or how much of it
     * was already done. Every field added here answers one of the four questions
     * a queue row has to answer: what, why, what state, and what to do.
     *
     * @param urgency  higher sorts first. Carries days-waited for QUIET so a
     *                 six-week silence outranks a two-week one with no second key.
     * @param href     where the action happens. A task that only informs is not a
     *                 task.
     * @param progress "18 of 20 fields prepared", or null where no preparation
     *                 sits behind it
     */
    public record Task(Kind kind, String title, String role, String detail,
            String href, String action, int urgency, String progress,
            StrategicClass lane, String location) {

        /** For the tasks that are not about one application. */
        public static Task simple(Kind kind, String title, String detail, String href,
                int urgency) {
            return new Task(kind, title, null, detail, href, kind.action(), urgency,
                    null, null, null);
        }
    }

    /**
     * The queue, split by what he would actually be doing.
     *
     * <p>Grouped here rather than on the page, and in the enum's own order, so
     * the headings cannot drift out of the order the kinds were declared in -
     * which is the order of how much each one is worth doing first.
     */
    public Map<String, List<Task>> byGroup() {
        Map<String, List<Task>> groups = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            for (Task task : tasks) {
                if (task.kind() == kind) {
                    groups.computeIfAbsent(kind.group(), key -> new ArrayList<>()).add(task);
                }
            }
        }
        return groups;
    }

    /**
     * The search as a funnel, which is the one view that says whether it is
     * working.
     *
     * @param answered replies of any kind, including rejections. A rejection is a
     *                 worse outcome than an interview and a far better one than
     *                 silence, and collapsing it into "no interviews" hides that
     *                 the top of the funnel, not the bottom, is what is starving.
     */
    public record Funnel(long screened, long open, long freshThisWeek,
            long applied, long answered, long interviewing) {

        /** Applications per reply. Empty until there is anything to divide. */
        public java.util.OptionalDouble replyRate() {
            return applied == 0 ? java.util.OptionalDouble.empty()
                    : java.util.OptionalDouble.of((double) answered / applied);
        }

        /**
         * The honest one-line read on where the search is stuck.
         *
         * <p>Deliberately names the narrowest point rather than the most
         * flattering number. Nine thousand screened is not an achievement, it is
         * the denominator.
         */
        public String diagnosis() {
            if (freshThisWeek < 5) {
                return "The top of the funnel is the problem. Only " + freshThisWeek
                        + " new " + (freshThisWeek == 1 ? "job" : "jobs")
                        + " matched this week, so there is little to apply to. "
                        + "Widening the boards will do more than polishing an application.";
            }
            if (applied == 0) {
                return "Nothing has been sent yet. The screening works; the applying has "
                        + "not started.";
            }
            if (answered == 0) {
                return "Nothing has come back yet from " + applied + " applications. "
                        + "Too early to read anything into that.";
            }
            if (interviewing == 0 && applied >= 15) {
                return applied + " applications and no interview yet. At this volume that "
                        + "points at the application rather than the search.";
            }
            return applied + " sent, " + answered + " answered.";
        }
    }

    /** A posting with its score, which is how anything is ordered here. */
    public record Scored(Posting posting, MatchScore score) {
    }
}
