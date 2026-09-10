package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.AttemptStatus;
import com.anuragbhandary.jobradar.apply.ScopeOption;
import com.anuragbhandary.jobradar.web.Parts.Tone;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Application Preparation screen.
 *
 * <h2>What changed, and why</h2>
 * The first version of this screen followed the order the brief listed its
 * sections in: progress, resume, every field, then the things needing a decision.
 * Rendered against a real eighteen-field form that put the three items needing
 * him <em>1,270 pixels below</em> a table of the fifteen that did not - so the
 * page opened on a wall of green and the only question it existed to answer was
 * off the bottom of the screen.
 *
 * <p>So the order is now by who has to act:
 *
 * <ol>
 *   <li>what this application is, and how far along it is;</li>
 *   <li>what needs him - questions, then drafts, then the website's problems;</li>
 *   <li>one primary action;</li>
 *   <li>what is already done, grouped and collapsed;</li>
 *   <li>supporting detail.</li>
 * </ol>
 *
 * <h2>Two shapes for a field</h2>
 * A settled field is a line of text. A field needing a decision is a card with a
 * surface, a heading and one button. The difference in weight <em>is</em> the
 * information - and drawing both as table rows, which is what the first version
 * did, meant reading all thirty to find the one.
 *
 * <p>Rendering only. Which actions a field allows, which scopes an answer may be
 * saved at and what the counts add up to were all decided on the server; a page
 * that decides for itself is one release away from offering an action the server
 * refuses.
 */
final class PreparationPage {

    /** The applicant's five steps, which are not the pipeline's nine stages. */
    private static final List<String> STEPS =
            List.of("Resume", "Application", "Questions", "Review", "Open");

    private PreparationPage() {
    }

    static String render(ApplicationAttempt attempt, PreparationView view,
            List<FieldView> fields, Map<Long, List<ScopeOption>> scopes, String flash) {

        StringBuilder body = new StringBuilder();
        body.append(Components.toast(flash));
        body.append("<p class=\"crumbs\"><a href=\"/jobs\">Jobs</a> · ")
                .append(Ui.esc(attempt.getCompany())).append("</p>");

        header(body, attempt, view);
        alert(body, view);
        needsYou(body, view, fields, scopes);
        review(body, attempt, view);
        prepared(body, fields);
        resume(body, attempt);
        screenshot(body, attempt, view);

        String stat = attempt.getStatus() == AttemptStatus.SUBMITTED
                ? "<strong>sent</strong>"
                : "<span class=\"note-muted\">nothing sent yet</span>";
        return Ui.page(attempt.getCompany() + " · " + attempt.getRole(), stat,
                body.toString(), Ui.Tab.JOBS, "/prep.js");
    }

    // ------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------

    /**
     * Everything about where this stands, above the fold.
     *
     * <p>Role above company, because he is choosing between roles and already
     * knows which companies he applied to. Then the four facts that decide
     * whether the answers below are right - where, how, which lane - and a bar
     * whose segments are the buckets rather than one percentage that hides which
     * of them is which.
     */
    private static void header(StringBuilder body, ApplicationAttempt attempt,
            PreparationView view) {

        PreparationView.ReadinessView ready = view.readiness();
        StringBuilder facts = new StringBuilder();
        fact(facts, view.location());
        fact(facts, view.country());
        fact(facts, view.workMode());
        fact(facts, view.strategicClass());

        String summary = ready.total() == 0
                ? "No fields recorded yet."
                : ready.prepared() + " handled automatically"
                        + part(ready.awaitingApproval(), "draft", "to approve")
                        + part(ready.awaitingAnswer(), "question", "for you")
                        + part(ready.automationBlocked(), "field", "for the website")
                        + part(ready.conflicts(), "conflict", "");

        body.append("""
                <header class="page-head prep-head">
                  <div>
                    <h1>%s</h1>
                    <p class="prep-company">%s</p>
                    <div class="prep-facts">%s</div>
                  </div>
                  <div class="page-actions">%s</div>
                </header>
                <section class="surface surface-pad prep-summary">
                  <div class="prep-ready">
                    <span class="prep-pct">%s</span>
                    <span class="prep-count">%s</span>
                  </div>
                  %s
                  %s
                </section>
                """.formatted(
                        Ui.esc(attempt.getRole()),
                        Ui.esc(attempt.getCompany()),
                        facts,
                        Parts.state(statusTone(attempt.getStatus()), view.statusLabel()),
                        percent(ready),
                        Ui.esc(summary),
                        Parts.readyBar(ready.prepared(), ready.awaitingApproval(),
                                ready.awaitingAnswer(), ready.automationBlocked(),
                                ready.skippedOptional() + ready.declined()),
                        stepper(view)));
    }

