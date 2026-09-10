package com.anuragbhandary.jobradar.apply.form;

import com.anuragbhandary.jobradar.apply.ApplicationDocuments;
import com.anuragbhandary.jobradar.domain.Posting;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Types the answers into the page.
 *
 * <p><strong>This class cannot submit anything.</strong> It has no reference to a
 * submit button and no code path that clicks one; that lives in {@link Submitter}
 * and is called from one place, after a human has said yes. The separation is the
 * safety property of the whole feature - an exception, a retry or a stray Enter
 * key in here cannot send an application, because there is nothing here to send
 * it with.
 *
 * <p>Filling continues past a field that fails. One dropdown whose options
 * changed should not cost the other thirty-nine fields; the failure is recorded
 * and shown, and a form with a failure is not submittable.
 */
@Component
public class FormFiller {

    private static final Logger log = LoggerFactory.getLogger(FormFiller.class);

    private final FieldMapper mapper;
    /** The knowledge system's answer, used when it is the authority. */
    private final KnowledgeAnswers knowledge;
    /**
     * Whether the knowledge system decides real answers.
     *
     * <p>One flag, read once, and both implementations stay compiled in. Turning
     * it off is a config edit and a restart, not a rollback commit - which is the
     * only kind of rollback worth having on the path that fills an employer's
     * form.
     */
    private final boolean knowledgeIsAuthoritative;

    public FormFiller(FieldMapper mapper, KnowledgeAnswers knowledge,
            @org.springframework.beans.factory.annotation.Value(
                    "${job-radar.knowledge.resolver-authoritative:false}")
            boolean knowledgeIsAuthoritative) {
        this.mapper = mapper;
        this.knowledge = knowledge;
        this.knowledgeIsAuthoritative = knowledgeIsAuthoritative;
        log.info("Form answers come from {}", knowledgeIsAuthoritative
                ? "the knowledge resolver" : "the legacy field mapper");
    }

    public FillReport fill(
            Page page, List<FormField> fields, Posting posting, ApplicationDocuments documents) {
        return fill(page, fields, posting, documents,
                com.anuragbhandary.jobradar.apply.PreparedAnswers.none(), null);
    }

    public FillReport fill(Page page, List<FormField> fields, Posting posting,
            ApplicationDocuments documents,
            com.anuragbhandary.jobradar.apply.PreparedAnswers settled) {
        return fill(page, fields, posting, documents, settled, null);
    }

    /**
     * Fills, preferring answers the applicant has already settled on.
     *
     * <p>The override layer is what makes an application resumable. A question
     * answered on the preparation screen has to survive the browser closing, and
     * on the next run it is the settled answer that goes in rather than the one
     * the mapper would work out again from a profile that has not changed.
     *
     * <p>It is a layer above the mapper rather than a change to it, on purpose.
     * The mapper is still what answers every field nobody has touched, and there
     * is no path here by which the new knowledge resolver decides anything - see
     * {@link com.anuragbhandary.jobradar.apply.PreparedAnswers}.
     */
    public FillReport fill(Page page, List<FormField> fields, Posting posting,
            ApplicationDocuments documents,
            com.anuragbhandary.jobradar.apply.PreparedAnswers settled,
            com.anuragbhandary.jobradar.knowledge.ApplicationContext context) {

        List<FillReport.Entry> entries = new ArrayList<>(fields.size());
        List<FillReport.Entry> blockers = new ArrayList<>();

        for (FormField field : fields) {
            Answer answer = settled.forField(field)
                    .map(chosen -> reconcile(chosen, field))
                    .orElseGet(() -> answerFor(field, posting, documents, context));

            if (!answer.hasValue()) {
                FillReport.Entry entry = new FillReport.Entry(field, answer, false, null);
                entries.add(entry);
                // A required field with no answer is the whole reason this method
                // returns a report. It is not filled with a placeholder and the
                // run does not continue as though it were fine.
                // A consent box is a blocker when required - it genuinely stops
                // the form - but it is not a profile gap, and the message says so.
                if (field.required() && answer.origin() != Answer.Origin.DECLINED) {
                    blockers.add(entry);
                }
                continue;
            }

            try {
                apply(page, field, answer.value());
                entries.add(new FillReport.Entry(field, answer, true, null));
            } catch (RuntimeException e) {
                String message = firstLine(e.getMessage());
                log.warn("Could not fill '{}': {}", field.label(), message);
                FillReport.Entry entry = new FillReport.Entry(field, answer, false, message);
                entries.add(entry);
                if (field.required()) {
                    blockers.add(entry);
                }
            }
        }

        log.info("Filled {} of {} fields; {} blocker(s)",
                entries.stream().filter(FillReport.Entry::filled).count(),
                fields.size(), blockers.size());
        return new FillReport(List.copyOf(entries), List.copyOf(blockers));
    }

