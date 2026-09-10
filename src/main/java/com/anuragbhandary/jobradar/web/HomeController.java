package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.money.SalaryGuide;
import com.anuragbhandary.jobradar.web.Parts.Tone;
import com.anuragbhandary.jobradar.worklist.Worklist;
import com.anuragbhandary.jobradar.worklist.WorklistService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The command centre: what to do right now.
 *
 * <p>It used to be the job list, which answered a question nobody has. Nine
 * thousand postings screen down to fifty-odd, of which a handful arrived this
 * week; the rest were read days ago. Opening the tool to that list every morning
 * is opening it to yesterday's news, while the things that genuinely needed a
 * decision - a form stopped on an unanswered question, an application filled and
 * never sent, one silent for three weeks - were on no screen at all.
 *
 * <h2>What the page is ordered by</h2>
 * Decisions, then the strategy, then the numbers, then this week's arrivals. The
 * queue is grouped by <em>what he would be doing</em> rather than by which table
 * the row came from, because "answer a question" and "finish a captcha" are
 * fifteen seconds and five minutes and belong in different piles.
 *
 * <p>The tiles carry outcomes and no longer lead with how many rows the scraper
 * has seen. Nine thousand screened is the denominator, not an achievement, and
 * putting it in the largest type on the page said how hard the tool had worked
 * rather than how the search was going.
 */
@Controller
public class HomeController {

    /** Beyond this the queue is truncated, and the heading still counts the rest. */
    private static final int QUEUE_LIMIT = 12;

    /** How many of this week's arrivals belong on the home page. */
    private static final int FRESH_LIMIT = 6;

    private final WorklistService worklist;
    private final SalaryGuide salary;
    private final CompanyNames companies;