    /**
     * How far along, as a whole number.
     *
     * <p>Prepared over total, and nothing cleverer. There is no weighting by
     * effort and no estimate of time left, because both would be invented - and a
     * number a person cannot check is worse on this screen than no number.
     */
    private static String percent(PreparationView.ReadinessView ready) {
        if (ready.total() == 0) {
            return "—";
        }
        return Math.round(ready.prepared() * 100.0 / ready.total()) + "% prepared";
    }

    private static String part(int count, String noun, String tail) {
        if (count == 0) {
            return "";
        }
        return " · " + count + " " + noun + (count == 1 ? "" : "s")
                + (tail.isBlank() ? "" : " " + tail);
    }

    private static void fact(StringBuilder facts, String value) {
        if (value != null && !value.isBlank()) {
            facts.append("<span>").append(Ui.esc(value)).append("</span>");
        }
    }

    /**
     * The five steps of an application, in his language.
     *
     * <p>Not the nine internal stages. "Reading the form" and "Working out the
     * answers" are things the tool does in the same four seconds and are one step
     * to the person waiting; what he actually tracks is whether the resume is
     * ready, whether the form got filled, whether anything is still asked of him,
     * and whether it is his turn.
     */
    private static String stepper(PreparationView view) {
        PreparationView.ReadinessView ready = view.readiness();
        int at;
        int blocked = -1;
        if (view.running()) {
            at = view.step() >= 5 ? 1 : 0;
        } else if (ready.total() == 0) {
            at = 1;
        } else if (ready.awaitingAnswer() > 0 || ready.awaitingApproval() > 0
                || ready.conflicts() > 0) {
            at = 2;
        } else if (view.manualReason() != null || ready.automationBlocked() > 0) {
            at = 3;
            blocked = 4;
        } else {
            at = 3;
        }
        if (view.status().equals("SUBMITTED")) {
            at = STEPS.size();
        }
        return Parts.stepper(STEPS, at, Tone.MANUAL, blocked);
    }

    // ------------------------------------------------------------------
    // The attempt-level alert
    // ------------------------------------------------------------------

    /**
     * The website's problem, or a run that threw. Never both, never conflated.
     *
     * <p>Amber for a board that wants a person, red for something that actually
     * broke. That distinction is the one this whole phase of the project exists
     * to make, and using one colour for both is how "your application failed"
     * came to mean "there was a captcha".
     */
    private static void alert(StringBuilder body, PreparationView view) {
        if (view.error() != null && !view.error().isBlank()) {
            body.append("""
                    <section class="surface surface-pad alertbox edge-bad">
                      <div class="alert-head">%s<h2>Preparation stopped</h2></div>
                      <p class="alert-what">%s</p>
                      <p class="alert-note">Nothing was sent. Your resume is saved, and
                      starting again costs only the time.</p>
                      %s
                    </section>
                    """.formatted(Parts.state(Tone.BAD, "Failed"), Ui.esc(view.error()),
                            Parts.post("/attempt/" + view.attemptId() + "/resume",
                                    "Try again", "btn", null)));
            return;
        }
        if (view.manualReason() == null) {
            return;
        }
        body.append("""
                <section class="surface surface-pad alertbox edge-manual">
                  <div class="alert-head">%s<h2>%s</h2></div>
                  <p class="alert-what">%s</p>
                  <p class="alert-note">%s</p>
                </section>
                """.formatted(
                        Parts.state(Tone.MANUAL, "Manual action required"),
                        Ui.esc(view.manualExplanation()),
                        Ui.esc(view.manualDetail()),
                        view.valuesReusable()
                                ? "Job Radar prepared everything it safely could. "
                                        + view.readiness().prepared()
                                        + " fields are ready and go back into the form when "
                                        + "you open it below."
                                : "Finish this one on the board itself."));
    }

