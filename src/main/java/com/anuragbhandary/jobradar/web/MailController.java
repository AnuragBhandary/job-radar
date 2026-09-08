package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.mail.GmailConnection;
import com.anuragbhandary.jobradar.mail.InboxScanner;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Gmail: whether it is connected, and a link to fix it when it is not.
 *
 * <p>Consent is a link the user clicks and a callback that lands back here. The
 * application is already a web server with a browser pointed at it, so there is
 * nothing to open and no second port to collide with.
 */
@Controller
public class MailController {

    /** Must match byte-for-byte between the approval link and the exchange. */
    private static final String CALLBACK_PATH = "/mail/callback";

    private final GmailConnection connection;
    private final InboxScanner scanner;

    public MailController(GmailConnection connection, InboxScanner scanner) {
        this.connection = connection;
        this.scanner = scanner;
    }

    @GetMapping(value = "/mail", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String page(@RequestParam(required = false) String said, HttpServletRequest request) {
        GmailConnection.Status status = connection.status();

        StringBuilder body = new StringBuilder();
        if (said != null && !said.isBlank()) {
            body.append("<section class=\"card panel\"><div class=\"panel-body\">")
                    .append("<p class=\"check-a\">").append(Ui.esc(said)).append("</p>")
                    .append("</div></section>");
        }

        body.append("<section class=\"card panel\">")
                .append(Ui.panelHead("Gmail", badge(status)))
                .append("<div class=\"panel-body\">")
                .append(explain(status))
                .append(actions(status, request))
                .append("</div></section>")
                .append(whatItIsFor());

        String stat = status.connected() ? "<strong>connected</strong>" : "not connected";
        return Ui.page("Mail", stat, body.toString(), Ui.Tab.MAIL);
    }

    /**
     * Where Google sends the browser back to.
     *
     * <p>A GET, because Google redirects here; there is no form to post. It is
     * the one route that changes state on a GET, and the alternative is a page
     * that asks the user to press a button to finish something they have already
     * approved.
     */
    @GetMapping("/mail/callback")
    public String callback(@RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpServletRequest request,
            RedirectAttributes flash) {

        flash.addAttribute("said", connection.complete(code, error, redirectUri(request)));
        return "redirect:/mail";
    }

    @PostMapping("/mail/disconnect")
    public String disconnect(RedirectAttributes flash) {
        flash.addAttribute("said", connection.disconnect());
        return "redirect:/mail";
    }

    // ------------------------------------------------------------------

    /**
     * The callback address, rebuilt from the request that is asking.
     *
     * <p>Not a constant, because it has to agree with whatever host and port the
     * browser actually reached: "localhost" and "127.0.0.1" are the same machine
     * and different strings, and Google compares strings.
     */
    private static String redirectUri(HttpServletRequest request) {
        String base = request.getScheme() + "://" + request.getServerName();
        int port = request.getServerPort();
        boolean standard = ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
        return base + (standard ? "" : ":" + port) + CALLBACK_PATH;
    }

    private static String badge(GmailConnection.Status status) {
        return switch (status.state()) {
            case CONNECTED -> Ui.badge("ok", "CONNECTED");
            case FAILED -> Ui.badge("bad", "NOT CONNECTED");
            default -> Ui.badge("origin", "NOT CONNECTED");
        };
    }

    private String explain(GmailConnection.Status status) {
        StringBuilder text = new StringBuilder();

        if (status.connected()) {
            text.append("<p class=\"check-a\">Reading replies is switched on.</p>");
            status.since().ifPresent(when -> text.append("<p class=\"check-why\">Approved ")
                    .append(Ui.esc(ago(when)))
                    .append(". Access is read only: this cannot send, delete or "
                            + "label anything.</p>"));
            if (status.nearlyStale()) {
                text.append("<p class=\"submit-note\" style=\"margin-top:10px\">"
                        + "That approval is close to seven days old. While the consent "
                        + "screen is in Testing, Google expires these weekly, so the next "
                        + "scan may stop working. Reconnect now if you would rather not "
                        + "be interrupted.</p>");
            }
            if (!scanner.isConfigured()) {
                text.append("<p class=\"check-why\">The spreadsheet is not configured, so "
                        + "there is nothing for a reply to update yet.</p>");
            }
        } else if (status.detail() != null) {
            text.append("<p class=\"check-q\">").append(Ui.esc(status.detail())).append("</p>");
        } else {
            text.append("<p class=\"check-q\">Not connected, so replies are not being read "
                    + "and the tracker's status column is whatever you last typed into "
                    + "it.</p>");
        }
        return text.toString();
    }

    private String actions(GmailConnection.Status status, HttpServletRequest request) {
        if (status.state() == GmailConnection.State.UNCONFIGURED) {
            return "";
        }
        Optional<String> link = connection.approvalLink(redirectUri(request));
        if (link.isEmpty()) {
            return "<p class=\"check-q\">Could not build the approval link. "
                    + "Check the OAuth client JSON.</p>";
        }

        StringBuilder html = new StringBuilder("<div class=\"assist-actions\">")
                // A plain link, not a button that posts. The whole point of the
                // rewrite is that nothing here tries to open a browser itself.
                .append("<a class=\"btn btn-primary\" href=\"").append(Ui.esc(link.get()))
                .append("\">")
                .append(status.connected() ? "Reconnect with Google" : "Approve with Google")
                .append("</a>");

        if (status.connected()) {
            html.append("<form method=\"post\" action=\"/mail/disconnect\">")
                    .append("<button class=\"btn\" type=\"submit\">Disconnect</button></form>");
        }
        html.append("</div>");

        html.append("<p class=\"caption\">Opens Google in this tab. Pick the mailbox you "
                + "want read, which may not be the account Google offers first.</p>");
        return html.toString();
    }

    private String whatItIsFor() {
        return """
                <section class="card panel">
                  <div class="panel-head"><h2>What this is for</h2></div>
                  <div class="panel-body">
                    <p class="check-q">With Gmail connected, <code>inbox</code> reads the
                    last 60 days, matches replies to rows in the tracker, and proposes a
                    status change for each one. It prints what it would change and changes
                    nothing until you pass <code>--apply</code>.</p>
                    <p class="check-why">That split is deliberate. The classifier is reading
                    prose written by strangers, and a wrong "Rejected" makes a live
                    application look dead and stops you following it up.</p>
                  </div>
                </section>
                """;
    }

    /** Rough, and rough is what a person wants here. */
    private static String ago(Instant when) {
        Duration since = Duration.between(when, Instant.now());
        long days = since.toDays();
        if (days >= 2) {
            return days + " days ago";
        }
        if (days == 1) {
            return "yesterday";
        }
        long hours = since.toHours();
        if (hours >= 1) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        return "just now";
    }
}