    /**
     * Which system answers this field.
     *
     * <p>The knowledge resolver when it is the authority and there is a context to
     * resolve against; the legacy mapper otherwise. The mapper is kept and is not
     * going anywhere - it is the regression oracle the shadow compares against,
     * the fallback when the flag is off, and the reference when the two disagree.
     */
    Answer answerFor(FormField field, Posting posting, ApplicationDocuments documents,
            com.anuragbhandary.jobradar.knowledge.ApplicationContext context) {

        // No context means nothing in the knowledge system can resolve, so a
        // caller without one gets the path that does not need one rather than a
        // form full of blanks.
        if (knowledgeIsAuthoritative && context != null) {
            return knowledge.answer(field, context, documents);
        }
        return mapper.answer(field, posting, documents);
    }

    /**
     * Checks a settled answer against the options this reading of the form offers.
     *
     * <p>The option list can change between two readings of the same form, and a
     * value a select does not have cannot be typed into it. Where it no longer
     * fits, the field is left unanswered <em>with the reason</em> rather than
     * handed back to the mapper: the applicant decided this one, and quietly
     * replacing his decision with a computed answer is worse than stopping and
     * saying the form has moved.
     */
    private static Answer reconcile(Answer settled, FormField field) {
        if (!field.isChoice() || field.options() == null || field.options().isEmpty()) {
            return settled;
        }
        return OptionMatcher.match(settled.value(), field.options())
                .map(option -> new Answer(option, settled.origin(), settled.note()))
                .orElseGet(() -> Answer.unanswered(
                        "you answered this earlier, and this form no longer offers that "
                                + "option - it wants one of: "
                                + String.join(" | ", field.options())));
    }

    private void apply(Page page, FormField field, String value) {
        Locator locator = page.locator(field.selector()).first();

        switch (field.control()) {
            case FILE -> locator.setInputFiles(Path.of(value));

            case SELECT -> locator.selectOption(new SelectOption().setLabel(value));

            // Click the button whose own label matches, not the group. Clicking
            // the group's first element selects "Yes" for every question on the
            // form, which is a uniquely bad failure: it is invisible in a
            // screenshot of a long page and answers the sponsorship question
            // wrongly.
            case RADIO -> {
                Locator chosen = page.locator(field.selector())
                        .filter(new Locator.FilterOptions().setHasText(value));
                if (chosen.count() == 0) {
                    // The radio's text often sits in a sibling label rather than
                    // inside the input, so fall back to the accessible name.
                    chosen = page.getByRole(com.microsoft.playwright.options.AriaRole.RADIO,
                            new Page.GetByRoleOptions().setName(value).setExact(false));
                }
                if (chosen.count() == 0) {
                    throw new IllegalStateException("no radio button labelled '" + value + "'");
                }
                chosen.first().check();
            }

            // Clicked, not checked: these are buttons wearing role="radio", and
            // Playwright's check() only understands real inputs.
            case ARIA_CHOICE -> clickOption(page, field, value);

            case CHECKBOX -> {
                if (Boolean.parseBoolean(value) || "yes".equalsIgnoreCase(value)) {
                    locator.check();
                } else {
                    locator.uncheck();
                }
            }

            // fill() replaces rather than appends. type() would append to a field
            // the board pre-populated from a parsed resume, producing doubled
            // names - which is what a "smart" ATS does to an autofilled form.
            case TEXT, TEXTAREA, DATE -> fillText(page, locator, field, value);
        }
    }