    // ------------------------------------------------------------------
    // What needs him
    // ------------------------------------------------------------------

    /**
     * Everything waiting on a decision, hardest first.
     *
     * <p>Questions before drafts before the website, because answering a question
     * is the only one of the three that teaches Job Radar something every future
     * form will use - and because a draft can be approved in two seconds once the
     * page is already open.
     */
    private static void needsYou(StringBuilder body, PreparationView view,
            List<FieldView> fields, Map<Long, List<ScopeOption>> scopes) {

        List<FieldView> questions = by(fields, "AWAITING_ANSWER");
        List<FieldView> drafts = by(fields, "AWAITING_APPROVAL");
        List<FieldView> conflicts = fields.stream().filter(FieldView::conflict).toList();
        List<FieldView> blocked = fields.stream()
                .filter(f -> "AUTOMATION_BLOCKED".equals(f.outcome())).toList();

        if (questions.isEmpty() && drafts.isEmpty() && conflicts.isEmpty()
                && blocked.isEmpty()) {
            return;
        }
        body.append("<div id=\"needs-you\">");

        if (!questions.isEmpty()) {
            StringBuilder cards = new StringBuilder();
            questions.forEach(field -> question(cards, view.attemptId(), field,
                    scopes.getOrDefault(field.id(), List.of())));
            body.append(Parts.block("We need your input", String.valueOf(questions.size()),
                    Parts.state(Tone.BAD, "Action required"),
                    "<p class=\"block-note\">Job Radar could not find reliable evidence for "
                            + "these. Answering one unblocks this application - and if you "
                            + "save it, the next form asking the same thing will not have "
                            + "to ask.</p>" + cards));
        }

        if (!drafts.isEmpty()) {
            StringBuilder cards = new StringBuilder();
            drafts.forEach(field -> draft(cards, view.attemptId(), field));
            body.append(Parts.block("Suggested answers", String.valueOf(drafts.size()),
                    Parts.state(Tone.WARN, "Your approval required"),
                    "<p class=\"block-note\">Drafted from your own material. Nothing written "
                            + "by the assistant is sent until you approve it - read each one, "
                            + "it goes to an employer under your name.</p>" + cards));
        }

        if (!conflicts.isEmpty()) {
            StringBuilder cards = new StringBuilder();
            conflicts.forEach(field -> conflict(cards, field));
            body.append(Parts.block("Conflicting saved answers",
                    String.valueOf(conflicts.size()),
                    Parts.state(Tone.BAD, "Nothing chosen"),
                    "<p class=\"block-note\">Two saved answers apply here equally and "
                            + "disagree. Job Radar has not picked between them.</p>" + cards));
        }

        if (!blocked.isEmpty()) {
            StringBuilder cards = new StringBuilder();
            blocked.forEach(field -> blockedField(cards, field));
            body.append(Parts.block("The website would not fill these",
                    String.valueOf(blocked.size()),
                    Parts.state(Tone.MANUAL, "Copy these across"),
                    "<p class=\"block-note\">Job Radar knows the answer. The page would not "
                            + "let the browser enter it, so these are yours to type - the "
                            + "values are here to copy.</p>" + cards));
        }
        body.append("</div>");
    }

    /**
     * A question with no answer, as a task rather than a form.
     *
     * <p>The scope choice is on the same card and defaults to not remembering.
     * Answering has to be possible in one decision; choosing where the answer
     * applies is a second, optional one, and making it compulsory would turn
     * every unknown question into a configuration exercise.
     */
    private static void question(StringBuilder body, long attemptId, FieldView field,
            List<ScopeOption> scopes) {

        body.append("<article class=\"field-card edge-bad\" data-field=\"")
                .append(field.id()).append("\">")
                .append(questionLine(field))
                .append("<p class=\"field-none\">Job Radar could not find a reliable "
                        + "answer.</p>")
                .append(explanation(field))
                .append(whyPanel(field))
                .append(answerForm(attemptId, field, scopes))
                .append("<div class=\"field-actions\">").append(whyButton(field))
                .append("</div></article>");
    }

