package com.anuragbhandary.jobradar.apply.form;

import com.anuragbhandary.jobradar.apply.ApplicationDocuments;
import com.anuragbhandary.jobradar.domain.Posting;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.SelectOption;
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

    public FormFiller(FieldMapper mapper) {
        this.mapper = mapper;
    }

    public FillReport fill(
            Page page, List<FormField> fields, Posting posting, ApplicationDocuments documents) {

        List<FillReport.Entry> entries = new ArrayList<>(fields.size());
        List<FillReport.Entry> blockers = new ArrayList<>();

        for (FormField field : fields) {
            Answer answer = mapper.answer(field, posting, documents);

            if (!answer.hasValue()) {
                FillReport.Entry entry = new FillReport.Entry(field, answer, false, null);
                entries.add(entry);
                // A required field with no answer is the whole reason this method
                // returns a report. It is not filled with a placeholder and the
                // run does not continue as though it were fine.
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
            case TEXT, TEXTAREA, DATE -> locator.fill(value);
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
