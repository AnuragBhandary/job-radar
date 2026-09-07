package com.anuragbhandary.jobradar.apply.form;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a live application form into {@link FormField}s.
 *
 * <p>Done in one injected script rather than with Playwright locators, for two
 * reasons. A form of forty fields is forty round trips otherwise, on pages that
 * are already slow. More importantly, the label of a field is not a property
 * Playwright exposes: finding it means walking {@code <label for>}, then ARIA,
 * then the DOM around the input, and doing that walk in the page is both faster
 * and the only place the layout is actually visible.
 *
 * <p>What comes back is untrusted text from a third-party page. It is classified
 * by substring rules, shown to a human, and never executed or interpreted as an
 * instruction.
 */
@Component
public class FormReader {

    private static final Logger log = LoggerFactory.getLogger(FormReader.class);

    private final ObjectMapper mapper;
    private final FieldClassifier classifier;

    public FormReader(ObjectMapper mapper, FieldClassifier classifier) {
        this.mapper = mapper;
        this.classifier = classifier;
    }

    /** Every visible, fillable field on the page, classified. */
    public List<FormField> read(Page page) {
        Object result = page.evaluate(SCRIPT);
        List<RawField> raw;
        try {
            raw = mapper.readValue(String.valueOf(result), new TypeReference<List<RawField>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the form: " + e.getMessage(), e);
        }

        List<FormField> fields = new ArrayList<>(raw.size());
        for (RawField field : raw) {
            FormField.ControlType control = control(field.control());
            FormField unclassified = new FormField(
                    field.selector(),
                    field.label() == null ? "" : field.label(),
                    control,
                    field.options() == null ? List.of() : field.options(),
                    field.required(),
                    FieldKind.UNKNOWN);
            fields.add(unclassified.withKind(classifier.classify(unclassified)));
        }
        log.debug("Read {} fields", fields.size());
        return fields;
    }

    private static FormField.ControlType control(String name) {
        return switch (name) {
            case "textarea" -> FormField.ControlType.TEXTAREA;
            case "select" -> FormField.ControlType.SELECT;
            case "radio" -> FormField.ControlType.RADIO;
            case "checkbox" -> FormField.ControlType.CHECKBOX;
            case "file" -> FormField.ControlType.FILE;
            case "date" -> FormField.ControlType.DATE;
            case "aria" -> FormField.ControlType.ARIA_CHOICE;
            default -> FormField.ControlType.TEXT;
        };
    }

    private record RawField(
            String selector, String label, String control,
            List<String> options, boolean required) {
    }

    /**
     * The extraction script.
     *
     * <p>Label resolution is tried in the order a person would read the page:
     * the explicit {@code <label for>}, then ARIA, then an enclosing label, then
     * the nearest preceding text, and only then the placeholder. Placeholder is
     * last because boards use it for examples - a field labelled "Phone" with
     * placeholder "+1 555 0100" would otherwise be classified from the example.
     *
     * <p>Radios and multi-checkbox groups are collapsed by {@code name} into one
     * field whose options are the button labels, because that is the question
     * being asked. Reading them as five separate fields loses the fact that they
     * are one choice - and, worse, labels each of them with its own option text,
     * so the blocked list fills with entries like "I agree" and "Less than 2
     * years" that no profile could ever answer.
     *
     * <p>A lone checkbox is left alone: "I agree to the privacy policy" really is
     * its own question.
     */
    private static final String SCRIPT = """
            () => {
              const visible = (el) => {
                if (!el) return false;
                const style = getComputedStyle(el);
                if (style.display === 'none' || style.visibility === 'hidden') return false;
                if (el.type === 'hidden') return false;
                // A file input is routinely 1x1 or fully transparent behind a
                // styled button, so it is exempt from the size test - excluding
                // it would lose the resume upload on most modern boards.
                if (el.type === 'file') return true;
                const rect = el.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0;
              };

              const text = (el) => (el ? (el.innerText || el.textContent || '') : '')
                  .replace(/\\s+/g, ' ').trim();

              const labelFor = (el) => {
                if (el.id) {
                  const tag = document.querySelector(
                      'label[for="' + CSS.escape(el.id) + '"]');
                  if (tag && text(tag)) return text(tag);
                }
                if (el.getAttribute('aria-label')) return el.getAttribute('aria-label').trim();
                const by = el.getAttribute('aria-labelledby');
                if (by) {
                  const parts = by.split(/\\s+/)
                      .map((id) => document.getElementById(id))
                      .filter(Boolean).map(text).filter(Boolean);
                  if (parts.length) return parts.join(' ');
                }
                const wrapping = el.closest('label');
                if (wrapping && text(wrapping)) return text(wrapping);

                // Walk up looking for a container that holds a heading or a
                // legend. Stops at 4 levels: past that the "label" is the whole
                // form section and every field gets the same one.
                let node = el.parentElement;
                for (let depth = 0; node && depth < 4; depth++, node = node.parentElement) {
                  const own = node.querySelector('label, legend, .label, [class*="label"]');
                  if (own && text(own)) return text(own);
                }
                return el.placeholder || el.name || '';
              };

              // Every field gets a selector that matches exactly one element.
              //
              // The old fallback was `input:nth-child(3)`, which is not scoped to
              // a parent and so matches the third child of ANY element in the
              // document. Playwright's .first() then picked whichever came first
              // in document order - on this form, the hidden resume file input -
              // and the failure surfaced as "Input of type file cannot be filled"
              // against a field labelled "Location". A selector that silently
              // resolves to a different element is worse than one that fails.
              let stamped = 0;
              const selectorFor = (el) => {
                if (el.id) return '#' + CSS.escape(el.id);
                if (el.name) return el.tagName.toLowerCase()
                    + '[name="' + CSS.escape(el.name) + '"]';
                // No stable handle of its own, so give it one. If the page
                // re-renders before the fill the attribute goes with it and the
                // locator finds nothing - which fails loudly, and is the outcome
                // to want when the alternative is filling the wrong element.
                const stamp = 'jr' + (stamped++);
                el.setAttribute('data-job-radar-field', stamp);
                return '[data-job-radar-field="' + stamp + '"]';
              };

              const isRequired = (el, label) =>
                  el.required || el.getAttribute('aria-required') === 'true'
                  || /\\*\\s*$/.test(label) || /\\(required\\)/i.test(label);

              // The question a group of choices belongs to, as opposed to the
              // label of any one choice.
              //
              // The fieldset legend is the correct answer and is often absent -
              // Ashby renders choice groups as divs. Without a fallback the field
              // arrives labelled "I agree" or "Less than 2 years", which is an
              // option masquerading as a question: unanswerable, and it makes the
              // blocked list read as nonsense.
              const groupQuestion = (el) => {
                const fieldset = el.closest('fieldset');
                const legend = fieldset ? fieldset.querySelector('legend') : null;
                if (legend && text(legend)) return text(legend);

                const group = el.closest('[role="group"], [role="radiogroup"]');
                if (group) {
                  const by = group.getAttribute('aria-labelledby');
                  if (by) {
                    const named = document.getElementById(by);
                    if (named && text(named)) return text(named);
                  }
                  if (group.getAttribute('aria-label')) {
                    return group.getAttribute('aria-label').trim();
                  }
                }

                // Walk up, preferring text that reads as a question.
                //
                // The loose version of this - "take the previous sibling's text" -
                // labelled a Yes/No group "Resume Upload File or drag and drop
                // here", because it climbed past the question and landed in the
                // upload widget above it. A question mark or the required
                // asterisk is what distinguishes a label from whatever else
                // happens to sit nearby, so those are required first and the
                // loose match is only a last resort at close range.
                const asksSomething = (t) =>
                    t.length > 0 && t.length <= 200 && (/[?*]\\s*$/.test(t) || /\\?/.test(t));

                let node = el.parentElement;
                for (let depth = 0; node && depth < 4; depth++, node = node.parentElement) {
                  const previous = node.previousElementSibling;
                  if (previous && asksSomething(text(previous))) {
                    return text(previous);
                  }
                  // A <label> or <legend> is semantically the question, so it does
                  // not have to look like one. Requiring a question mark here cost
                  // the checkbox group its heading and put the option text back in
                  // its place: "Less than 2 years" as a question nobody can answer.
                  const labelled = node.querySelector('label, legend, [class*="Label"]');
                  if (labelled && !labelled.contains(el)) {
                    const labelText = text(labelled);
                    if (labelText.length > 0 && labelText.length <= 200) {
                      return labelText;
                    }
                  }
                }

                // Nothing question-shaped nearby. One short sibling at close
                // range, or nothing at all - and nothing at all is fine, because
                // an unlabelled group is reported rather than answered.
                const near = el.parentElement && el.parentElement.previousElementSibling;
                const nearText = near ? text(near) : '';
                return nearText.length > 0 && nearText.length <= 120 ? nearText : '';
              };

              const out = [];
              const seenGroups = new Set();

              for (const el of document.querySelectorAll('input, select, textarea')) {
                if (['submit', 'button', 'reset', 'image'].includes(el.type)) continue;
                if (!visible(el) && el.type !== 'radio') continue;
                if (el.disabled || el.readOnly) continue;

                const label = labelFor(el);

                // Radios always group by name. Checkboxes group only when there
                // is more than one sharing a name - a lone checkbox is its own
                // question ("I agree to the privacy policy") and collapsing it
                // would lose that.
                const grouped = el.type === 'radio'
                    || (el.type === 'checkbox' && el.name
                        && document.querySelectorAll(
                            'input[type="checkbox"][name="' + CSS.escape(el.name) + '"]'
                          ).length > 1);

                if (grouped) {
                  const key = el.type + ':' + el.name;
                  if (!el.name || seenGroups.has(key)) continue;
                  seenGroups.add(key);
                  const group = Array.from(document.querySelectorAll(
                      'input[type="' + el.type + '"][name="'
                        + CSS.escape(el.name) + '"]'));
                  out.push({
                    selector: 'input[name="' + CSS.escape(el.name) + '"]',
                    label: groupQuestion(el) || label,
                    control: el.type === 'radio' ? 'radio' : 'checkbox',
                    options: group.map(labelFor).filter(Boolean),
                    required: group.some((r) => isRequired(r, label))
                  });
                  continue;
                }

                let control = el.tagName.toLowerCase();
                if (control === 'input') control = el.type === 'file' ? 'file'
                    : el.type === 'checkbox' ? 'checkbox'
                    : el.type === 'date' ? 'date' : 'text';

                out.push({
                  selector: selectorFor(el),
                  // A file input is routinely unlabelled and sits next to the
                  // field above it, so it borrows that field's label. Naming it
                  // for what it is keeps a failure on the upload from reading as
                  // a failure on the name field.
                  label: control === 'file' && !/resum|cv|cover|file|upload/i.test(label)
                      ? (label ? label + ' (file upload)' : 'File upload')
                      : label,
                  control: control,
                  options: control === 'select'
                      ? Array.from(el.options).map((o) => o.label || o.text || o.value)
                            .filter((v) => v && v.trim())
                      : [],
                  required: isRequired(el, label)
                });
              }
              // --- Choices that are not inputs -------------------------------
              //
              // Ashby, Workday and most component libraries build yes/no toggles
              // out of buttons with role="radio". Nothing above sees them, so a
              // required question renders empty and the run reports no blockers.
              const ariaGroups = new Set();

              // Two shapes. The tidy one declares role="radio". The common one -
              // Ashby's, and most component libraries' - is a row of bare
              // <button> elements with no role at all, which is why looking only
              // for ARIA roles found nothing and two required questions stayed
              // invisible.
              const declared = Array.from(document.querySelectorAll(
                  '[role="radio"], [role="switch"], [role="checkbox"]'))
                  .filter((o) => o.tagName !== 'INPUT' && visible(o));

              // A button is an option candidate when its label is short enough to
              // be one. "Yes", "No", "1-3 years" are options; anything sentence
              // length is a real button and is left alone.
              const buttonOptions = Array.from(document.querySelectorAll('button'))
                  .filter((b) => visible(b) && !b.disabled
                      && text(b).length > 0 && text(b).length <= 40
                      && !/submit|apply|upload|replace|continue|next|back|cancel|save/i
                          .test(text(b)));

              for (const option of declared.concat(buttonOptions)) {
                const group = option.closest(
                    '[role="radiogroup"], [role="group"], fieldset') || option.parentElement;
                if (!group || ariaGroups.has(group)) continue;

                const options = Array.from(group.children)
                    .filter((o) => o === option || o.tagName === option.tagName)
                    .filter(visible)
                    .filter((o) => text(o).length > 0 && text(o).length <= 40);
                // Two to six siblings that all look like options. One is a button;
                // seven is a toolbar.
                if (options.length < 2 || options.length > 6) continue;
                ariaGroups.add(group);

                const stamp = 'jrg' + (stamped++);
                group.setAttribute('data-job-radar-field', stamp);

                const question = groupQuestion(option)
                    || group.getAttribute('aria-label') || '';
                if (!question) continue;
                out.push({
                  selector: '[data-job-radar-field="' + stamp + '"]',
                  label: question,
                  control: 'aria',
                  options: options.map((o) =>
                      (o.getAttribute('aria-label') || text(o))).filter(Boolean),
                  // aria-required lives on the group on some libraries and on the
                  // options on others, so both are checked.
                  required: group.getAttribute('aria-required') === 'true'
                      || options.some((o) => o.getAttribute('aria-required') === 'true')
                      || /\\*\\s*$/.test(question) || /\\(required\\)/i.test(question)
                });
              }

              return JSON.stringify(out);
            }
            """;
}