    private static String answerForm(long attemptId, FieldView field,
            List<ScopeOption> scopes) {

        StringBuilder input = new StringBuilder();
        if (!field.options().isEmpty()) {
            input.append("<select class=\"input\" name=\"value\" aria-label=\"Your answer\">");
            field.options().forEach(option -> input.append("<option value=\"")
                    .append(Ui.esc(option)).append("\">").append(Ui.esc(option))
                    .append("</option>"));
            input.append("</select>");
        } else if ("TEXTAREA".equals(field.controlType())) {
            input.append("<textarea class=\"input\" name=\"value\" rows=\"4\" ")
                    .append("aria-label=\"Your answer\" placeholder=\"Your answer\">")
                    .append("</textarea>");
        } else {
            input.append("<input class=\"input\" name=\"value\" autocomplete=\"off\" ")
                    .append("aria-label=\"Your answer\" type=\"")
                    .append("DATE".equals(field.controlType()) ? "date" : "text")
                    .append("\" placeholder=\"Your answer\">");
        }

        StringBuilder picker = new StringBuilder();
        if (!scopes.isEmpty()) {
            picker.append("<fieldset class=\"scopes\">")
                    .append("<legend>Save it for future applications?</legend>")
                    .append("""
                            <label class="scope"><input type="radio" name="scopeLevel" value=""
                                   checked><span><strong>Just this once</strong>
                            <span class="scope-say">Use it here and do not remember it.</span>
                            </span></label>
                            """);
            for (ScopeOption scope : scopes) {
                picker.append("""
                        <label class="scope">
                          <input type="radio" name="scopeLevel" value="%s">
                          <span><strong>%s</strong>
                          <span class="scope-say">%s</span></span>
                        </label>
                        """.formatted(Ui.esc(scope.level()), Ui.esc(scope.label()),
                                Ui.esc(scope.sentence())));
            }
            picker.append("</fieldset>");
        }

        return """
                <form method="post" action="/attempt/%d/act" class="field-answer">
                  %s%s<input type="hidden" name="action" value="answer">
                  %s
                  %s
                  <button class="btn btn-primary" type="submit" data-busy="saving">
                    Save answer</button>
                </form>
                """.formatted(attemptId, Parts.hidden("fieldId", field.id()),
                        Parts.hidden("version", field.version()), input, picker);
    }

    /**
     * A drafted answer.
     *
     * <p>Quoted rather than stated: its own inset surface with a rule down the
     * side, labelled before the prose starts. He has to know who wrote it while
     * he is reading it, not after - a well-written paragraph presented as an
     * answer stops looking like a suggestion by the second sentence.
     */
    private static void draft(StringBuilder body, long attemptId, FieldView field) {
        String hidden = Parts.hidden("fieldId", field.id())
                + Parts.hidden("version", field.version())
                + Parts.hidden("assertionId", field.pendingAssertionId() == null
                        ? "" : field.pendingAssertionId().toString());

        body.append("<article class=\"field-card edge-warn\" data-field=\"")
                .append(field.id()).append("\">")
                .append(questionLine(field))
                .append("<div class=\"draft\"><p class=\"draft-from\">")
                .append(Parts.state(Tone.WARN, "Suggested answer"))
                .append(" · review before using</p>")
                .append(paragraphs(field.answer()))
                .append("</div>")
                .append(evidence(field, "Based on"))
                .append(whyPanel(field))
                .append("<div class=\"field-actions\">")
                .append(Parts.post("/attempt/" + attemptId + "/act", "Approve",
                        "btn btn-primary", hidden + Parts.hidden("action", "approve")))
                .append("<button class=\"btn btn-sm\" type=\"button\" data-edit=\"")
                .append(field.id()).append("\">Edit</button>");
        if (field.actions().contains("regenerate")) {
            body.append(Parts.post("/attempt/" + attemptId + "/act", "Regenerate", "btn btn-sm",
                    hidden + Parts.hidden("action", "regenerate")));
        }
        body.append(Parts.post("/attempt/" + attemptId + "/act", "Reject", "btn btn-sm btn-quiet",
                        hidden + Parts.hidden("action", "reject")))
                .append(whyButton(field))
                .append("</div>");

        // Editing is the approve action with a value on it: one decision,
        // recorded once, with userEdited set on the assertion it approved.
        body.append("""
                <form method="post" action="/attempt/%d/act" class="field-edit"
                      id="edit-%d" hidden>
                  %s<input type="hidden" name="action" value="approve">
                  <textarea class="input" name="value" rows="6"
                            aria-label="Your version of this answer">%s</textarea>
                  <button class="btn btn-primary" type="submit" data-busy="saving">
                    Save and approve</button>
                </form>
                """.formatted(attemptId, field.id(), hidden, Ui.esc(field.answer())));
        body.append("</article>");
    }

