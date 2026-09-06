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
     * <p>Radios are collapsed by {@code name} into one field whose options are the
     * button labels, because that is the question being asked. Reading them as
     * five separate fields loses the fact that exactly one may be chosen.
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

              const selectorFor = (el) => {
                if (el.id) return '#' + CSS.escape(el.id);
                if (el.name) return el.tagName.toLowerCase()
                    + '[name="' + CSS.escape(el.name) + '"]';
                const siblings = Array.from(
                    el.parentElement ? el.parentElement.children : []);
                return el.tagName.toLowerCase() + ':nth-child('
                    + (siblings.indexOf(el) + 1) + ')';
              };

              const isRequired = (el, label) =>
                  el.required || el.getAttribute('aria-required') === 'true'
                  || /\\*\\s*$/.test(label) || /\\(required\\)/i.test(label);

              const out = [];
              const seenRadioGroups = new Set();

              for (const el of document.querySelectorAll('input, select, textarea')) {
                if (['submit', 'button', 'reset', 'image'].includes(el.type)) continue;
                if (!visible(el) && el.type !== 'radio') continue;
                if (el.disabled || el.readOnly) continue;

                const label = labelFor(el);

                if (el.type === 'radio') {
                  if (!el.name || seenRadioGroups.has(el.name)) continue;
                  seenRadioGroups.add(el.name);
                  const group = Array.from(document.querySelectorAll(
                      'input[type="radio"][name="' + CSS.escape(el.name) + '"]'));
                  // The group's question is the fieldset legend, not the label of
                  // its first button - which is "Yes".
                  const fieldset = el.closest('fieldset');
                  const legend = fieldset ? fieldset.querySelector('legend') : null;
                  out.push({
                    selector: 'input[name="' + CSS.escape(el.name) + '"]',
                    label: (legend && text(legend)) || label,
                    control: 'radio',
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
                  label: label,
                  control: control,
                  options: control === 'select'
                      ? Array.from(el.options).map((o) => o.label || o.text || o.value)
                            .filter((v) => v && v.trim())
                      : [],
                  required: isRequired(el, label)
                });
              }
              return JSON.stringify(out);
            }
            """;
}
