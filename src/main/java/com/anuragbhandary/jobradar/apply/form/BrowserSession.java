package com.anuragbhandary.jobradar.apply.form;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Chromium session, opened for one application and closed after it.
 *
 * <p>Not a Spring singleton. A browser held open across a scheduled run is a
 * quarter of a gigabyte of resident memory and a process that outlives the
 * command that needed it; this is opened by {@code try (var session = ...)} and
 * shut when the application is done.
 *
 * <h2>Why the profile directory persists</h2>
 * Cookies and local storage are kept in a real user-data directory rather than a
 * fresh incognito context, because several boards require an account. Workday in
 * particular makes a separate account per employer tenant, and its sign-in is
 * emailed one-time codes. Logging in by hand once and having the session survive
 * is the difference between the tool being useful on Workday and not.
 *
 * <p>That directory holds live session cookies for every board used. It is
 * credentials in every sense that matters, it lives outside the repository, and
 * it is in {@code .gitignore} twice over.
 */
public class BrowserSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    /**
     * Chromium's own automation flags removed.
     *
     * <p>Not to defeat bot detection - nothing here touches a captcha, and a
     * board that does not want automated traffic gets none. It is that
     * {@code navigator.webdriver} makes some React form libraries take a
     * different code path, and debugging a form that fills correctly by hand and
     * not under Playwright is a bad way to spend an evening.
     */
    private static final List<String> ARGS = List.of("--disable-blink-features=AutomationControlled");

    private final Playwright playwright;
    private final BrowserContext context;
    private final Browser browser;

    private BrowserSession(Playwright playwright, Browser browser, BrowserContext context) {
        this.playwright = playwright;
        this.browser = browser;
        this.context = context;
    }

    /**
     * Opens a browser whose cookies survive between runs.
     *
     * @param headless false to watch it work. The default for applying is false -
     *                 the whole design assumes a human is present at the submit
     *                 step, and a form that goes wrong is much easier to diagnose
     *                 when it is on screen.
     */
    public static BrowserSession persistent(Path profileDir, boolean headless) {
        Playwright playwright = Playwright.create();
        BrowserContext context = playwright.chromium().launchPersistentContext(
                profileDir,
                new BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(headless)
                        .setArgs(ARGS)
                        .setViewportSize(1440, 900)
                        // Boards localise dates and number formats from this, and
                        // a form that silently switches to Dutch is worse than one
                        // that fails.
                        .setLocale("en-GB")
                        .setAcceptDownloads(true));
        log.debug("Browser profile at {}", profileDir);
        return new BrowserSession(playwright, null, context);
    }

    /**
     * A throwaway headless browser, used only to print HTML to PDF.
     *
     * <p>Separate from the persistent one on purpose: {@code Page.pdf()} is
     * headless-only in Chromium, and the applying browser is deliberately not
     * headless. Trying to serve both from one context means the resume cannot be
     * rendered while the form is open.
     */
    public static BrowserSession headless() {
        Playwright playwright = Playwright.create();
        Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true).setArgs(ARGS));
        return new BrowserSession(playwright, browser, browser.newContext());
    }

    public Page newPage() {
        return context.newPage();
    }

    /**
     * The domains this profile currently holds cookies for, with a count each.
     *
     * <p>The only way to answer "am I still signed in to that Workday tenant?"
     * without opening the board. Cookie counts rather than names: the names are
     * session tokens and there is no reason to print them.
     */
    public java.util.Map<String, Long> cookieDomains() {
        return context.cookies().stream()
                .map(cookie -> cookie.domain.startsWith(".")
                        ? cookie.domain.substring(1) : cookie.domain)
                .collect(java.util.stream.Collectors.groupingBy(
                        domain -> domain, java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()));
    }

    /** Blocks until the page is closed by hand, or the timeout passes. */
    public void waitForManualWork(Page page, long timeoutMs) {
        try {
            page.waitForClose(new Page.WaitForCloseOptions().setTimeout(timeoutMs), () -> { });
        } catch (RuntimeException e) {
            log.debug("Stopped waiting for the page: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            context.close();
            if (browser != null) {
                browser.close();
            }
        } catch (RuntimeException e) {
            log.debug("Browser did not close cleanly: {}", e.getMessage());
        } finally {
            playwright.close();
        }
    }
}
