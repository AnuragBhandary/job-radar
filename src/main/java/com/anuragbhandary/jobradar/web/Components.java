package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.match.MatchScore;
import com.anuragbhandary.jobradar.money.AskingPrice;
import java.util.List;
import java.util.Optional;

/**
 * The pieces every page is built from.
 *
 * <p>Here because the alternative was what this replaced: each controller
 * assembling its own markup with a StringBuilder, so a card on the board and a
 * card on the queue drifted apart one edit at a time, and an empty state was
 * whatever the author of that page felt like writing that day.
 *
 * <p>Static methods returning strings, not a template engine. The templates would
 * be a build step and a second language for a UI that is five pages of a
 * single-user tool; the point of collecting them here is consistency, which plain
 * methods give just as well.
 *
 * <p>Every component takes its content already escaped or escapes it itself, and
 * says which in its signature. Values on these pages come from job boards.
 */
final class Components {

    private Components() {
    }

    // ------------------------------------------------------------------
    // Surfaces
    // ------------------------------------------------------------------

    /** A titled panel. {@code body} is raw HTML; {@code title} is escaped. */
    static String panel(String title, String aside, String body) {
        return "<section class=\"card panel\">"
                + Ui.panelHead(title, aside)
                + "<div class=\"panel-body\">" + body + "</div></section>";
    }

    /** A section of the page: a heading, a count, and whatever follows. */
    static String section(String title, String count, String body) {
        return "<section class=\"section\">" + Ui.sectionHead(title, count) + body + "</section>";
    }

    /**
     * What to show when there is nothing.
     *
     * <p>Takes a next action rather than only a sentence. An empty state that
     * explains the emptiness and stops there leaves the reader where they were;
     * the useful ones say what would fill it.
     */
    static String empty(String headline, String explanation, String href, String action) {
        String cta = href == null ? ""
                : "<p class=\"empty-action\"><a class=\"btn\" href=\"" + Ui.esc(href) + "\">"
                        + Ui.esc(action) + "</a></p>";
        return "<div class=\"card blank\"><p class=\"blank-head\">" + Ui.esc(headline) + "</p>"
                + "<p class=\"blank-why\">" + Ui.esc(explanation) + "</p>" + cta + "</div>";
    }

    /**
     * Feedback after an action, as a page element rather than a floating widget.
     *
     * <p>Every mutation here is a form post followed by a redirect, so the
     * message arrives on a fresh page load and there is no client state to keep.
     * It fades on a timer in CSS and stays readable if the reader is slow.
     */
    static String toast(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return "<div class=\"toast\" role=\"status\">" + Ui.esc(message) + "</div>";
    }

    // ------------------------------------------------------------------
    // Numbers
    // ------------------------------------------------------------------

    /** One figure with its label. The number leads; the word explains it. */
    static String tile(String number, String label, String note, boolean quiet) {
        return "<div class=\"tile" + (quiet ? " tile-quiet" : "") + "\">"
                + "<span class=\"tile-n\">" + Ui.esc(number) + "</span>"
                + "<span class=\"tile-l\">" + Ui.esc(label) + "</span>"
                + (note == null ? "" : "<span class=\"tile-note\">" + Ui.esc(note) + "</span>")
                + "</div>";
    }

    /**
     * What to ask for, in the currency of the job and in rupees.
     *
     * <p>Compact by design: on a list of forty rows the reader wants to know
     * whether a job clears the bar, not to do arithmetic. The full breakdown,
     * including the monthly figures and the rate used, is on the posting page.
     */
    static String money(Optional<AskingPrice> price) {
        return price.map(p -> "<span class=\"money\" title=\"What to put in the salary box\">"
                + "<span class=\"money-main\">" + Ui.esc(p.annualRange()) + "</span>"
                + (p.convertible()
                        ? "<span class=\"money-inr\">" + Ui.esc(p.annualInRupees()) + "</span>"
                        : "")
                + "</span>").orElse("");
    }