    private static void conflict(StringBuilder body, FieldView field) {
        body.append("<article class=\"field-card edge-bad\" data-field=\"")
                .append(field.id()).append("\">")
                .append(questionLine(field))
                .append(whyPanel(field))
                .append("<div class=\"field-actions\">")
                .append(whyButton(field))
                .append(Parts.linkButton("/knowledge", "Resolve in Knowledge", "btn btn-sm"))
                .append("</div></article>");
    }

    /**
     * Known, and the page would not take it.
     *
     * <p>The heading says the website could not fill it, never that the answer is
     * missing - and the value is shown at full size, because the job this card
     * exists for is copying it into a browser.
     */
    private static void blockedField(StringBuilder body, FieldView field) {
        body.append("<article class=\"field-card edge-manual\" data-field=\"")
                .append(field.id()).append("\">")
                .append(questionLine(field))
                .append("<p class=\"field-label-sm\">Known answer</p>")
                .append("<p class=\"field-a copyable\">").append(Ui.esc(field.answer()))
                .append("</p>")
                .append("<p class=\"field-label-sm\">Why the browser could not enter it</p>")
                .append("<p class=\"why-line\">")
                .append(Ui.esc(field.failureReason() == null
                        ? "the control could not be driven safely" : field.failureReason()))
                .append("</p>")
                .append(whyPanel(field))
                .append("<div class=\"field-actions\">").append(whyButton(field))
                .append("</div></article>");
    }

    // ------------------------------------------------------------------
    // The single call to action
    // ------------------------------------------------------------------

