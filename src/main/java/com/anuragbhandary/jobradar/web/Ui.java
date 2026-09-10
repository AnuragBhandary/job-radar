package com.anuragbhandary.jobradar.web;

import java.util.List;

/**
 * Page chrome and the stylesheet.
 *
 * <p>The design came out of a Lovable project (September 2026), briefed as a
 * static prototype rather than as its usual React app precisely so it could be
 * lifted into a server-rendered Java page without a build step. The original
 * prototype is kept in {@code docs/design/} so the two can be diffed when either
 * moves. Nothing here needs npm, a CDN or a webfont: the tool runs on localhost
 * and is expected to work with the network off.
 *
 * <p>Everything user-supplied is escaped by {@link #esc}. The values on these
 * pages come from job boards - titles, field labels, cover-letter text - which is
 * third-party content rendered into a page with buttons that submit real
 * applications. Being a local single-user server lowers the stakes and does not
 * remove them.
 */
final class Ui {

    private Ui() {
    }

    /**
     * @param stat the right-hand side of the top bar. Different per page, because
     *             the useful number on the queue is how many candidates there are
     *             and on a review page it is which attempt this is.
     */
    /**
     * Which top-bar tab is lit. A posting and a review both hang off the feed, so
     * they say FEED rather than going unmarked: the tab answers "where am I in
     * this tool", not "which URL is this".
     */
    /**
     * Seven sections, named for what a person does there.
     *
     * <p>"Board" and "Setup" were the words the code used; "Applications" and
     * "Settings" are the words a person would look for. The open-questions page
     * lost its own tab in the same pass - it is a view of knowledge, and giving
     * it a tab beside Knowledge meant two places to look for the same answers.
     */
    enum Tab {
        TODAY, JOBS, BOARD, KNOWLEDGE, STRATEGY, ASSISTANT, SETUP
    }

    static String page(String title, String stat, String body) {
        return page(title, stat, body, Tab.JOBS);
    }

    static String page(String title, String stat, String body, Tab current) {
        return page(title, stat, body, current, null);
    }