    /**
     * Fills a text input, falling back to the typeahead dance when it is a
     * combobox pretending to be one.
     *
     * <p>Location and country fields on Ashby and Workday are text inputs backed
     * by an async listbox. {@code fill()} sets the value and the widget's own
     * state never changes, so the form submits with the field empty - or, on the
     * ones that validate, {@code fill()} throws outright. Both are silent
     * failures of the same shape: the box looks filled on screen.
     *
     * <p>So a combobox is typed into and a suggestion is chosen. If no suggestion
     * appears the value is left as typed, and the review file shows what happened
     * rather than the fill being reported as successful.
     */
    private void fillText(Page page, Locator locator, FormField field, String value) {
        if (!isCombobox(locator)) {
            locator.fill(value);
            return;
        }

        locator.click();
        locator.fill("");
        // Typed rather than filled: the listbox is populated by keystroke events,
        // which fill() does not produce.
        locator.pressSequentially(value, new Locator.PressSequentiallyOptions().setDelay(35));

        Locator options = page.getByRole(AriaRole.OPTION);
        try {
            options.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(2500));
        } catch (RuntimeException e) {
            log.debug("No suggestions for '{}' on '{}'", value, field.label());
            return;
        }
        options.first().click();
    }

    /** A text input the page has declared, or wired up, as a combobox. */
    private static boolean isCombobox(Locator locator) {
        try {
            String role = locator.getAttribute("role");
            if ("combobox".equalsIgnoreCase(role)) {
                return true;
            }
            String expanded = locator.getAttribute("aria-expanded");
            String controls = locator.getAttribute("aria-controls");
            String autocomplete = locator.getAttribute("aria-autocomplete");
            return expanded != null || controls != null || autocomplete != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Picks one option out of a group built from buttons.
     *
     * <p>These are frequently {@code <button type="submit">} - Ashby's are - which
     * means a click that the page does not intercept would post the form. The
     * page always does intercept, right up until the day one does not, and the
     * cost of that day is a half-filled application sent to a company that
     * accepts one.
     *
     * <p>So the URL is captured before the click and checked after. A navigation
     * means the click did something other than answer a question, and that is a
     * hard failure which stops the run rather than a warning nobody reads.
     */
    private void clickOption(Page page, FormField field, String value) {
        Locator group = page.locator(field.selector()).first();

        Locator chosen = group.locator("[role=radio], [role=switch], [role=checkbox], button")
                .filter(new Locator.FilterOptions().setHasText(value));
        if (chosen.count() == 0) {
            chosen = group.getByText(value, new Locator.GetByTextOptions().setExact(false));
        }
        if (chosen.count() == 0) {
            throw new IllegalStateException("no option labelled '" + value + "' in this group");
        }

        String before = page.url();
        chosen.first().click();
        String after = page.url();
        if (!before.equals(after)) {
            throw new IllegalStateException(
                    "clicking '" + value + "' navigated to " + after
                            + " - it was not an option button. Nothing further was filled.");
        }
    }

    /**
     * A one-line, readable version of a Playwright error.
     *
     * <p>Its messages are multi-line and begin with a brace, so taking everything
     * before the first newline produced the literal string "Error {" for every
     * failure on this form - which says nothing, and looked like the same failure
     * happening twice when it was two different ones.
     */
    static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        String flattened = message.replaceAll("\\s+", " ").trim();
        // Playwright puts the useful sentence after the "Call log" preamble on
        // some errors and before it on others; the first 200 characters catch it
        // either way without pasting a stack trace into the review file.
        return flattened.length() <= 200 ? flattened : flattened.substring(0, 197) + "...";
    }
}