    /**
     * One primary action, whatever the state.
     *
     * <p>There used to be three "Open application" buttons on this page - in the
     * manual box, under the blocked fields, and here - which is three chances to
     * press the one that is wrong for what he actually wants. Now the sections
     * above explain and this decides: resolve what is outstanding, or open the
     * form.
     */
    private static void review(StringBuilder body, ApplicationAttempt attempt,
            PreparationView view) {

        PreparationView.ReadinessView ready = view.readiness();
        if (ready.total() == 0 && !view.running()) {
            return;
        }
        boolean blocking = ready.awaitingAnswer() > 0 || ready.awaitingApproval() > 0
                || ready.conflicts() > 0;

        StringBuilder counts = new StringBuilder("<ul class=\"readiness\">");
        count(counts, ready.prepared(), "field", "prepared", Tone.OK);
        count(counts, ready.awaitingApproval(), "suggested answer", "to approve", Tone.WARN);
        count(counts, ready.awaitingAnswer(), "question", "for you", Tone.BAD);
        count(counts, ready.automationBlocked(), "field", "for you to type", Tone.MANUAL);
        count(counts, ready.conflicts(), "conflict", "unresolved", Tone.BAD);
        count(counts, ready.skippedOptional(), "optional field", "left blank", Tone.QUIET);
        count(counts, ready.declined(), "field", "declined on purpose", Tone.QUIET);
        counts.append("</ul>");

        String cta;
        String note;
        if (view.running()) {
            cta = "<span class=\"note-muted\">Preparation is still running.</span>";
            note = "";
        } else if (blocking) {
            // Only what he can actually act on from this page. A blocked control
            // is on the blockers list because it stops a submission, and counting
            // it here would promise a fix this button cannot deliver.
            int mine = ready.awaitingAnswer() + ready.awaitingApproval()
                    + ready.conflicts();
            cta = Parts.linkButton("#needs-you",
                    "Resolve " + mine + (mine == 1 ? " item" : " items"),
                    "btn btn-primary btn-lg");
            note = "The form can be opened once nothing is waiting on you.";
        } else if (!view.canOpen()) {
            cta = "";
            note = "Answer the questions above first - opening now would put the same "
                    + "blank back into the form.";
        } else {
            cta = Parts.post("/attempt/" + attempt.getId() + "/open", "Open application",
                    "btn btn-primary btn-lg", null);
            note = "This re-fills the form in a browser and leaves it on screen. It does "
                    + "not submit - nothing here does. Read it and press the board's own "
                    + "button yourself.";
        }

        body.append("""
                <section class="surface surface-pad review-block %s" id="review">
                  <div class="review-top">
                    <div>
                      <h2>%s</h2>
                      <p class="review-line">%s</p>
                    </div>
                    <div class="review-cta">%s</div>
                  </div>
                  %s
                  %s
                </section>
                """.formatted(blocking ? "edge-bad" : "edge-ok",
                        blocking ? "Action required" : "Ready to continue",
                        blocking ? Ui.esc(String.join(" · ", ready.blockers()))
                                : Ui.esc(percent(ready) + " · nothing is waiting on you"),
                        cta, counts,
                        note.isBlank() ? "" : "<p class=\"caption\">" + Ui.esc(note) + "</p>"));

        if (attempt.getStatus().isReadyToSend()) {
            body.append(submitBlock(attempt));
        }
    }

    private static void count(StringBuilder out, int number, String noun, String tail,
            Tone tone) {
        if (number == 0) {
            return;
        }
        out.append("<li><span class=\"state state-").append(tone.css()).append("\"></span>")
                .append("<strong>").append(number).append("</strong> ")
                .append(Ui.esc(noun)).append(number == 1 ? "" : "s").append(' ')
                .append(Ui.esc(tail)).append("</li>");
    }

    private static String submitBlock(ApplicationAttempt attempt) {
        return """
                <section class="surface surface-pad submit-block">
                  <h2>Submit</h2>
                  <p class="submit-note">This re-opens the form and fills it again before
                  submitting, because the posting may have changed since it was prepared.
                  Most boards accept one application per posting, ever.</p>
                  <form method="post" action="/submit">
                    <input type="hidden" name="attemptId" value="%d">
                    <label class="confirm">
                      <input type="checkbox" name="read" onchange="go.disabled=!checked">
                      <span>I have read the filled form, including the derived answers and
                      any drafted text.</span>
                    </label>
                    <button id="go" class="btn btn-primary" disabled>Submit to %s</button>
                  </form>
                </section>
                """.formatted(attempt.getId(), Ui.esc(attempt.getCompany()));
    }

    // ------------------------------------------------------------------
    // What is already done
    // ------------------------------------------------------------------

