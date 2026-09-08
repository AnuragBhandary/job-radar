package com.anuragbhandary.jobradar.worklist;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.match.MatchScore;
import java.util.List;

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
 */
public record Worklist(List<Task> tasks, Funnel funnel, List<Scored> fresh) {

    /** Why a task is on the list. Drives its colour and its ordering. */
    public enum Kind {
        /** A form stopped on a question with no answer in the profile. */
        BLOCKED("Answer this", "bad"),
        /**
         * The board never showed a form: a sign-in wall, or an apply link that
         * leads somewhere else. Nothing to answer and nothing to learn from, so
         * it is a different task with a different action - go and do it by hand.
         */
        NO_FORM("Apply by hand", "warn"),
        /** Filled and never sent. The work is done and wasted until it goes. */
        UNSENT("Send it", "warn"),
        /** A reminder the user set, now due. */
        REMINDER("You asked to be reminded", "warn"),
        /** Sent, and silent for long enough to be worth a nudge. */
        QUIET("Chase or drop", "soft"),
        /** A board stopped returning postings, so jobs are being missed. */
        BROKEN_BOARD("Jobs are being missed", "bad");

        private final String label;
        private final String tone;

        Kind(String label, String tone) {
            this.label = label;
            this.tone = tone;
        }

        public String label() {
            return label;
        }

        /** Maps to the badge modifiers already in the stylesheet. */
        public String tone() {
            return tone;
        }
    }

    /**
     * @param urgency higher sorts first. Carries days-waited for QUIET so a
     *                six-week silence outranks a two-week one without a second
     *                sort key.
     * @param href    where the action happens. Every task must have somewhere to go;
     *                a task that only informs is not a task.
     */
    public record Task(Kind kind, String title, String detail, String href,
            String action, int urgency) {
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
