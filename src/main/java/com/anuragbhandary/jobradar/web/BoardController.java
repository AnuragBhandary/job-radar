package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.pipeline.JobInterest;
import com.anuragbhandary.jobradar.pipeline.PipelineService;
import com.anuragbhandary.jobradar.pipeline.PipelineStage;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The pipeline board.
 *
 * <p>Columns instead of drag-and-drop. A select on each card moves it, which is
 * two clicks rather than one and works on a phone, keyboard and screen reader
 * without a drag library. The board is read far more often than it is rearranged.
 */
@Controller
public class BoardController {

    private final PipelineService pipeline;

    public BoardController(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @GetMapping(value = "/board", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String board(@RequestParam(required = false) String said) {
        LocalDate today = LocalDate.now();
        List<PipelineService.Column> columns = pipeline.board();

        StringBuilder body = new StringBuilder(Components.toast(said));
        body.append(Parts.pageHead("Applications",
                "Everything you have applied to, and where each one has got to. Move a "
                        + "card with its dropdown - it works from the keyboard, which is "
                        + "why this is not a drag-and-drop board.",
                "<form method=\"post\" action=\"/board/import\" class=\"inline-form\">"
                        + "<button class=\"btn btn-sm\" type=\"submit\" "
                        + "data-busy=\"importing\">Import from sheet</button></form>"));

        List<PipelineService.Entry> due = pipeline.dueReminders(today);
        if (!due.isEmpty()) {
            body.append("<section class=\"card panel callout-warn\">")
                    .append(Ui.panelHead("Follow up",
                            Ui.badge("warn", String.valueOf(due.size()))))
                    .append("<ul class=\"blocked-list\">");
            due.forEach(entry -> body.append("<li><span class=\"q\">")
                    .append(Ui.esc(entry.interest().getCompany())).append("</span> — ")
                    .append("<span class=\"why\">").append(Ui.esc(entry.interest().getRole()))
                    .append(", due ").append(entry.interest().getRemindOn())
                    .append("</span></li>"));
            body.append("</ul></section>");
        }

        body.append("<div class=\"block-head\"><h2>In progress</h2>"
                + "<span class=\"count\">" + columns.stream()
                        .filter(column -> PipelineStage.live().contains(column.stage()))
                        .mapToInt(PipelineService.Column::size).sum() + "</span></div>");
        body.append("<div class=\"board board-6\">");
        columns.stream()
                .filter(column -> PipelineStage.live().contains(column.stage()))
                .forEach(column -> body.append(column(column, today)));
        body.append("</div>");

        List<PipelineService.Column> closed = columns.stream()
                .filter(column -> column.stage().isTerminal() && column.size() > 0)
                .toList();
        if (!closed.isEmpty()) {
            body.append("<div class=\"block-head\"><h2>Closed</h2><span class=\"count\">"
                            + closed.stream().mapToInt(PipelineService.Column::size).sum()
                            + "</span></div>")
                    .append("<div class=\"board board-2 band-closed\">");
            closed.forEach(column -> body.append(column(column, today)));
            body.append("</div>");
        }

        int total = columns.stream().mapToInt(PipelineService.Column::size).sum();
        long live = columns.stream()
                .filter(column -> !column.stage().isTerminal())
                .mapToInt(PipelineService.Column::size).sum();

        String stat = "<strong>%d</strong> tracked<span class=\"sep\">·</span>"
                .formatted(total)
                + "<strong>%d</strong> live".formatted(live);

        return Ui.page("Applications", stat, body.toString(), Ui.Tab.BOARD);
    }

    private String column(PipelineService.Column column, LocalDate today) {
        StringBuilder cards = new StringBuilder();
        for (PipelineService.Entry entry : column.entries()) {
            cards.append(card(entry, today));
        }
        String empty = column.size() == 0 ? "<p class=\"col-empty\">nothing here</p>" : "";
        return """
                <div class="col">
                  <div class="col-head"><span>%s</span><span class="n">%d</span></div>
                  <div class="col-body">%s</div>%s
                </div>
                """.formatted(Ui.esc(column.stage().label()), column.size(), cards, empty);
    }

    private String card(PipelineService.Entry entry, LocalDate today) {
        JobInterest interest = entry.interest();

        StringBuilder options = new StringBuilder();
        for (PipelineStage stage : PipelineStage.values()) {
            options.append("<option value=\"").append(stage.name()).append('"')
                    .append(stage == interest.getStage() ? " selected" : "").append('>')
                    .append(Ui.esc(stage.label())).append("</option>");
        }

        String score = entry.score() == null ? ""
                : "<span class=\"score score-" + entry.score().band().label()
                        + "\">" + entry.score().score() + "</span>";

        String link = interest.getUrl() == null || interest.getUrl().isBlank() ? ""
                : "<a href=\"" + Ui.esc(interest.getUrl())
                        + "\" target=\"_blank\" rel=\"noreferrer\">posting</a>";

        String note = interest.getNotes() == null || interest.getNotes().isBlank() ? ""
                : "<div class=\"note\">" + Ui.esc(interest.getNotes()) + "</div>";

        String due = entry.isDue(today)
                ? "<div class=\"due\">follow up · " + interest.getRemindOn() + "</div>" : "";

        // The lane and how long this has been sitting where it is. Both were
        // already in the database and on no card, so the board could show
        // fifteen applications and not which of them had gone stale.
        StringBuilder facts = new StringBuilder("<div class=\"bc-facts\">");
        if (entry.posting() != null && entry.posting().getStrategicClass() != null) {
            facts.append(Parts.tag(
                    HomeController.laneTone(entry.posting().getStrategicClass()),
                    HomeController.laneName(entry.posting().getStrategicClass())));
        }
        // Days since it was sent, where it has been sent. Not Entry.drift(),
        // which is how far the *score* has moved since it was saved - a useful
        // number that is not a duration, and reading it as one put "-4 days here"
        // on a card that had been open for a fortnight.
        interest.daysSinceApplied(today).ifPresent(days ->
                facts.append("<span class=\"bc-age\">").append(days)
                        .append(days == 1 ? " day since applying" : " days since applying")
                        .append("</span>"));
        Integer drift = entry.drift();
        if (drift != null && drift <= -5) {
            // Only a fall worth noticing. A point or two either way is noise, and
            // a badge on every card for noise is a badge nobody reads.
            facts.append(Parts.tag(Parts.Tone.WARN, "score down " + Math.abs(drift)));
        }
        facts.append("</div>");

        // The note form is collapsed behind a <details>. Fifteen open textareas on
        // a board is not a board, and a note is written once and read often.
        return """
                <div class="jobcard">
                  <div class="c">%s</div>
                  <div class="r">%s</div>
                  %s%s%s
                  <div class="foot">
                    <form method="post" action="/board/move">
                      <input type="hidden" name="interestId" value="%d">
                      <select name="stage" onchange="this.form.submit()">%s</select>
                    </form>
                    <span>%s %s</span>
                  </div>
                  <details class="jotter">
                    <summary>note %s</summary>
                    <form method="post" action="/board/annotate">
                      <input type="hidden" name="interestId" value="%d">
                      <textarea class="input" name="notes" rows="2"
                        placeholder="Anything worth remembering.">%s</textarea>
                      <label class="remind">remind me
                        <input type="date" name="remindOn" value="%s">
                      </label>
                      <button class="btn btn-sm" type="submit">Save</button>
                    </form>
                  </details>
                </div>
                """.formatted(Ui.esc(interest.getCompany()), Ui.esc(interest.getRole()),
                        facts, note, due, interest.getId(), options, score, link,
                        interest.getNotes() == null || interest.getNotes().isBlank()
                                ? "" : "•",
                        interest.getId(),
                        Ui.esc(interest.getNotes()),
                        interest.getRemindOn() == null ? "" : interest.getRemindOn());
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    @PostMapping("/board/move")
    public String move(@RequestParam Long interestId, @RequestParam String stage,
            RedirectAttributes flash) {
        PipelineStage moved = PipelineStage.valueOf(stage);
        var interest = pipeline.move(interestId, moved);
        flash.addAttribute("said",
                interest.getCompany() + " moved to " + moved.label()
                        + (moved.isSent() && interest.getAppliedOn() != null
                                ? ". Dated " + interest.getAppliedOn() + "." : "."));
        return "redirect:/board";
    }

    @PostMapping("/board/import")
    public String importFromTracker(RedirectAttributes flash) {
        try {
            flash.addAttribute("said", pipeline.importFromTracker().describe());
        } catch (IOException e) {
            // The board still renders; a failed import is not worth a stack trace
            // in front of someone who clicked a button. It is worth a sentence,
            // though - this used to redirect in silence whether it had imported
            // seventeen rows or thrown.
            flash.addAttribute("said", "Could not read the sheet: " + e.getMessage());
        }
        return "redirect:/board";
    }

    @PostMapping("/board/annotate")
    public String annotate(@RequestParam Long interestId,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) String remindOn) {

        LocalDate remind = null;
        if (remindOn != null && !remindOn.isBlank()) {
            try {
                remind = LocalDate.parse(remindOn.trim());
            } catch (DateTimeParseException e) {
                // An unparseable date clears the reminder rather than throwing.
                // The field is a convenience and should never lose the note.
                remind = null;
            }
        }
        pipeline.annotate(interestId, notes, remind);
        return "redirect:/board";
    }

    /** Saves a posting straight from the feed. */
    @PostMapping("/save")
    public String save(@RequestParam Long postingId) {
        pipeline.save(postingId, PipelineStage.SAVED);
        return "redirect:/jobs";
    }
}
