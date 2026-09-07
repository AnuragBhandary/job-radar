package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.apply.ApplyProperties;
import com.anuragbhandary.jobradar.apply.form.BrowserSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import java.nio.file.Path;
import java.util.Map;
import java.util.Scanner;
import org.springframework.stereotype.Component;

/**
 * {@code login --url=...} - sign in to a board by hand, once.
 *
 * <p>The gap this fills is Workday, which is a large share of European hiring and
 * is unusable without it. Workday makes a <strong>separate account per employer
 * tenant</strong>: an account at Philips is not an account at NXP, sign-in is by
 * emailed one-time code, and the application form is behind it. There is no
 * automating that first step and there should not be - it involves a password and
 * a code from an inbox.
 *
 * <p>So it is done by hand, once per employer, in the same persistent browser
 * profile that {@code apply} uses. After that the session cookie is there and
 * {@code apply} walks straight in.
 *
 * <p>This command types nothing. It opens a window and waits. The tool never sees
 * the password, because it is never in a field the tool wrote to.
 */
@Component
public class LoginCommand {

    /** Long enough for an emailed code to arrive and be found. */
    private static final long WAIT_MS = 15 * 60 * 1000L;

    private final ApplyProperties config;

    public LoginCommand(ApplyProperties config) {
        this.config = config;
    }

    public void run(Map<String, String> options) {
        Path profile = Path.of(expand(config.browserProfile()));

        if ("true".equals(options.get("list"))) {
            list(profile);
            return;
        }

        String url = options.get("url");
        if (url == null || url.isBlank()) {
            System.out.println("""
                    Usage:
                      login --url=https://philips.wd3.myworkdayjobs.com/...   sign in by hand
                      login --list                                           what is signed in

                    Workday makes a separate account per employer, so this is once
                    per company. The session then persists and `apply` uses it.
                    """);
            return;
        }

        try (BrowserSession session = BrowserSession.persistent(profile, false)) {
            Page page = session.newPage();
            page.navigate(url);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            System.out.printf("""

                    A browser is open at:
                      %s

                    Sign in, or create the account, by hand. Nothing here types
                    anything into that window - your password never passes through
                    this tool.

                    Press Enter when you are done (or just close the window).
                    """, url);

            // Either signal ends the wait: a typed Enter, or the window closing.
            // Waiting only on the console would leave a dead browser open; waiting
            // only on the close event would strand anyone who leaves it open.
            Thread waiter = Thread.ofVirtual().start(
                    () -> session.waitForManualWork(page, WAIT_MS));
            new Scanner(System.in).nextLine();
            waiter.interrupt();

            Map<String, Long> domains = session.cookieDomains();
            System.out.printf("%nProfile now holds cookies for %d domain(s).%n", domains.size());
            domains.forEach((domain, count) ->
                    System.out.printf("  %-46s %d%n", domain, count));
            System.out.println("\nThese persist. `apply` will use them.");

        } catch (RuntimeException e) {
            System.out.println("Could not open the browser: " + e.getMessage());
        }
    }

    /**
     * Opens the profile headless purely to read its cookie jar.
     *
     * <p>Headless because nothing needs to be seen, and it is the one place the
     * browser is opened without a human meaning to look at it.
     */
    private void list(Path profile) {
        try (BrowserSession session = BrowserSession.persistent(profile, true)) {
            Map<String, Long> domains = session.cookieDomains();
            if (domains.isEmpty()) {
                System.out.println("\nNo stored sessions. `login --url=...` creates one.");
                return;
            }
            System.out.printf("%n%d domain(s) with a stored session:%n%n", domains.size());
            domains.forEach((domain, count) ->
                    System.out.printf("  %-46s %d cookie(s)%n", domain, count));
            System.out.println("""

                    A cookie here is not proof of being signed in - boards expire
                    sessions server-side without deleting anything. The real test
                    is `apply --posting-id=N` reaching the form.""");
        } catch (RuntimeException e) {
            System.out.println("Could not read the profile: " + e.getMessage());
        }
    }

    private static String expand(String path) {
        return path != null && path.startsWith("~")
                ? System.getProperty("user.home") + path.substring(1) : path;
    }
}