    /**
     * The settled fields, grouped and folded away.
     *
     * <p>Open by default only where the answers were worked out rather than
     * copied. A name taken from the profile is right by construction and there is
     * nothing to catch; a sponsorship answer derived from the job's country is
     * right only if the derivation is, and that is a thing a person can actually
     * check.
     */
    private static void prepared(StringBuilder body, List<FieldView> fields) {
        List<FieldView> settled = fields.stream()
                .filter(f -> !"AWAITING_ANSWER".equals(f.state()))
                .filter(f -> !"AWAITING_APPROVAL".equals(f.state()))
                .filter(f -> !"AUTOMATION_BLOCKED".equals(f.outcome()))
                .toList();
        if (settled.isEmpty()) {
            return;
        }
        Map<FieldGroup, List<FieldView>> grouped = new LinkedHashMap<>();
        for (FieldGroup group : FieldGroup.values()) {
            grouped.put(group, new ArrayList<>());
        }
        settled.forEach(field -> grouped.get(FieldGroup.of(field.conceptId())).add(field));

        StringBuilder groups = new StringBuilder();
        for (Map.Entry<FieldGroup, List<FieldView>> entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            FieldGroup group = entry.getKey();
            boolean open = group == FieldGroup.AUTHORISATION;
            StringBuilder rows = new StringBuilder("<div class=\"field-list\">");
            entry.getValue().forEach(field -> doneField(rows, field));
            rows.append("</div>");

            groups.append("""
                    <details class="fgroup"%s>
                      <summary>
                        <span class="fgroup-name">%s</span>
                        <span class="fgroup-n">%d</span>
                        %s
                      </summary>
                      %s
                    </details>
                    """.formatted(open ? " open" : "", Ui.esc(group.label()),
                            entry.getValue().size(),
                            group.note() == null ? ""
                                    : "<span class=\"fgroup-note\">" + Ui.esc(group.note())
                                            + "</span>",
                            rows));
        }

        body.append(Parts.block("Already prepared", String.valueOf(settled.size()),
                Parts.state(Tone.OK, "No action needed"), groups.toString()));
    }

    /** A settled field: a line, not a card. See the class note. */
    private static void doneField(StringBuilder body, FieldView field) {
        body.append("<div class=\"field-done\" data-field=\"").append(field.id()).append("\">")
                .append("<span class=\"fd-q\">").append(Ui.esc(field.question()))
                .append(field.required() ? "" : " <span class=\"tag\">optional</span>")
                .append("</span>")
                .append("<span class=\"fd-a\">").append(Ui.esc(displayValue(field)))
                .append("</span>")
                .append("<span class=\"fd-side\">")
                .append(Parts.state(automationTone(field), field.automationLabel()))
                .append(whyButton(field))
                .append("</span>")
                .append(whyPanel(field))
                .append("</div>");
    }

    // ------------------------------------------------------------------
    // Supporting detail
    // ------------------------------------------------------------------

    /**
     * Which resume went, and why that one.
     *
     * <p>The tailoring note is the interesting half - it says what was moved to
     * the top for this posting, which is the only thing about a tailored resume
     * worth a sentence on a screen about something else.
     */
    private static void resume(StringBuilder body, ApplicationAttempt attempt) {
        if (attempt.getResumePath() == null) {
            return;
        }
        String name = attempt.getResumePath()
                .substring(attempt.getResumePath().lastIndexOf('/') + 1);
        body.append(Parts.block("Resume", null, """
                <div class="surface surface-pad resume-row">
                  <div>
                    <p class="resume-name">%s</p>
                    <p class="resume-why">%s</p>
                  </div>
                  <a class="btn btn-sm" href="/resume/%d" target="_blank"
                     rel="noopener">View resume</a>
                </div>
                """.formatted(Ui.esc(name),
                        Ui.esc(attempt.getTailoringNote() == null
                                ? "No tailoring note was recorded for this run."
                                : attempt.getTailoringNote()),
                        attempt.getId())));
    }

    /**
     * The screenshot, and only when there is one on disk.
     *
     * <p>The path is a record of what was captured and the file can be gone -
     * moved, cleaned up, written by a run whose output directory no longer
     * exists. Rendering the frame anyway gave five hundred pixels of broken
     * image, which reads as the page being broken rather than the file.
     */
    private static void screenshot(StringBuilder body, ApplicationAttempt attempt,
            PreparationView view) {
        if (attempt.getScreenshotPath() == null || !view.hasScreenshot()) {
            return;
        }
        body.append(Parts.block("The filled form", null,
                "<button class=\"btn btn-sm\" type=\"button\" id=\"shot-toggle\" "
                        + "aria-expanded=\"false\">Expand</button>",
                """
                <div class="surface">
                  <div class="shot-frame" id="shot-frame" tabindex="0"
                       aria-label="Screenshot of the filled form, scrollable">
                    <img src="/shot/%d" alt="the application form as it was filled">
                  </div>
                </div>
                <p class="caption">Captured before anything was sent.</p>
                """.formatted(attempt.getId())));
    }

