package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.WorkMode;
import com.anuragbhandary.jobradar.knowledge.Assertion;
import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.Concepts;
import com.anuragbhandary.jobradar.knowledge.KnowledgeService;
import com.anuragbhandary.jobradar.knowledge.Scope;
import com.anuragbhandary.jobradar.strategy.CountryPolicy;
import com.anuragbhandary.jobradar.strategy.CountryStrategy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Reviewing knowledge that arrived without a scope.
 *
 * <p>The migration of the old {@code extra-answers} list deliberately refused to
 * guess where an answer applied. Ten of them are sitting inert as a result, four
 * of which are about sponsorship and work authorisation - exactly the answers
 * that were being applied globally before, and exactly the ones where a wrong
 * guess is an auto-reject on an employer's form.
 *
 * <p>This is the screen that lets him decide. It is not the finished knowledge
 * page and does not try to be: the point is that nothing stays trapped in the
 * database, and that choosing a scope is a decision with a date on it rather than
 * a default nobody saw.
 */
@Controller
public class KnowledgeController {

    private final KnowledgeService knowledge;
    private final CountryStrategy strategy;

    public KnowledgeController(KnowledgeService knowledge, CountryStrategy strategy) {
        this.knowledge = knowledge;
        this.strategy = strategy;
    }