    /**
     * @param script a second script served from {@code /static}, loaded after
     *               the shared one. Only the preparation screen has enough
     *               behaviour of its own to want a file; every other page is
     *               served by {@code app.js} alone.
     */
    static String page(String title, String stat, String body, Tab current, String script) {
        String extra = script == null ? ""
                // Fingerprinted like the other two. A page-specific script that
                // caches while the shared one does not is the worst of both.
                : "<script src=\"" + script + "?v=" + fingerprint("/static" + script)
                        + "\" defer></script>";
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s · job-radar</title>
                <link rel="stylesheet" href="%s"></head><body>
                <a class="skip" href="#main">Skip to the page</a>
                <header class="topbar">
                  <nav class="nav" aria-label="Sections">
                    <a class="wordmark" href="/">job-radar</a>
                    <a class="%s" href="/">Today</a>
                    <a class="%s" href="/jobs">Jobs</a>
                    <a class="%s" href="/board">Applications</a>
                    <a class="%s" href="/knowledge">Knowledge</a>
                    <a class="%s" href="/strategy">Strategy</a>
                    <a class="%s" href="/chat">Assistant</a>
                    <a class="%s" href="/setup">Settings</a>
                  </nav>
                  <div class="statstrip">%s</div>
                </header>
                <main class="page %s" id="main" tabindex="-1">%s</main>
                <script src="%s" defer></script>%s</body></html>
                """.formatted(esc(title), STYLESHEET,
                        on(current, Tab.TODAY), on(current, Tab.JOBS), on(current, Tab.BOARD),
                        on(current, Tab.KNOWLEDGE), on(current, Tab.STRATEGY),
                        on(current, Tab.ASSISTANT), on(current, Tab.SETUP),
                        stat, current == Tab.BOARD ? "wide" : "", body, SCRIPT, extra);
    }

    /**
     * Marks the section a page belongs to, for the eye and for a screen reader.
     *
     * <p>{@code aria-current="page"} rather than the class alone: the class draws
     * an underline, which is invisible to anything that is not looking at the
     * screen, and "where am I" is a question a keyboard user has too.
     */
    private static String on(Tab current, Tab tab) {
        return current == tab ? "is-active\" aria-current=\"page" : "";
    }

    static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** A section heading with its count, as used down the queue. */
    static String sectionHead(String title, String count) {
        return """
                <div class="section-head"><h2>%s</h2><span class="count">%s</span></div>
                """.formatted(esc(title), esc(count));
    }

    /** A panel heading with an optional right-hand note. */
    static String panelHead(String title, String aside) {
        return """
                <div class="panel-head"><h2>%s</h2>%s</div>
                """.formatted(esc(title), aside == null ? "" : aside);
    }

    /**
     * The chat page body.
     *
     * <p>Plain fetch and plain DOM. The whole page is a list of bubbles and a text
     * box, and reaching for a framework here would mean a build step for something
     * that is forty lines of vanilla JavaScript.
     */
    static String chat() {
        return """
                <section class="card panel">
                  <div class="panel-head">
                    <h2>Assistant</h2>
                    <form method="post" action="/chat/clear">
                      <button class="btn btn-sm" type="submit">new chat</button>
                    </form>
                  </div>
                  <div class="panel-body">
                    <div id="log" class="chatlog">
                      <div class="bubble bot">Ask me about the postings, your board, or what to apply to next. I can bookmark jobs and move them between columns. I cannot submit an application.</div>
                    </div>
                    <div class="suggest">
                      <button class="chip" type="button">Which 5 should I apply to this week, and why?</button>
                      <button class="chip" type="button">What is on my board right now?</button>
                      <button class="chip" type="button">Which remote jobs pay above my India band?</button>
                      <button class="chip" type="button">What should I learn to unlock more of these jobs?</button>
                    </div>
                    <textarea class="input" id="q" rows="2"
                      placeholder="Ask anything. Shift+Enter for a new line."></textarea>
                    <div class="assist-actions">
                      <button class="btn btn-primary" type="button" id="send">Send</button>
                      <span class="note-muted" id="status"></span>
                    </div>
                  </div>
                </section>
                <script>
                (function () {
                  var log = document.getElementById('log'),
                      box = document.getElementById('q'),
                      send = document.getElementById('send'),
                      status = document.getElementById('status');

                  function bubble(text, who) {
                    var d = document.createElement('div');
                    d.className = 'bubble ' + who;
                    d.textContent = text;
                    log.appendChild(d);
                    log.scrollTop = log.scrollHeight;
                    return d;
                  }

                  function ask() {
                    var text = box.value.trim();
                    if (!text) { return; }
                    bubble(text, 'me');
                    box.value = '';
                    send.disabled = true;
                    status.textContent = 'thinking...';

                    fetch('/chat/send', {
                      method: 'POST',
                      headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify({message: text})
                    }).then(function (r) { return r.json(); }).then(function (d) {
                      bubble(d.answer || '(nothing came back)', 'bot');
                      if (d.actions && d.actions.length) {
                        bubble(d.actions.join(', '), 'did');
                      }
                      status.textContent = '';
                      send.disabled = false;
                      box.focus();
                    }).catch(function (e) {
                      status.textContent = String(e);
                      send.disabled = false;
                    });
                  }

                  send.onclick = ask;
                  box.addEventListener('keydown', function (e) {
                    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); ask(); }
                  });
                  document.querySelectorAll('.suggest .chip').forEach(function (c) {
                    c.onclick = function () { box.value = c.textContent.trim(); ask(); };
                  });
                  box.focus();
                })();
                </script>
                """;
    }

    /** The score cell that opens a feed row. */
    static String scoreCell(com.anuragbhandary.jobradar.match.MatchScore score) {
        if (score == null) {
            return "<div class=\"score score-none\">—</div>";
        }
        return "<div class=\"score score-" + score.band().label() + "\">"
                + score.score() + "</div>";
    }

    static String badge(String kind, String text) {
        return "<span class=\"badge badge-" + kind + "\">" + esc(text) + "</span>";
    }

    static String noteMuted(String text) {
        return "<span class=\"note-muted\">" + esc(text) + "</span>";
    }

    /**
     * The assistant panel.
     *
     * <p>Three modes, separated because they carry different risk. An answer and a
     * letter are text that goes to an employer under his name, so both are
     * grounded in the profile and policed for tone; an ask is for him to read and
     * is neither. The panel says which is which rather than presenting one box
     * that quietly does three things.
     *
     * <p>Nothing here writes to a form. The answer box has a save button that
     * appends to applicant.yml, which is a second, deliberate click.
     */
    static String assistant(long attemptId, boolean usable, List<String> questions) {
        if (!usable) {
            return """
                    <section class="card panel">
                      <div class="panel-head"><h2>Assistant</h2></div>
                      <div class="panel-body">
                        <p class="empty">No model configured. Put a key in
                        <code>~/.config/job-radar/secrets.yml</code> under
                        <code>job-radar.llm</code> and restart.</p>
                      </div>
                    </section>
                    """;
        }

        StringBuilder chips = new StringBuilder();
        for (String question : questions) {
            chips.append("<button class=\"chip\" type=\"button\" data-q=\"")
                    .append(esc(question)).append("\">")
                    .append(esc(shorten(question))).append("</button>");
        }

        return """
                <section class="card panel">
                  <div class="panel-head"><h2>Assistant</h2>
                    <span class="note-muted">Google Gemini</span></div>
                  <div class="panel-body">
                    <div class="segmented" role="group" aria-label="Assistant mode">
                      <button type="button" data-mode="answer" aria-pressed="true">Draft an answer</button>
                      <button type="button" data-mode="letter" aria-pressed="false">Rewrite the letter</button>
                      <button type="button" data-mode="ask" aria-pressed="false">Ask about this posting</button>
                    </div>

                    <div class="chips">%s</div>

                    <textarea class="input" id="ap"
                      placeholder="Paste the question from the form."></textarea>

                    <div class="assist-actions">
                      <button class="btn btn-primary" type="button" id="ag">Draft it</button>
                      <span class="note-muted" id="as">nothing is sent to the job board from here</span>
                    </div>

                    <div class="result" id="ao" hidden>
                      <p id="at"></p>
                      <div class="result-actions">
                        <button class="btn btn-sm" type="button" id="ac">Copy</button>
                        <button class="btn btn-sm" type="button" id="av">Save to profile</button>
                        <span class="note-muted" id="am"></span>
                      </div>
                      <p class="note-muted" id="ar" hidden></p>
                    </div>
                  </div>
                </section>
                <script>
                (function () {
                  var id = %d, mode = 'answer', lastQuestion = '';
                  var box = document.getElementById('ap'),
                      out = document.getElementById('ao'),
                      text = document.getElementById('at'),
                      status = document.getElementById('as'),
                      rejected = document.getElementById('ar'),
                      saved = document.getElementById('am');

                  document.querySelectorAll('[data-mode]').forEach(function (b) {
                    b.onclick = function () {
                      document.querySelectorAll('[data-mode]').forEach(function (o) {
                        o.setAttribute('aria-pressed', String(o === b));
                      });
                      mode = b.dataset.mode;
                      box.placeholder = mode === 'letter'
                        ? 'What should change? Leave empty for a straight rewrite.'
                        : mode === 'ask'
                          ? 'Does this posting say anything about sponsorship?'
                          : 'Paste the question from the form.';
                      document.getElementById('av').hidden = mode !== 'answer';
                    };
                  });

                  document.querySelectorAll('.chip').forEach(function (c) {
                    c.onclick = function () { box.value = c.dataset.q; box.focus(); };
                  });

                  document.getElementById('ag').onclick = function () {
                    lastQuestion = box.value;
                    status.textContent = 'thinking...';
                    saved.textContent = '';
                    rejected.hidden = true;
                    fetch('/assistant', {
                      method: 'POST',
                      headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify({attemptId: id, mode: mode, text: box.value})
                    }).then(function (r) { return r.json(); }).then(function (d) {
                      out.hidden = !d.ok;
                      text.textContent = d.text || '';
                      if (!d.ok) {
                        status.textContent = d.note || 'nothing came back';
                        return;
                      }
                      status.textContent = mode === 'letter'
                        ? 'saved to this attempt' : 'nothing is sent to the job board from here';
                      // Why the first draft was thrown away. Shown, not hidden:
                      // seeing it is how the phrase filter earns any trust.
                      if (d.note) { rejected.textContent = d.note; rejected.hidden = false; }
                    }).catch(function (e) { status.textContent = String(e); });
                  };

                  document.getElementById('ac').onclick = function () {
                    navigator.clipboard.writeText(text.textContent);
                    saved.textContent = 'copied';
                  };

                  document.getElementById('av').onclick = function () {
                    fetch('/assistant/save-answer', {
                      method: 'POST',
                      headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify({match: lastQuestion, answer: text.textContent})
                    }).then(function (r) { return r.json(); }).then(function (d) {
                      saved.textContent = d.note || '';
                    });
                  };
                })();
                </script>
                """.formatted(chips, attemptId);
    }

    private static String shorten(String question) {
        String flat = question.replaceAll("\\s+", " ").trim();
        return flat.length() <= 40 ? flat : flat.substring(0, 38) + "...";
    }

    /**
     * The stylesheet's address, with a fingerprint on it.
     *
     * <p>The CSS was a 1,290-line text block in this file until the preparation
     * screen needed a screenful more of it. Moving it to
     * {@code src/main/resources/static/app.css} makes it CSS again - editable,
     * highlighted, diffable - and costs nothing: it is served by this same
     * process off the classpath, so the tool still works with the network off and
     * still needs no npm, no CDN and no build step.
     *
     * <p>The query string is the file's own content hash, computed once at class
     * load. Without it a browser keeps yesterday's stylesheet and the page looks
     * broken in a way that has nothing to do with the change just made - which is
     * the standard reason people do not split CSS out of their templates.
     */
    private static final String STYLESHEET = "/app.css?v=" + fingerprint("/static/app.css");

    /**
     * The shared behaviour, fingerprinted the same way.
     *
     * <p>Was an inline {@code <script>} block in this file. Moving it out is the
     * same trade the stylesheet made: it becomes JavaScript again - lintable,
     * diffable, cacheable - and costs nothing, because it is served by this
     * process off the classpath with the network off.
     */
    private static final String SCRIPT = "/app.js?v=" + fingerprint("/static/app.js");

    private static String fingerprint(String resource) {
        try (java.io.InputStream in = Ui.class.getResourceAsStream(resource)) {
            if (in == null) {
                return "0";
            }
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(in.readAllBytes());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            // A stylesheet that cannot be fingerprinted still has to be served.
            // Losing the cache-busting is a nuisance; refusing to render a page
            // because of it would be absurd.
            return "0";
        }
    }

}