    public HomeController(WorklistService worklist, SalaryGuide salary,
            CompanyNames companies) {
        this.worklist = worklist;
        this.salary = salary;
        this.companies = companies;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String today(@RequestParam(required = false) String said) {
        Worklist today = worklist.build(LocalDate.now());
        int waiting = today.tasks().size();

        StringBuilder body = new StringBuilder(Components.toast(said));
        body.append(greeting(waiting, today));
        body.append(waiting == 0 ? caughtUp() : queue(today));
        body.append(strategy(today.lanes()));
        body.append(numbers(today.funnel()));
        body.append(fresh(today.fresh()));

        String stat = waiting == 0
                ? "<span class=\"note-muted\">nothing waiting on you</span>"
                : "<strong>" + waiting + "</strong> waiting on you";
        return Ui.page("Today", stat, body.toString(), Ui.Tab.TODAY);
    }

    // ------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------

    /**
     * The one sentence the page exists to say.
     *
     * <p>A count and a breakdown, not a welcome. "Four things need your attention
     * · 2 needs your answer, 1 waiting on approval, 1 manual application" is
     * enough to decide whether this is a ten-minute morning or a five-second one,
     * before anything else on the page has been read.
     */
    private String greeting(int waiting, Worklist today) {
        String lede = waiting == 0
                ? "Nothing is blocked, and nothing is waiting to be sent."
                : waiting + (waiting == 1 ? " thing needs" : " things need")
                        + " your attention. " + breakdown(today);
        return Parts.pageHead(hour() + ".", lede, null);
    }

    /**
     * "2 answers needed · 1 draft to approve · 3 to do by hand".
     *
     * <p>Counted per kind rather than per group heading: a heading is a label and
     * reads as broken English after a numeral, which is how the first version of
     * this line came to say "3 manual application".
     */
    private String breakdown(Worklist today) {
        Map<Worklist.Kind, Integer> counts = new java.util.EnumMap<>(Worklist.Kind.class);
        today.tasks().forEach(task -> counts.merge(task.kind(), 1, Integer::sum));
        StringBuilder out = new StringBuilder();
        counts.forEach((kind, n) -> {
            if (!out.isEmpty()) {
                out.append(" · ");
            }
            out.append(kind.counted(n));
        });
        return out.toString();
    }

    /**
     * Time of day, from the machine's clock.
     *
     * <p>The only personal touch on the page, and it stops short of the name: a
     * tool used by one person does not need to tell him who he is.
     */
    private static String hour() {
        int hour = LocalTime.now().getHour();
        if (hour < 12) {
            return "Good morning";
        }
        return hour < 17 ? "Good afternoon" : "Good evening";
    }

    // ------------------------------------------------------------------
    // The queue
    // ------------------------------------------------------------------

    /**
     * The largest thing on the page, grouped by the action it wants.
     *
     * <p>Capped, and honest about the cap. A queue showing forty items is a queue
     * that gets scrolled past; the ones below the line are still counted in the
     * heading and still reachable from the page they belong to.
     */
    private String queue(Worklist today) {
        StringBuilder out = new StringBuilder();
        int shown = 0;

        for (Map.Entry<String, List<Worklist.Task>> group : today.byGroup().entrySet()) {
            int room = QUEUE_LIMIT - shown;
            if (room <= 0) {
                break;
            }
            List<Worklist.Task> tasks = group.getValue();
            List<Worklist.Task> visible = tasks.size() > room ? tasks.subList(0, room) : tasks;
            StringBuilder rows = new StringBuilder();
            visible.forEach(task -> rows.append(card(task)));
            shown += visible.size();

            if (visible.size() < tasks.size()) {
                rows.append("<p class=\"caption\">and ")
                        .append(tasks.size() - visible.size())
                        .append(" more like this</p>");
            }
            out.append(Parts.block(group.getKey(), String.valueOf(tasks.size()),
                    rows.toString()));
        }
        return out.toString();
    }

    /**
     * One queue row.
     *
     * <p>Answers what, where, how far along and what to do without being opened.
     * The progress line matters most: "18 of 20 fields prepared" is the
     * difference between a task worth starting now and one worth leaving until
     * the evening, and it was not on the old queue at all.
     */
    private String card(Worklist.Task task) {
        StringBuilder meta = new StringBuilder();
        if (task.lane() != null) {
            meta.append(Parts.tag(laneTone(task.lane()), laneName(task.lane())));
        }
        if (task.location() != null && !task.location().isBlank()) {
            meta.append(Parts.tag(shorten(task.location())));
        }
        if (task.progress() != null) {
            meta.append("<span class=\"task-progress\">").append(Ui.esc(task.progress()))
                    .append("</span>");
        }
        return Parts.task(tone(task.kind().tone()), task.kind().label(),
                task.title(), task.role(), task.detail(), meta.toString(),
                Parts.linkButton(task.href(), task.action(), "btn btn-sm"));
    }

    /**
     * Nothing needs him, so the page says what is worth doing instead.
     *
     * <p>An empty dashboard reads as a broken one. The sections below this - the
     * strategy, the numbers, this week's arrivals - all still render, so a clear
     * queue leaves a page with something on it rather than a blank rectangle.
     */
    private String caughtUp() {
        return Parts.block("Your queue", "0", Parts.blank(
                "You're caught up.",
                "No application is blocked, none is waiting to be approved, and none has "
                        + "been prepared and left unsent. The next move is starting a new one.",
                Parts.linkButton("/jobs", "Find something to apply to", "btn btn-primary")));
    }

    // ------------------------------------------------------------------
    // Strategy
    // ------------------------------------------------------------------

    /**
     * The search, as the strategy sees it.
     *
     * <p>Four lanes and a live count each, so the plan and the pipeline can be
     * compared at a glance: "international relocation is the primary objective"
     * and "there are three of them open" is a different morning from the same
     * sentence with forty.
     *
     * <p>Counts only. No money here - the floors live on the Strategy page, where
     * there is room to say what each is based on and when it was last checked.
     */
    private String strategy(Map<StrategicClass, Long> lanes) {
        if (lanes.isEmpty()) {
            return "";
        }
        StringBuilder rows = new StringBuilder("<div class=\"lanes\">");
        for (StrategicClass lane : List.of(
                StrategicClass.INTERNATIONAL_RELOCATION, StrategicClass.INTERNATIONAL_REMOTE,
                StrategicClass.INDIA_HOME, StrategicClass.INDIA_OTHER,
                StrategicClass.UNCLASSIFIED)) {

            long count = lanes.getOrDefault(lane, 0L);
            if (count == 0 && lane == StrategicClass.UNCLASSIFIED) {
                continue;
            }
            rows.append("""
                    <a class="lane" href="/jobs?lane=%s">
                      <span class="lane-n">%d</span>
                      <span class="lane-name">%s</span>
                      <span class="lane-role">%s</span>
                    </a>
                    """.formatted(lane.name(), count, Ui.esc(laneName(lane)),
                            Ui.esc(laneRole(lane))));
        }
        rows.append("</div>");
        return Parts.block("Your search", null,
                "<span class=\"note-muted\">open now, by lane</span>", rows.toString());
    }

    /**
     * What each lane is <em>for</em>.
     *
     * <p>Strategic context, never financial advice: the sentence names the part
     * the lane plays in the plan, and every number behind it lives on the
     * Strategy page with its basis and its check-by date attached.
     */
    private static String laneRole(StrategicClass lane) {
        return switch (lane) {
            case INTERNATIONAL_RELOCATION -> "Primary objective";
            case INTERNATIONAL_REMOTE -> "Highest value, no move";
            case INDIA_HOME -> "Financial safety net";
            case INDIA_OTHER -> "Only above the relocation floor";
            case UNCLASSIFIED -> "Screened before the lanes existed";
        };
    }

    static String laneName(StrategicClass lane) {
        return switch (lane) {
            case INTERNATIONAL_RELOCATION -> "International — Relocation";
            case INTERNATIONAL_REMOTE -> "International — Remote";
            case INDIA_HOME -> "India — Home";
            case INDIA_OTHER -> "India — Other";
            case UNCLASSIFIED -> "Unclassified";
        };
    }

    /** A lane is a classification rather than a status, so it stays quiet. */
    static Tone laneTone(StrategicClass lane) {
        return switch (lane) {
            case INTERNATIONAL_RELOCATION, INTERNATIONAL_REMOTE -> Tone.INFO;
            default -> Tone.QUIET;
        };
    }

    // ------------------------------------------------------------------
    // Numbers
    // ------------------------------------------------------------------

    /**
     * Outcomes, and one sentence on where the funnel is stuck.
     *
     * <p>Applied, answered, in process, new this week, open now - five things
     * that describe the search. How many postings have ever been screened is
     * deliberately not among them: it was the largest number on this page, and it
     * measured the scraper.
     */
    private String numbers(Worklist.Funnel funnel) {
        String stats = "<div class=\"stats\">"
                + Parts.stat(String.valueOf(funnel.applied()), "Applications sent")
                + Parts.stat(String.valueOf(funnel.answered()),
                        funnel.answered() == 0 ? "Replies, including rejections" : "Replies")
                + Parts.stat(String.valueOf(funnel.interviewing()), "In process")
                + Parts.stat(String.valueOf(funnel.freshThisWeek()), "New this week")
                + Parts.stat(String.valueOf(funnel.open()), "Open now")
                + "</div>";

        return Parts.block("How the search is going", null,
                "<div class=\"surface surface-pad\">" + stats
                        + "<p class=\"readout\">" + Ui.esc(funnel.diagnosis())
                        + "</p></div>");
    }

    // ------------------------------------------------------------------
    // This week
    // ------------------------------------------------------------------

    private String fresh(List<Worklist.Scored> fresh) {
        if (fresh.isEmpty()) {
            return Parts.block("New this week", "0", Parts.blank(
                    "Nothing new matched this week.",
                    "Every posting that passed screening was already here last week. That "
                            + "usually means the board list needs widening rather than that "
                            + "there are no jobs.",
                    Parts.linkButton("/setup", "Check the boards", "btn")));
        }
        List<Worklist.Scored> shown = fresh.size() > FRESH_LIMIT
                ? fresh.subList(0, FRESH_LIMIT) : fresh;

        StringBuilder rows = new StringBuilder("<div class=\"joblist\">");
        shown.forEach(scored -> rows.append(JobCard.render(scored.posting(), scored.score(),
                companies.of(scored.posting()), salary.forPosting(scored.posting()), null)));
        rows.append("</div>");

        return Parts.block("New this week", String.valueOf(fresh.size()),
                Parts.linkButton("/jobs", fresh.size() > shown.size()
                        ? "See all " + fresh.size() : "Open the job list", "btn btn-sm"),
                rows.toString());
    }

    /** "Bengaluru, Karnataka, India" is a chip nobody can read. The city will do. */
    private static String shorten(String location) {
        int comma = location.indexOf(',');
        return comma > 0 ? location.substring(0, comma).trim() : location.trim();
    }

    private static Tone tone(String name) {
        return switch (name) {
            case "ok" -> Tone.OK;
            case "warn" -> Tone.WARN;
            case "bad" -> Tone.BAD;
            case "manual" -> Tone.MANUAL;
            case "info" -> Tone.INFO;
            default -> Tone.QUIET;
        };
    }
}
