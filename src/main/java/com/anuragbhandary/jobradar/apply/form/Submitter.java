package com.anuragbhandary.jobradar.apply.form;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only code in this project that submits an application.
 *
 * <p>It is one small class on purpose. An application cannot be unsent, most
 * boards accept one per posting for all time, and the cost of a bad one is not a
 * rejection - it is that the good application which could have been made now
 * cannot be. So the whole write path is here, it is reached from exactly one
 * caller, and it refuses in four separate ways.
 *
 * <h2>What it refuses</h2>
 * <ol>
 *   <li>A {@link FillReport} that is not submittable - a required field unanswered
 *       or a field that failed to fill.</li>
 *   <li>Any call where {@code confirmed} is false. The flag is set from a typed
 *       answer at a terminal and from nowhere else; there is no configuration
 *       option that sets it.</li>
 *   <li>A page carrying a captcha. Solving one is out of scope permanently, and a
 *       captcha is the board saying it does not want automated submissions - the
 *       tool hands the browser back and lets a person finish.</li>
 *   <li>A page with no button it can positively identify as submitting the
 *       application.</li>
 * </ol>
 */
@Component
public class Submitter {

    private static final Logger log = LoggerFactory.getLogger(Submitter.class);

    /** Accessible names that mean "send the application". */
    private static final List<String> SUBMIT_NAMES = List.of(
            "submit application", "submit my application", "submit", "send application",
            "apply now", "apply for this job", "apply");

    /**
     * Words that mean the opposite despite matching the above.
     *
     * <p>"Save and continue later" contains no submit word, but "Apply filters"
     * does, and so does the "Submit" on a newsletter box that some career pages
     * put in the footer. Matching the first button containing "apply" on a
     * Greenhouse page finds the header's "Apply" link, which navigates away and
     * loses the filled form.
     */
    private static final List<String> NOT_SUBMIT = List.of(
            "filter", "search", "newsletter", "subscribe", "save", "later",
            "back", "cancel", "previous", "sign in", "log in", "create account");

    private static final List<String> CAPTCHA_MARKERS = List.of(
            "iframe[src*='recaptcha']", "iframe[src*='hcaptcha']",
            "iframe[title*='captcha' i]", "[class*='captcha']", "#cf-turnstile");

    /**
     * Clicks submit.
     *
     * @param confirmed must come from a human answering a prompt. There is no
     *                  other legitimate source, and a caller that hardcodes true
     *                  has defeated the design rather than configured it.
     * @return the page URL after submission, for the audit record
     */
    public String submit(Page page, FillReport report, boolean confirmed) {
        if (!confirmed) {
            throw new IllegalStateException("submit() called without human confirmation");
        }
        if (!report.isSubmittable()) {
            throw new IllegalStateException(
                    "form is not submittable: " + report.blockers().size()
                            + " unanswered required field(s)");
        }

        String captcha = detectCaptcha(page);
        if (captcha != null) {
            throw new CaptchaPresentException(
                    "this form has a captcha (" + captcha + "). "
                            + "It is filled and ready - finish it by hand in the open browser.");
        }

        Locator button = findSubmitButton(page);
        if (button == null) {
            throw new IllegalStateException(
                    "no submit button found - the form is filled; submit it by hand");
        }

        String label = safeText(button);
        log.info("Clicking '{}'", label);
        button.click();

        // networkidle rather than a fixed sleep: single-page forms post over XHR
        // and re-render, and a fixed wait is either too short to catch the result
        // or long enough to be annoying on every application.
        page.waitForLoadState(LoadState.NETWORKIDLE);
        return page.url();
    }

    /** The captcha selector that matched, or null. */
    public String detectCaptcha(Page page) {
        for (String selector : CAPTCHA_MARKERS) {
            try {
                if (page.locator(selector).count() > 0) {
                    return selector;
                }
            } catch (RuntimeException e) {
                // A malformed selector on an exotic page must not be the thing
                // that decides whether an application is submitted.
                log.debug("Captcha probe {} failed: {}", selector, e.getMessage());
            }
        }
        return null;
    }

    /**
     * The button that submits this form, or null if none is unambiguous.
     *
     * <p>Names are tried longest-first, so "Submit application" is preferred over
     * a bare "Submit" and "Apply" is the last resort rather than the first hit.
     */
    private Locator findSubmitButton(Page page) {
        for (String name : SUBMIT_NAMES) {
            Locator candidates = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(name).setExact(false));
            int count = candidates.count();
            for (int i = 0; i < count; i++) {
                Locator candidate = candidates.nth(i);
                if (isPlausible(candidate)) {
                    return candidate;
                }
            }
        }
        // input[type=submit] is not a button role, and older boards still use it.
        Locator fallback = page.locator("input[type='submit']");
        return fallback.count() == 1 && isPlausible(fallback.first()) ? fallback.first() : null;
    }

    private boolean isPlausible(Locator candidate) {
        try {
            if (!candidate.isVisible() || !candidate.isEnabled()) {
                return false;
            }
            String text = safeText(candidate).toLowerCase(Locale.ROOT);
            return NOT_SUBMIT.stream().noneMatch(text::contains);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String safeText(Locator locator) {
        try {
            String text = locator.innerText();
            return text == null || text.isBlank()
                    ? String.valueOf(locator.getAttribute("value")) : text.trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Thrown when the board is asking for a human, and gets one. */
    public static class CaptchaPresentException extends RuntimeException {
        public CaptchaPresentException(String message) {
            super(message);
        }
    }
}