    @GetMapping(value = "/knowledge", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String page(@RequestParam(required = false) String said) {
        List<Assertion> pending = knowledge.needingReview();
        List<Assertion> live = knowledge.live().stream().filter(Assertion::isUsable).toList();

        StringBuilder body = new StringBuilder(Components.toast(said));
        body.append(Parts.pageHead("Knowledge",
                "What Job Radar knows about you, where each answer applies, and where it "
                        + "came from. Nothing here is used on a form until it has a scope.",
                null));
        body.append(needsReview(pending));
        body.append(inUse(live));

        String stat = pending.isEmpty()
                ? "<span class=\"note-muted\">all reviewed</span>"
                : "<strong>" + pending.size() + "</strong> awaiting a scope";
        return Ui.page("Knowledge", stat, body.toString(), Ui.Tab.KNOWLEDGE);
    }

    /**
     * The answers that arrived without a scope, and are switched off until they
     * have one.
     *
     * <p>First on the page because they are the only part of it that is a task.
     * Everything below is a record; this is a decision, and four of these are
     * about sponsorship and work authorisation - the answers where a wrong guess
     * is an auto-reject on an employer's form.
     */
    private String needsReview(List<Assertion> pending) {
        if (pending.isEmpty()) {
            return Parts.block("Needs review", "0", Parts.blank(
                    "Nothing is waiting on a decision.",
                    "Every answer Job Radar has imported either applies everywhere safely "
                            + "or has been given a scope you chose.",
                    Parts.linkButton("/answers", "See open questions", "btn")));
        }
        StringBuilder cards = new StringBuilder();
        cards.append("<p class=\"block-note\">These came from the old flat answer list, "
                + "where every entry applied everywhere because there was nowhere to say "
                + "otherwise. The ones below depend on something that list never recorded - "
                + "usually which country - so they were imported switched off rather than "
                + "guessed at. Until you choose, they answer nothing.</p>");
        pending.forEach(assertion -> cards.append(card(assertion)));
        return Parts.block("Needs review", String.valueOf(pending.size()),
                Parts.state(Parts.Tone.BAD, "Answering nothing"), cards.toString());
    }

    /**
     * What is actually in use, grouped by how far it reaches.
     *
     * <p>The grouping is the point: an answer that applies everywhere and an
     * answer that applies to one German application are different kinds of thing,
     * and a flat list of both was what the old {@code extra-answers} file was.
     * Sorting by scope makes the reach visible without reading every row.
     */
    private String inUse(List<Assertion> live) {
        if (live.isEmpty()) {
            return "";
        }
        Map<Scope.Level, List<Assertion>> byLevel = new LinkedHashMap<>();
        for (Scope.Level level : Scope.Level.values()) {
            byLevel.put(level, new java.util.ArrayList<>());
        }
        live.forEach(assertion -> byLevel.get(assertion.scope().level()).add(assertion));

        StringBuilder groups = new StringBuilder();
        for (Map.Entry<Scope.Level, List<Assertion>> entry : byLevel.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            StringBuilder rows = new StringBuilder("<div class=\"know-list\">");
            entry.getValue().forEach(assertion -> rows.append(knownRow(assertion)));
            rows.append("</div>");
            groups.append("""
                    <details class="fgroup"%s>
                      <summary>
                        <span class="fgroup-name">%s</span>
                        <span class="fgroup-n">%d</span>
                        <span class="fgroup-note">%s</span>
                      </summary>
                      %s
                    </details>
                    """.formatted(entry.getKey() == Scope.Level.GLOBAL ? " open" : "",
                            Ui.esc(reach(entry.getKey())), entry.getValue().size(),
                            Ui.esc(reachNote(entry.getKey())), rows));
        }
        return Parts.block("In use", String.valueOf(live.size()),
                Parts.state(Parts.Tone.OK, "Answering forms"), groups.toString());
    }

    /**
     * One thing Job Radar knows.
     *
     * <p>Five facts on one line: what it is about, what it says, where it
     * applies, where it came from and how sure it is. The concept id is shown
     * small rather than as the heading - it is the storage key, and the question
     * it answers is what a person recognises.
     */
    private String knownRow(Assertion assertion) {
        Optional<Concept> concept = Concepts.byId(assertion.getConceptId());
        return """
                <div class="know">
                  <span class="kn-q">%s<span class="kn-id">%s</span></span>
                  <span class="kn-a">%s</span>
                  <span class="kn-side">%s%s</span>
                </div>
                """.formatted(
                        Ui.esc(concept.map(Concept::label).orElse(assertion.getConceptId())),
                        Ui.esc(assertion.getConceptId()),
                        Ui.esc(abbreviate(assertion.getValue())),
                        Parts.tag(assertion.scope().describe()),
                        Parts.state(confidenceTone(assertion), sourceLabel(assertion)));
    }

    private static String reach(Scope.Level level) {
        return switch (level) {
            case GLOBAL -> "Everywhere";
            case STRATEGIC_CLASS -> "One kind of opportunity";
            case WORK_MODE -> "One work arrangement";
            case COUNTRY -> "One country";
            case COMPANY -> "One company";
            case APPLICATION -> "One application";
        };
    }

    private static String reachNote(Scope.Level level) {
        return switch (level) {
            case GLOBAL -> "facts that do not change with the employer or the country";
            case COUNTRY -> "sponsorship, work authorisation, pay - wrong for one country "
                    + "if answered for another";
            case COMPANY -> "answers about one employer";
            default -> "";
        };
    }

    private static String sourceLabel(Assertion assertion) {
        return switch (assertion.getSource()) {
            case USER_RULE -> "A rule you approved";
            case USER_INPUT -> "You answered this";
            case PROFILE -> "Your profile";
            case RESUME -> "Your resume";
            case DERIVED -> "Worked out";
            case HISTORICAL -> "An earlier application";
            case AI_PROPOSED -> "Drafted, approved by you";
            case SESSION -> "This session";
        };
    }

    private static Parts.Tone confidenceTone(Assertion assertion) {
        if (assertion.getConfidence() == null) {
            return Parts.Tone.QUIET;
        }
        return switch (assertion.getConfidence()) {
            case HIGH -> Parts.Tone.OK;
            case MEDIUM -> Parts.Tone.WARN;
            case LOW -> Parts.Tone.QUIET;
        };
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------

    /**
     * @param params every value picker on the card, one per scope level.
     *
     *               <p>Named per level - {@code value_COUNTRY}, {@code value_COMPANY}
     *               - rather than sharing one name and being hidden by script.
     *               They all submit whether they are on screen or not, so a
     *               single {@code value} parameter arrived as
     *               "nl,,onsite,india_home" and was stored as the scope. The
     *               server picking the one that matches the chosen level does not
     *               depend on any JavaScript having run.
     */
    @PostMapping("/knowledge/scope")
    public String setScope(@RequestParam Long id, @RequestParam String level,
            @RequestParam(required = false) String newValue,
            @RequestParam java.util.Map<String, String> params,
            RedirectAttributes flash) {
        try {
            Scope scope = Scope.of(Scope.Level.valueOf(level), params.get("value_" + level));
            Assertion saved = newValue == null || newValue.isBlank()
                    ? knowledge.rescope(id, scope, "knowledge review")
                    : knowledge.edit(id, newValue.trim(), scope, "knowledge review");
            flash.addAttribute("said", "Saved. '" + abbreviate(saved.getValue())
                    + "' now applies to " + scope.describe() + " and nothing else.");
        } catch (IllegalArgumentException e) {
            // The server is the boundary. A client sending GLOBAL for a
            // context-sensitive concept is refused rather than quietly narrowed,
            // because quietly narrowing hides the bug that sent it.
            flash.addAttribute("said", "Refused: " + e.getMessage());
        }
        return "redirect:/knowledge";
    }

    @PostMapping("/knowledge/discard")
    public String discard(@RequestParam Long id, RedirectAttributes flash) {
        knowledge.discard(id, "knowledge review");
        flash.addAttribute("said", "Discarded. It is kept in the record and will not be used.");
        return "redirect:/knowledge";
    }

    @PostMapping("/knowledge/keep")
    public String keep(@RequestParam Long id, RedirectAttributes flash) {
        knowledge.keepPending(id);
        flash.addAttribute("said",
                "Left pending. It answers nothing until you give it a scope.");
        return "redirect:/knowledge";
    }

    // ------------------------------------------------------------------

    /**
     * One pending answer, with somewhere to put it.
     *
     * <p>Shows the original wording as well as the concept. The concept is the
     * abstraction and the wording is the evidence: "do you have eu citizenship
     * or" reads as a sponsorship question and was written for one Dutch form, and
     * seeing that is what makes the country obvious.
     */
    private String card(Assertion assertion) {
        Optional<Concept> concept = Concepts.byId(assertion.getConceptId());
        String title = concept.map(Concept::label).orElse(assertion.getConceptId());
        boolean contextSensitive = concept.map(Concept::contextSensitive).orElse(true);

        return """
                <article class="field-card edge-%s">
                  <p class="field-q">%s<span class="kn-id">%s</span></p>
                  <p class="kn-was">%s</p>
                  <p class="field-a">%s</p>
                  <p class="why-line">%s</p>
                  <form method="post" action="/knowledge/scope" class="field-answer">
                    <input type="hidden" name="id" value="%d">
                    <fieldset class="scope-pick">
                      <legend>Where does this answer apply?</legend>
                      %s
                    </fieldset>
                    <input class="input" name="newValue" autocomplete="off"
                           aria-label="Replace the answer"
                           placeholder="Leave blank to keep this answer, or type a new one">
                    <div class="field-actions">
                      <button class="btn btn-primary" type="submit"
                              data-busy="saving">Save scope</button>
                      %s
                      %s
                    </div>
                  </form>
                </article>
                """.formatted(
                        contextSensitive ? "bad" : "warn",
                        Ui.esc(title),
                        Ui.esc(assertion.getConceptId()),
                        Ui.esc(assertion.getSourceQuestion() == null
                                ? "No original wording was recorded."
                                : "Originally matched on: \u201c"
                                        + assertion.getSourceQuestion() + "\u201d"),
                        Ui.esc(assertion.getValue()),
                        Ui.esc(contextSensitive
                                ? "This answer changes with the country, the work mode or "
                                        + "the employer, so it cannot be saved everywhere. "
                                        + "Choose where it applies."
                                : assertion.getNote() == null
                                        ? "Imported without a scope."
                                        : assertion.getNote()),
                        assertion.getId(),
                        scopePicker(concept.orElse(null)),
                        postButton("/knowledge/keep", assertion.getId(), "Keep pending"),
                        postButton("/knowledge/discard", assertion.getId(), "Discard"));
    }

    /**
     * The scopes this concept may legitimately be stored at.
     *
     * <p>Built from {@link Concept#allowsScope}, so a context-sensitive concept
     * simply has no global option to click. The server refuses one anyway - the
     * UI is not the boundary - but an interface that offers a choice and then
     * rejects it is an interface that has lied.
     */
    private String scopePicker(Concept concept) {
        StringBuilder out = new StringBuilder("<select class=\"input\" name=\"level\" "
                + "onchange=\"this.form.querySelectorAll('[data-scope]').forEach("
                + "function(s){s.hidden = s.dataset.scope !== this.value;}.bind(this));\">");
        StringBuilder values = new StringBuilder();

        for (Scope.Level level : offeredLevels(concept)) {
            out.append("<option value=\"").append(level).append("\">")
                    .append(label(level)).append("</option>");
            values.append(valuePicker(level));
        }
        // The first level's value picker starts visible; the script swaps them.
        String rendered = values.toString();
        List<Scope.Level> levels = offeredLevels(concept);
        if (!levels.isEmpty()) {
            rendered = rendered.replaceFirst(
                    "<span data-scope=\"" + levels.getFirst() + "\" hidden>",
                    "<span data-scope=\"" + levels.getFirst() + "\">");
        }
        return out.append("</select>").append(rendered).toString();
    }

    /**
     * The levels to offer, safest first.
     *
     * <p>The concept's own default leads, so a sponsorship rule opens on "one
     * country" rather than on whichever enum constant happens to be declared
     * first. A picker whose first option is the wrong one is a picker that will
     * be accepted unread.
     */
    private static List<Scope.Level> offeredLevels(Concept concept) {
        List<Scope.Level> levels = new java.util.ArrayList<>();
        if (concept != null && concept.allowsScope(concept.defaultScope())
                && concept.defaultScope() != Scope.Level.APPLICATION) {
            levels.add(concept.defaultScope());
        }
        for (Scope.Level level : Scope.Level.values()) {
            // A migrated rule belongs to no single application, so that level is
            // never offered here even where the concept allows it.
            if (level == Scope.Level.APPLICATION || levels.contains(level)) {
                continue;
            }
            if (concept == null || concept.allowsScope(level)) {
                levels.add(level);
            }
        }
        return levels;
    }

    private String valuePicker(Scope.Level level) {
        String open = "<span data-scope=\"" + level + "\" hidden>";
        String name = "value_" + level;
        return switch (level) {
            // Ordered by relocation tier, then by name. The list arrived in
            // configuration order, so the option pre-selected for a sponsorship
            // answer was whichever country happened to be written first - and a
            // dropdown whose default is arbitrary is a dropdown that gets saved
            // unread.
            case COUNTRY -> open + "<select class=\"input\" name=\"" + name + "\">"
                    + strategy.policies().stream()
                            .sorted(java.util.Comparator
                                    .comparingInt((CountryPolicy policy) ->
                                            policy.relocationTier().rank())
                                    .thenComparing(CountryPolicy::displayName,
                                            String.CASE_INSENSITIVE_ORDER))
                            .map(CountryPolicy::countryCode)
                            .map(code -> "<option value=\"" + code + "\">"
                                    + Ui.esc(CountryCodes.displayName(code)) + "</option>")
                            .reduce("", String::concat)
                    + "</select></span>";
            case STRATEGIC_CLASS -> open + "<select class=\"input\" name=\"" + name + "\">"
                    + java.util.Arrays.stream(StrategicClass.values())
                            .map(lane -> "<option value=\"" + lane + "\">"
                                    + Ui.esc(readable(lane.name())) + "</option>")
                            .reduce("", String::concat)
                    + "</select></span>";
            case WORK_MODE -> open + "<select class=\"input\" name=\"" + name + "\">"
                    + java.util.Arrays.stream(WorkMode.values())
                            .map(mode -> "<option value=\"" + mode + "\">"
                                    + Ui.esc(readable(mode.name())) + "</option>")
                            .reduce("", String::concat)
                    + "</select></span>";
            case COMPANY -> open + "<input class=\"input\" name=\"" + name + "\" "
                    + "placeholder=\"Company name\"></span>";
            case GLOBAL, APPLICATION -> "";
        };
    }

    private static String postButton(String action, Long id, String label) {
        return """
                <form method="post" action="%s" style="display:inline">
                  <input type="hidden" name="id" value="%d">
                  <button class="chip" type="submit">%s</button>
                </form>
                """.formatted(action, id, label);
    }

    private static String label(Scope.Level level) {
        return switch (level) {
            case COUNTRY -> "One country";
            case STRATEGIC_CLASS -> "One kind of opportunity";
            case WORK_MODE -> "One work arrangement";
            case COMPANY -> "One company";
            case GLOBAL -> "Everywhere";
            case APPLICATION -> "This application only";
        };
    }

    private static String readable(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "(none)";
        }
        return value.length() <= 80 ? value : value.substring(0, 77) + "...";
    }
}
