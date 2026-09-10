package com.anuragbhandary.jobradar.web;

import java.util.List;

/**
 * The shared component layer: one implementation of each idea the pages reuse.
 *
 * <p>Written after a review of the rendered screens found the same four ideas
 * built four times over - a heading with a count, a row that carries an action, a
 * coloured chip, a block that says nothing is here. Six near-identical
 * implementations is how an application stops looking like one application, and
 * it is also how a state comes to mean one thing on Today and another on Jobs.
 *
 * <h2>State is never colour alone</h2>
 * {@link #state} always emits a dot, a word and a hue. Colour is the fastest of
 * the three to read and the only one that fails on a bad screen, a projector, or
 * for the eight percent of men who cannot separate red from green - so it is
 * never asked to carry the meaning by itself.
 *
 * <h2>Six meanings, six colours, and no others</h2>
 * <ul>
 *   <li>{@link Tone#OK} green - ready, complete, high confidence</li>
 *   <li>{@link Tone#WARN} yellow - review, approval, medium confidence</li>
 *   <li>{@link Tone#BAD} red - action required, conflict, a real problem</li>
 *   <li>{@link Tone#MANUAL} orange - <b>the website</b> needs a person</li>
 *   <li>{@link Tone#INFO} blue - informational, active, navigation</li>
 *   <li>{@link Tone#QUIET} neutral - passive, done with, background</li>
 * </ul>
 * MANUAL is orange and not yellow on purpose. "Read this before it goes" and
 * "the browser has done all it safely can" are acted on by different people at
 * different times, and they shared a colour until this phase.
 */
final class Parts {

    private Parts() {
    }

    /** The six meanings a piece of state may have. There is no seventh. */
    enum Tone {
        OK("ok"), WARN("warn"), BAD("bad"), MANUAL("manual"), INFO("info"), QUIET("quiet");

        private final String css;

        Tone(String css) {
            this.css = css;
        }

        String css() {
            return css;
        }
    }

    // ------------------------------------------------------------------
    // Page structure
    // ------------------------------------------------------------------

    /**
     * The one heading a page gets.
     *
     * @param lede    one line saying what the page is for, or null
     * @param actions page-level buttons, already rendered, or null
     */
    static String pageHead(String title, String lede, String actions) {
        return """
                <header class="page-head">
                  <div>
                    <h1>%s</h1>
                    %s
                  </div>
                  %s
                </header>
                """.formatted(Ui.esc(title),
                        lede == null || lede.isBlank() ? ""
                                : "<p class=\"lede\">" + Ui.esc(lede) + "</p>",
                        actions == null || actions.isBlank() ? ""
                                : "<div class=\"page-actions\">" + actions + "</div>");
    }

    /**
     * A titled group of things.
     *
     * <p>A rule under a small uppercase title, not a card. Boxing every group in
     * its own surface is what made the earlier pages read as a pile of unrelated
     * panels rather than as one document.
     */
    static String block(String title, String count, String aside, String body) {
        return """
                <section class="block">
                  <div class="block-head">
                    <h2>%s</h2>
                    %s
                    %s
                  </div>
                  %s
                </section>
                """.formatted(Ui.esc(title),
                        count == null ? "" : "<span class=\"count\">" + Ui.esc(count) + "</span>",
                        aside == null ? "" : "<span class=\"block-aside\">" + aside + "</span>",
                        body);
    }