    // ------------------------------------------------------------------
    // Field parts
    // ------------------------------------------------------------------

    private static String questionLine(FieldView field) {
        return "<p class=\"field-q\">" + Ui.esc(field.question())
                + (field.required() ? Parts.requiredTag() : "") + "</p>";
    }

    /**
     * The metadata line: where the answer came from, and how sure it is.
     *
     * <p>Two facts, one line, under the answer. The first version put both status
     * axes and the source in three separate chips above it, which turned every
     * settled field into four things to read.
     */
    private static String explanation(FieldView field) {
        String note = field.explanation();
        // "no configured answer for X" is the mapper restating the heading above
        // it in the mapper's own words. What is worth showing is the rest: the
        // reason a draft was refused, or what the resolver read and rejected.
        if (note == null || note.isBlank() || note.startsWith("no configured answer")) {
            return "";
        }
        return "<p class=\"why-line\">" + Ui.esc(note) + "</p>";
    }

    private static String evidence(FieldView field, String summary) {
        if (field.evidence().isEmpty()) {
            return "";
        }
        StringBuilder items = new StringBuilder();
        field.evidence().forEach(item ->
                items.append("<li>").append(Ui.esc(item)).append("</li>"));
        return """
                <details class="evidence">
                  <summary>%s (%d)</summary>
                  <ul>%s</ul>
                </details>
                """.formatted(Ui.esc(summary), field.evidence().size(), items);
    }

    /**
     * Where the explanation lands. Filled by {@code prep.js} when it is opened.
     *
     * <p>An inline panel rather than a modal, deliberately: the answer it explains
     * has to stay on screen next to the explanation, or the comparison the panel
     * exists to allow cannot be made. A modal would cover the one thing being
     * checked.
     */
    private static String whyPanel(FieldView field) {
        return "<div class=\"why-panel\" id=\"why-" + field.id() + "\" hidden></div>";
    }

    private static String whyButton(FieldView field) {
        return "<button class=\"btn btn-xs btn-quiet\" type=\"button\" data-why=\""
                + field.id() + "\" aria-expanded=\"false\" aria-controls=\"why-"
                + field.id() + "\">Why?</button>";
    }

    private static String paragraphs(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String paragraph : text.trim().split("\n\\s*\n")) {
            out.append("<p>").append(Ui.esc(paragraph.trim())).append("</p>");
        }
        return out.toString();
    }

    /** A file path is shown by its file name; nobody reads a directory on a form. */
    private static String displayValue(FieldView field) {
        String value = field.answer();
        if (value == null || value.isBlank()) {
            return "FILE".equals(field.controlType()) ? "—" : "left blank";
        }
        if (value.startsWith("/") && value.contains("/")) {
            return value.substring(value.lastIndexOf('/') + 1);
        }
        return value.length() <= 160 ? value : value.substring(0, 157) + "...";
    }

    private static Tone automationTone(FieldView field) {
        return switch (field.automationState()) {
            case "FILLED" -> Tone.OK;
            case "BLOCKED" -> Tone.MANUAL;
            default -> Tone.QUIET;
        };
    }

    private static Tone statusTone(AttemptStatus status) {
        return switch (status) {
            case SUBMITTED, READY_FOR_REVIEW, PREPARED -> Tone.OK;
            case FAILED -> Tone.BAD;
            case MANUAL_REQUIRED -> Tone.MANUAL;
            case AWAITING_ANSWER, NEEDS_HUMAN -> Tone.BAD;
            case AWAITING_APPROVAL -> Tone.WARN;
            case PREPARING -> Tone.INFO;
            case SKIPPED -> Tone.QUIET;
        };
    }

    private static List<FieldView> by(List<FieldView> fields, String state) {
        return fields.stream().filter(f -> state.equals(f.state())).toList();
    }
}