    /** The same figures in full, for a page where there is room to think. */
    static String moneyDetail(AskingPrice p) {
        String rate = p.convertible()
                ? "<p class=\"check-why\">Converted at 1 " + Ui.esc(p.currency()) + " = Rs "
                        + p.inrRate().stripTrailingZeros().toPlainString()
                        + ", set " + Ui.esc(p.ratesAs())
                        + ". For orientation only: the figure that goes on the form is "
                        + Ui.esc(p.currency()) + "."
                : "";
        return """
                <div class="paypair">
                  <div><span class="pay-l">A year</span><span class="pay-v">%s</span>%s</div>
                  <div><span class="pay-l">A month</span><span class="pay-v">%s</span>%s</div>
                </div>%s
                """.formatted(
                        Ui.esc(p.annualRange()),
                        p.convertible()
                                ? "<span class=\"pay-inr\">" + Ui.esc(p.annualInRupees()) + "</span>"
                                : "",
                        Ui.esc(p.monthlyRange()),
                        p.convertible()
                                ? "<span class=\"pay-inr\">" + Ui.esc(p.monthlyInRupees()) + "</span>"
                                : "",
                        rate);
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    /**
     * One thing waiting on a decision.
     *
     * <p>Structured so the eye reads why it is here, then what it is, then what
     * to do. The action is a real link, never a "learn more".
     */
    static String task(String tone, String kind, String title, String detail,
            String href, String action) {
        return """
                <article class="task task-%s">
                  <span class="task-kind">%s</span>
                  <div class="task-main">
                    <span class="task-title">%s</span>
                    <span class="task-detail">%s</span>
                  </div>
                  <a class="btn btn-sm" href="%s">%s</a>
                </article>
                """.formatted(Ui.esc(tone), Ui.esc(kind), Ui.esc(title), Ui.esc(detail),
                        Ui.esc(href), Ui.esc(action));
    }

    /**
     * A posting in a list.
     *
     * <p>{@code trailing} is raw HTML for the buttons, because what you can do to
     * a posting differs by page and threading every variation through as flags
     * produces a method nobody can read.
     */
    static String jobRow(long postingId, MatchScore score, String company, String role,
            List<String> meta, String why, Optional<AskingPrice> price, String trailing) {

        StringBuilder metaHtml = new StringBuilder("<div class=\"meta\">");
        for (int i = 0; i < meta.size(); i++) {
            if (i > 0) {
                metaHtml.append("<span class=\"dot\">·</span>");
            }
            metaHtml.append(meta.get(i));
        }
        metaHtml.append(money(price)).append("</div>");

        return """
                <article class="row">
                  %s
                  <div class="row-main">
                    <div class="row-title">
                      <a class="company" href="/posting/%d">%s</a>
                      <span class="role">%s</span>
                    </div>
                    %s
                    %s
                  </div>
                  <div class="row-side">%s</div>
                </article>
                """.formatted(
                        Ui.scoreCell(score), postingId, Ui.esc(company), Ui.esc(role),
                        metaHtml,
                        why == null || why.isBlank() ? ""
                                : "<div class=\"why\">" + Ui.esc(why) + "</div>",
                        trailing);
    }

    /**
     * A button that posts a form.
     *
     * <p>Carries {@code data-busy} so one small script can put every submit
     * button in the application into a pending state without each page inventing
     * its own. Preparing an application takes about forty seconds, and a button
     * that looks untouched for forty seconds reads as a broken page.
     */
    static String postButton(String action, String label, String busyLabel,
            String hiddenName, Object hiddenValue, String classes) {
        return """
                <form method="post" action="%s" class="act">
                  <input type="hidden" name="%s" value="%s">
                  <button class="%s" type="submit" data-busy="%s">%s</button>
                </form>
                """.formatted(Ui.esc(action), Ui.esc(hiddenName), Ui.esc(String.valueOf(hiddenValue)),
                        Ui.esc(classes), Ui.esc(busyLabel), Ui.esc(label));
    }
}
