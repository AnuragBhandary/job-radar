package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.mail.GmailConnection;
import com.anuragbhandary.jobradar.mail.InboxScanner;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Gmail: whether it is connected, and a button to fix it when it is not.
 *
 * <p>Exists because re-consenting is a recurring chore rather than a one-off.
 * An OAuth app whose consent screen is still in Testing is issued refresh tokens
 * that expire after seven days, so roughly weekly the reply scanner goes deaf and
 * the only cure is to approve it again. Behind a CLI command that is a chore that
 * gets skipped, and a tracker nobody updates is worse than no tracker.
 */
@Controller
public class MailController {

    private final GmailConnection connection;
    private final InboxScanner scanner;

    public MailController(GmailConnection connection, InboxScanner scanner) {
        this.connection = connection;
        this.scanner = scanner;
    }

    @GetMapping(value = "/mail", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String page(@RequestParam(required = false) String said) {
        GmailConnection.Status status = connection.status();

        StringBuilder body = new StringBuilder();
        if (said != null && !said.isBlank()) {
            body.append("<section class=\"card panel\"><div class=\"panel-body\">")
                    .append("<p class=\"check-q\">").append(Ui.esc(said)).append("</p>")
                    .append("</div></section>");
        }

        body.append("<section class=\"card panel\">")
                .append(Ui.panelHead("Gmail", badge(status)))
                .append("<div class=\"panel-body\">")
                .append(explain(status))
                .append(buttons(status))
                .append("</div></section>");

        body.append(whatItIsFor());

        String stat = switch (status.state()) {
            case CONNECTED -> "<strong>connected</strong>";
            case WAITING -> "waiting for approval";
            default -> "not connected";
        };
        return Ui.page("Mail", stat, body.toString(), Ui.Tab.MAIL);
    }

    @PostMapping("/mail/connect")
    public String connect(RedirectAttributes flash) {
        flash.addAttribute("said", connection.connect());
        return "redirect:/mail";
    }

    @PostMapping("/mail/disconnect")
    public String disconnect(RedirectAttributes flash) {
        flash.addAttribute("said", connection.disconnect());
        return "redirect:/mail";
    }

    // ------------------------------------------------------------------

    private static String badge(GmailConnection.Status status) {
        return switch (status.state()) {
            case CONNECTED -> Ui.badge("ok", "CONNECTED");
            case WAITING -> Ui.badge("warn", "WAITING");
            case FAILED -> Ui.badge("bad", "FAILED");
            default -> Ui.badge("origin", "NOT CONNECTED");
        };
    }

    private String explain(GmailConnection.Status status) {
        StringBuilder text = new StringBuilder();

        if (status.state() == GmailConnection.State.CONNECTED) {
            text.append("<p class=\"check-a\">Reading replies is switched on.</p>");
            status.since().ifPresent(when -> text.append("<p class=\"check-why\">Approved ")
                    .append(Ui.esc(ago(when)))
                    .append(". Access is read only: this cannot send, delete or label "
                            + "anything.</p>"));
            status.since()
                    .filter(when -> Duration.between(when, Instant.now()).toDays() >= 6)
                    .ifPresent(when -> text.append(
                            "<p class=\"submit-note\" style=\"margin-top:10px\">"
                                    + "That is close to seven days old. While the consent "
                                    + "screen is in Testing, Google expires these weekly, so "
                                    + "the next scan may ask for approval again. Reconnect "
                                    + "now if you would rather not be interrupted.</p>"));
        } else if (status.detail() != null) {
            text.append("<p class=\"check-q\">").append(Ui.esc(status.detail())).append("</p>");
        } else {
            text.append("<p class=\"check-q\">Not connected, so replies are not being read "
                    + "and the tracker's status column is whatever you last typed into it.</p>");
        }

        if (!scanner.isConfigured() && status.state() == GmailConnection.State.CONNECTED) {
            text.append("<p class=\"check-why\">The spreadsheet is not configured, so there "
                    + "is nothing for a reply to update yet.</p>");
        }
        return text.toString();
    }

    private static String buttons(GmailConnection.Status status) {
        if (status.state() == GmailConnection.State.UNCONFIGURED) {
            return "";
        }
        String primary = switch (status.state()) {
            case CONNECTED -> "Reconnect";
            case WAITING -> "Open the window again";
            default -> "Connect Gmail";
        };
        StringBuilder html = new StringBuilder("<div class=\"assist-actions\">")
                .append("<form method=\"post\" action=\"/mail/connect\">")
                .append("<button class=\"btn btn-primary\" type=\"submit\"")
                .append(status.busy() ? " disabled" : "").append('>')
                .append(primary).append("</button></form>");

        if (status.state() == GmailConnection.State.CONNECTED) {
            html.append("<form method=\"post\" action=\"/mail/disconnect\">")
                    .append("<button class=\"btn\" type=\"submit\">Disconnect</button></form>");
        }
        if (status.busy()) {
            html.append("<span class=\"note-muted\">waiting for Google</span>");
        }
        html.append("</div>");

        if (status.state() == GmailConnection.State.CONNECTED) {
            html.append("<p class=\"caption\">Reconnect throws the current approval away and "
                    + "asks for a new one. Use it when a scan starts failing.</p>");
        }
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
