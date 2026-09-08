package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.money.SalaryGuide;
import com.anuragbhandary.jobradar.worklist.Worklist;
import com.anuragbhandary.jobradar.worklist.WorklistService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The home page: what to do today.
 *
 * <p>It used to be the job list, which answered a question nobody has. Nine
 * thousand postings screen down to fifty-odd, of which a handful arrived this
 * week; the rest were read days ago. Opening the tool to that list every morning
 * is opening it to yesterday's news, while the things that genuinely needed a
 * decision - a form stopped on an unanswered question, an application filled and
 * never sent, one silent for three weeks - were on no screen at all.
 *
 * <p>So: the decisions first, the honest read on the funnel second, and this
 * week's arrivals third. The full list is still one click away and is now
 * somewhere a person goes deliberately rather than somewhere they land.
 */
@Controller
public class HomeController {

    private final WorklistService worklist;
    private final SalaryGuide salary;
    private final CompanyNames companies;

    public HomeController(WorklistService worklist, SalaryGuide salary, CompanyNames companies) {
        this.worklist = worklist;
        this.salary = salary;
        this.companies = companies;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String today(@RequestParam(required = false) String said) {
        Worklist today = worklist.build(LocalDate.now());

        StringBuilder body = new StringBuilder(Components.toast(said));
        body.append(tasks(today.tasks()));
        body.append(funnel(today.funnel()));
        body.append(fresh(today.fresh()));

        String stat = today.tasks().isEmpty()
                ? "<span class=\"note-muted\">nothing waiting on you</span>"
                : "<strong>" + today.tasks().size() + "</strong> waiting on you";
        return Ui.page("Today", stat, body.toString(), Ui.Tab.TODAY);
    }

    // ------------------------------------------------------------------

    private String tasks(List<Worklist.Task> tasks) {
        if (tasks.isEmpty()) {
            return Components.section("Needs you", "0",
                    Components.empty("Nothing is waiting on you.",
                            "No blocked forms, nothing prepared and unsent, and no application "
                                    + "has gone quiet. Applying to something new is the only "
                                    + "move left.",
                            "/jobs", "Open the job list"));
        }
        StringBuilder list = new StringBuilder();
        tasks.forEach(task -> list.append(Components.task(
                task.kind().tone(), task.kind().label(),
                task.title(), task.detail(), task.href(), task.action())));

        return Components.section("Needs you", String.valueOf(tasks.size()), list.toString());
    }

    /**
     * The funnel, and one sentence saying where it is stuck.
     *
     * <p>The tiles are deliberately not all weighted the same. "Screened" is
     * nine thousand and means almost nothing; it is the denominator, and it is
     * rendered quiet so it stops looking like an achievement. The number that
     * decides whether there is anything to do is how many arrived this week.
     */
    private String funnel(Worklist.Funnel f) {
        String tiles = "<div class=\"tiles\">"
                + Components.tile(compact(f.screened()), "Screened", "all time", true)
                + Components.tile(String.valueOf(f.open()), "Open now", "passed screening", false)
                + Components.tile(String.valueOf(f.freshThisWeek()), "New this week",
                        "the reason to look", false)
                + Components.tile(String.valueOf(f.applied()), "Applied", null, false)
                + Components.tile(String.valueOf(f.answered()), "Answered",
                        f.answered() == 0 ? "including rejections" : "any reply", false)
                + Components.tile(String.valueOf(f.interviewing()), "In process", null,
                        f.interviewing() == 0)
                + "</div>";

        return Components.section("Where the search is", null,
                tiles + "<p class=\"readout\">" + Ui.esc(f.diagnosis()) + "</p>");
    }

    private String fresh(List<Worklist.Scored> fresh) {
        if (fresh.isEmpty()) {
            return Components.section("New this week", "0",
                    Components.empty("Nothing new matched this week.",
                            "Every posting that passed screening was already here last week. "
                                    + "That usually means the board list needs widening rather "
                                    + "than that there are no jobs.",
                            "/setup", "Check the boards"));
        }
        StringBuilder rows = new StringBuilder("<div class=\"card list\">");
        fresh.forEach(scored -> rows.append(Components.jobRow(
                scored.posting().getId(),
                scored.score(),
                companies.of(scored.posting()),
                scored.posting().getTitle(),
                List.of(
                        scored.posting().getCountry() == null ? ""
                                : Ui.badge("country", scored.posting().getCountry().name()),
                        "<span>" + Ui.esc(nullSafe(scored.posting().getLocation())) + "</span>",
                        "<span>" + Ui.esc(age(scored.posting().getPostedDate())) + "</span>"),
                scored.score().headline(),
                salary.forPosting(scored.posting()),
                Components.postButton("/save", "Save", "saving", "postingId",
                        scored.posting().getId(), "btn btn-sm")
                        + Components.postButton("/prepare", "Prepare", "filling the form",
                                "postingId", scored.posting().getId(), "btn"))));
        rows.append("</div>");

        return Components.section("New this week", String.valueOf(fresh.size()), rows.toString());
    }

    private static String age(LocalDate posted) {
        if (posted == null) {
            return "no date";
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(posted, LocalDate.now());
        if (days <= 0) {
            return "today";
        }
        return days == 1 ? "yesterday" : days + " days ago";
    }

    /** "9,032" reads as a quantity; "9032" reads as an id. */
    private static String compact(long value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
