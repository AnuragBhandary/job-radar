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
    public String board() {
        LocalDate today = LocalDate.now();
        List<PipelineService.Column> columns = pipeline.board();

        StringBuilder body = new StringBuilder();

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

        body.append("<h2 class=\"section-head\" style=\"margin-bottom:8px\">In progress</h2>");
        body.append("<div class=\"board\">");
        columns.stream()
                .filter(column -> PipelineStage.live().contains(column.stage()))
                .forEach(column -> body.append(column(column, today)));
        body.append("</div>");

        List<PipelineService.Column> closed = columns.stream()
                .filter(column -> column.stage().isTerminal() && column.size() > 0)
                .toList();
        if (!closed.isEmpty()) {
            body.append("<h2 class=\"section-head\" style=\"margin:22px 0 8px\">Closed</h2>")
                    .append("<div class=\"board\">");
            closed.forEach(column -> body.append(column(column, today)));
            body.append("</div>");
        }

        int total = columns.stream().mapToInt(PipelineService.Column::size).sum();
        long live = columns.stream()
                .filter(column -> !column.stage().isTerminal())
                .mapToInt(PipelineService.Column::size).sum();

        String stat = "<strong>%d</strong> tracked<span class=\"sep\">·</span>"
                .formatted(total)
                + "<strong>%d</strong> live".formatted(live)
                + "<span class=\"sep\">·</span>"
                + "<form method=\"post\" action=\"/board/import\">"
                + "<button class=\"btn btn-sm\">import from sheet</button></form>";

        return Ui.page("Board", stat, body.toString());
    }

    private String column(PipelineService.Column column, LocalDate today) {
        StringBuilder cards = new StringBuilder();
        if (column.size() == 0) {
            cards.append("<p class=\"col-empty\">nothing here</p>");
        }
        for (PipelineService.Entry entry : column.entries()) {
            cards.append(card(entry, today));
        }
        return """
                <div class="col">
                  <div class="col-head"><span>%s</span><span class="n">%d</span></div>
                  <div class="col-body">%s</div>
                </div>
                """.formatted(Ui.esc(column.stage().label()), column.size(), cards);
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
                        + "\" style=\"width:auto\">" + entry.score().score() + "</span>";

        String link = interest.getUrl() == null || interest.getUrl().isBlank() ? ""
                : "<a href=\"" + Ui.esc(interest.getUrl())
                        + "\" target=\"_blank\" rel=\"noreferrer\">posting</a>";

        String note = interest.getNotes() == null || interest.getNotes().isBlank() ? ""
                : "<div class=\"note\">" + Ui.esc(interest.getNotes()) + "</div>";

        String due = entry.isDue(today)
                ? "<div class=\"due\">follow up · " + interest.getRemindOn() + "</div>" : "";

        // The note form is collapsed behind a <details>. Fifteen open textareas on
        // a board is not a board, and a note is written once and read often.
        return """
                <div class="jobcard">
                  <div class="c">%s</div>
                  <div class="r">%s</div>
                  %s%s
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
                        note, due, interest.getId(), options, score, link,
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
    public String move(@RequestParam Long interestId, @RequestParam String stage) {
        pipeline.move(interestId, PipelineStage.valueOf(stage));
        return "redirect:/board";
    }

    @PostMapping("/board/import")
    public String importFromTracker() {
        try {
            pipeline.importFromTracker();
        } catch (IOException e) {
            // The board still renders; a failed import is not worth a stack trace
            // in front of someone who clicked a button.
            return "redirect:/board";
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
        return "redirect:/";
    }
}
