package com.anuragbhandary.jobradar.apply.form;

import java.util.List;

/**
 * One input on a live application form, as read out of the page.
 *
 * @param selector   a stable way back to the element. Built from id or name where
 *                   the board provides one, because Playwright's own element
 *                   handles do not survive the page re-rendering between reading
 *                   the form and filling it - which Greenhouse does on focus.
 * @param label      the visible question text, whitespace-collapsed
 * @param control    what kind of widget it is; decides how it gets filled
 * @param options    the choices for a select or radio group, empty otherwise.
 *                   Filling a select requires matching the answer to one of these
 *                   rather than typing it.
 * @param required   whether the form marks it required. The difference between
 *                   "skip this" and "stop and ask" for an unanswerable field.
 */
public record FormField(
        String selector,
        String label,
        ControlType control,
        List<String> options,
        boolean required,
        FieldKind kind) {

    public enum ControlType {
        TEXT,
        TEXTAREA,
        SELECT,
        RADIO,
        CHECKBOX,
        FILE,
        DATE,

        /**
         * A choice built out of buttons and ARIA roles rather than {@code <input>}.
         *
         * <p>Its own type because it is filled by clicking, not by checking - and
         * because it exists at all. Ashby renders "Are you legally eligible to
         * work in the country where you're planning to work from?" as a pair of
         * styled buttons with {@code role="radio"}. A reader that queries
         * {@code input, select, textarea} cannot see it, so a required question
         * sat empty on screen while the report said zero blockers. A form that
         * looks submittable and is not is the worst output this tool has.
         */
        ARIA_CHOICE
    }

    public FormField withKind(FieldKind newKind) {
        return new FormField(selector, label, control, options, required, newKind);
    }

    public boolean isFreeText() {
        return control == ControlType.TEXT || control == ControlType.TEXTAREA;
    }

    /** True when the field offers a fixed set of answers that must be matched. */
    public boolean isChoice() {
        return control == ControlType.SELECT || control == ControlType.RADIO
                || control == ControlType.ARIA_CHOICE;
    }
}