    static String block(String title, String count, String body) {
        return block(title, count, null, body);
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** A dot, a word and a hue. Never fewer than all three. */
    static String state(Tone tone, String label) {
        return "<span class=\"state state-" + tone.css() + "\">" + Ui.esc(label) + "</span>";
    }

    /** A pill, for a classification rather than a status: a lane, a country. */
    static String tag(Tone tone, String label) {
        return "<span class=\"tag tag-" + tone.css() + "\">" + Ui.esc(label) + "</span>";
    }

    static String tag(String label) {
        return "<span class=\"tag\">" + Ui.esc(label) + "</span>";
    }

    static String requiredTag() {
        return "<span class=\"tag tag-req\">required</span>";
    }

    /**
     * The readiness bar: one segment per bucket, in the order they matter.
     *
     * <p>Segmented rather than one fill at a percentage, because the segments are
     * the actual buckets and a single number hides which of them is which. "91%
     * prepared" and "91% prepared, and the missing 9% is a question only you can
     * answer" are different sentences.
     *
     * @param counts done, awaiting approval, awaiting answer, blocked, passed over
     */
    static String readyBar(int done, int approval, int answer, int blocked, int passed) {
        int total = done + approval + answer + blocked + passed;
        if (total == 0) {
            return "";
        }
        StringBuilder bar = new StringBuilder("<div class=\"readybar\" role=\"img\" aria-label=\"")
                .append(done).append(" of ").append(total).append(" fields prepared\">");
        segment(bar, "rb-ok", done, total);
        segment(bar, "rb-warn", approval, total);
        segment(bar, "rb-bad", answer, total);
        segment(bar, "rb-manual", blocked, total);
        segment(bar, "rb-quiet", passed, total);
        return bar.append("</div>").toString();
    }

    private static void segment(StringBuilder bar, String css, int count, int total) {
        if (count > 0) {
            bar.append("<span class=\"").append(css).append("\" style=\"width:")
                    .append(Math.round(count * 1000.0 / total) / 10.0).append("%\"></span>");
        }
    }

    /**
     * Where the application has got to, in the applicant's words.
     *
     * @param steps  the labels, in order
     * @param at     the index currently being worked on, or reached
     * @param marks  a tone per step, for the one that is blocked rather than done
     */
    static String stepper(List<String> steps, int at, Tone blockedTone, int blockedIndex) {
        StringBuilder out = new StringBuilder("<ol class=\"stepper\">");
        for (int i = 0; i < steps.size(); i++) {
            String css = i == blockedIndex ? "is-blocked"
                    : i < at ? "is-done" : i == at ? "is-current" : "";
            String mark = "is-done".equals(css) ? "&#10003;" : "";
            out.append("<li class=\"").append(css).append("\">")
                    .append("<span class=\"step-mark\" aria-hidden=\"true\">").append(mark)
                    .append("</span>").append(Ui.esc(steps.get(i))).append("</li>");
        }
        return out.append("</ol>").toString();
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    /**
     * One number worth knowing.
     *
     * <p>Only outcomes belong here. How many rows are in the posting table is a
     * fact about the database, and putting it in the largest type on the home
     * page told him how hard the scraper had worked rather than how his search
     * was going.
     */
    static String stat(String number, String label) {
        return """
                <div><span class="stat-n">%s</span><span class="stat-k">%s</span></div>
                """.formatted(Ui.esc(number), Ui.esc(label));
    }

    /**
     * A queue row: what it is, why it is here, and the one button.
     *
     * <p>Everything needed to act is on the line. Having to open a card to find
     * out whether it wants thirty seconds or twenty minutes is what makes a queue
     * go unread.
     */
    static String task(Tone tone, String state, String title, String subtitle,
            String why, String meta, String action) {
        return """
                <article class="task edge-%s">
                  <div class="task-main">
                    <div class="task-title"><strong>%s</strong>%s</div>
                    %s
                    <div class="task-meta">%s%s</div>
                  </div>
                  <div class="task-side">%s</div>
                </article>
                """.formatted(tone.css(), Ui.esc(title),
                        subtitle == null || subtitle.isBlank() ? ""
                                : "<span class=\"task-role\">" + Ui.esc(subtitle) + "</span>",
                        why == null || why.isBlank() ? ""
                                : "<p class=\"task-why\">" + Ui.esc(why) + "</p>",
                        state(tone, state), meta == null ? "" : meta, action);
    }

    /** Nothing here, and what to do about it. Never a blank rectangle. */
    static String blank(String headline, String explanation, String action) {
        return """
                <div class="blank">
                  <h3>%s</h3>
                  <p>%s</p>
                  %s
                </div>
                """.formatted(Ui.esc(headline), Ui.esc(explanation),
                        action == null ? "" : action);
    }

    /** A form that posts one thing and navigates. The only kind of button that acts. */
    static String post(String action, String label, String classes, String hidden) {
        return """
                <form method="post" action="%s" class="inline-form">
                  %s<button class="%s" type="submit" data-busy="working">%s</button>
                </form>
                """.formatted(action, hidden == null ? "" : hidden, classes, Ui.esc(label));
    }

    static String hidden(String name, Object value) {
        return "<input type=\"hidden\" name=\"" + name + "\" value=\""
                + Ui.esc(String.valueOf(value)) + "\">";
    }

    /** A link styled as a button, for actions that only navigate. */
    static String linkButton(String href, String label, String classes) {
        return "<a class=\"" + classes + "\" href=\"" + href + "\">" + Ui.esc(label) + "</a>";
    }
}
