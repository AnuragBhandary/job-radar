package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.AnswerBank;
import com.anuragbhandary.jobradar.apply.AnswerStore;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The questions that have stopped applications, answerable in place.
 *
 * <p>This loop existed and was open at both ends. {@code learn} printed a block
 * of YAML with the answers left blank; a person then had to find
 * {@code applicant.yml}, paste it in the right place, fill the blanks, and
 * restart. Every step is somewhere to stop, and stopping means the next form
 * blocks on the same question.
 *
 * <p>It is the highest-leverage screen in the tool for a reason that is easy to
 * miss: an answer is not worth one application. The match is a substring of the
 * question, so answering "do you need sponsorship" once answers it on every board
 * that asks, in whatever words they ask it. Two of the thirteen questions here
 * blocked a real form; the other eleven are waiting to.
 */
@Controller
public class AnswersController {

    private final ApplicationAttemptRepository attempts;
    private final AnswerStore answers;

    public AnswersController(ApplicationAttemptRepository attempts, AnswerStore answers) {
        this.attempts = attempts;
        this.answers = answers;
    }

    @GetMapping(value = "/answers", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String page(@RequestParam(required = false) String said) {
        List<AnswerBank.Suggestion> open = AnswerBank.suggest(attempts.findAll()).stream()
                // Something answered since the last render is no longer a question.
                .filter(suggestion -> !answers.answers(suggestion.key()))
                // Blocking first: these stopped a real application, the rest are
                // waiting to. Then by how often the question has come up.
                .sorted(Comparator.comparing(AnswerBank.Suggestion::required).reversed()
                        .thenComparing(Comparator.comparingInt(
                                AnswerBank.Suggestion::timesSeen).reversed()))
                .toList();

        StringBuilder body = new StringBuilder(Components.toast(said));
        body.append(explain(open));

        if (open.isEmpty()) {
            body.append(Components.empty(
                    "Nothing is unanswered.",
                    "Every question a form has asked so far has an answer in your profile. "
                            + "New ones appear here after a Prepare stops on something.",
                    "/jobs", "Prepare an application"));
        } else {
            open.forEach(suggestion -> body.append(card(suggestion)));
        }

        long blocking = open.stream().filter(AnswerBank.Suggestion::required).count();
        String stat = open.isEmpty() ? "<span class=\"note-muted\">all answered</span>"
                : "<strong>" + open.size() + "</strong> unanswered"
                        + (blocking > 0 ? "<span class=\"sep\">·</span><strong>" + blocking
                                + "</strong> blocking" : "");
        return Ui.page("Answers", stat, body.toString(), Ui.Tab.ANSWERS);
    }

    @PostMapping("/answers/save")
    public String save(@RequestParam String match, @RequestParam(required = false) String answer,
            RedirectAttributes flash) {
        try {
            flash.addAttribute("said", answers.remember(match, answer));
        } catch (IllegalArgumentException e) {
            flash.addAttribute("said", e.getMessage());
        }
        return "redirect:/answers";
    }

    // ------------------------------------------------------------------

    private String explain(List<AnswerBank.Suggestion> open) {
        if (open.isEmpty()) {
            return "";
        }
        return "<p class=\"readout\">Each answer here is worth more than one application. "
                + "The match is a substring of the question, so answering one covers every "
                + "board that asks the same thing in different words. Answers are written to "
                + "your profile and take effect on the next form, with no restart.</p>";
    }

    /**
     * One question, with somewhere to type the answer.
     *
     * <p>The options matter as much as the field. Where a form offered a radio or
     * a dropdown, the answer has to match one of its labels exactly or the filler
     * refuses it, so the choices are buttons that fill the box rather than a hint
     * to be retyped by hand.
     */
    private String card(AnswerBank.Suggestion suggestion) {
        String id = "a" + Math.abs(suggestion.key().hashCode());

        StringBuilder options = new StringBuilder();
        if (!suggestion.options().isEmpty()) {
            options.append("<div class=\"chips\">");
            suggestion.options().forEach(option -> options.append(
                    "<button type=\"button\" class=\"chip\" data-fill=\"").append(id)
                    .append("\" data-value=\"").append(Ui.esc(option)).append("\">")
                    .append(Ui.esc(option)).append("</button>"));
            options.append("</div>");
        }

        return """
                <section class="card panel qa%s">
                  <div class="panel-head">
                    <h2>%s</h2>
                    <span class="note-muted">%s</span>
                  </div>
                  <div class="panel-body">
                    <p class="qa-q">%s</p>
                    %s
                    <form method="post" action="/answers/save" class="qa-form">
                      <input type="hidden" name="match" value="%s">
                      <input class="input" id="%s" name="answer" autocomplete="off"
                             placeholder="%s">
                      <button class="btn btn-primary" type="submit"
                              data-busy="saving">Save answer</button>
                    </form>
                    <p class="check-why">Matched on <code>%s</code>, so any question
                    containing that phrase gets this answer.</p>
                  </div>
                </section>
                """.formatted(
                        suggestion.required() ? " qa-blocking" : "",
                        suggestion.required() ? "Blocked a form" : "Asked "
                                + suggestion.timesSeen() + (suggestion.timesSeen() == 1
                                        ? " time" : " times"),
                        suggestion.required() ? "seen " + suggestion.timesSeen() + "x" : "",
                        Ui.esc(suggestion.exampleLabel()),
                        options,
                        Ui.esc(suggestion.key()),
                        id,
                        suggestion.options().isEmpty() ? "Type the answer"
                                : "Pick one above, or type it",
                        Ui.esc(suggestion.key()));
    }
}
