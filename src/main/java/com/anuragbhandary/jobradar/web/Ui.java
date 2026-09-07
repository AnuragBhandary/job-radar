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
    static String page(String title, String stat, String body) {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s · job-radar</title><style>%s</style></head><body>
                <header class="topbar">
                  <a class="wordmark" href="/">job-radar</a>
                  <div class="statstrip">%s</div>
                </header>
                <main class="page">%s</main></body></html>
                """.formatted(esc(title), css(), stat, body);
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

    private static String css() {
        return """
                :root {
                  --font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
                               "Helvetica Neue", Arial, sans-serif;
                  --font-mono: ui-monospace, SFMono-Regular, Menlo, Consolas,
                               "Liberation Mono", monospace;
                  --fs-xs: 11px; --fs-sm: 12px; --fs-base: 13px;
                  --fs-md: 14px; --fs-lg: 16px; --fs-xl: 20px;
                  --sp-1: 4px; --sp-2: 6px; --sp-3: 10px;
                  --sp-4: 14px; --sp-5: 20px; --sp-6: 28px;
                  --radius: 5px;
                  --bg: #f7f7f6; --bg-panel: #ffffff; --bg-subtle: #f0f0ee;
                  --bg-inset: #fafaf9; --fg: #16181c; --fg-muted: #6a6f78;
                  --fg-faint: #8d939c; --border: #e0e0dd; --border-strong: #c9c9c5;
                  --accent: #2f5fd0; --accent-fg: #ffffff; --accent-soft: #e8eefc;
                  --ok: #1c7a4a; --ok-soft: #e2f3e9;
                  --warn: #8a5a08; --warn-soft: #fbf0d9; --warn-border: #e8d3a3;
                  --bad: #b3261e; --bad-soft: #fbe6e4;
                  --shadow: 0 1px 2px rgba(16, 18, 22, 0.06);
                }
                @media (prefers-color-scheme: dark) {
                  :root {
                    --bg: #0e0f11; --bg-panel: #16181b; --bg-subtle: #1c1f23;
                    --bg-inset: #121417; --fg: #e6e8ea; --fg-muted: #9aa1a9;
                    --fg-faint: #767d85; --border: #26292e; --border-strong: #343940;
                    --accent: #7aa2f7; --accent-fg: #0e0f11; --accent-soft: #1a2130;
                    --ok: #56c48a; --ok-soft: #14251c;
                    --warn: #e0b048; --warn-soft: #2a2113; --warn-border: #4b3c17;
                    --bad: #f0776c; --bad-soft: #2c1715;
                    --shadow: none;
                  }
                }
                * { box-sizing: border-box; }
                html { -webkit-text-size-adjust: 100%; }
                body {
                  margin: 0; background: var(--bg); color: var(--fg);
                  font-family: var(--font-sans); font-size: var(--fs-base); line-height: 1.45;
                }
                a { color: var(--accent); text-decoration: none; }
                a:hover { text-decoration: underline; }
                h1, h2, h3 { margin: 0; font-weight: 600; }
                code { font-family: var(--font-mono); font-size: var(--fs-xs); }

                .topbar {
                  position: sticky; top: 0; z-index: 10;
                  display: flex; align-items: center; justify-content: space-between;
                  gap: var(--sp-4); padding: var(--sp-3) var(--sp-5);
                  background: var(--bg-panel); border-bottom: 1px solid var(--border);
                }
                .wordmark {
                  font-family: var(--font-mono); font-size: var(--fs-md); font-weight: 600;
                  letter-spacing: -0.02em; color: var(--fg);
                }
                .wordmark:hover { text-decoration: none; }
                .statstrip {
                  font-size: var(--fs-sm); color: var(--fg-muted);
                  font-variant-numeric: tabular-nums;
                }
                .statstrip strong { color: var(--fg); font-weight: 600; }
                .statstrip .sep { color: var(--fg-faint); margin: 0 var(--sp-2); }

                .page { max-width: 1120px; margin: 0 auto; padding: var(--sp-5) var(--sp-5) 64px; }

                .filters {
                  display: flex; flex-wrap: wrap; align-items: center;
                  gap: var(--sp-1) var(--sp-3);
                  padding-bottom: var(--sp-4); margin-bottom: var(--sp-4);
                  border-bottom: 1px solid var(--border); font-size: var(--fs-sm);
                }
                .filters a { color: var(--fg-muted); padding: 2px 6px; border-radius: var(--radius); }
                .filters a:hover { background: var(--bg-subtle); text-decoration: none; }
                .filters a.is-active { color: var(--fg); background: var(--bg-subtle); font-weight: 600; }
                .filters a .n {
                  color: var(--fg-faint); font-variant-numeric: tabular-nums; margin-left: 4px;
                }

                .section { margin-bottom: var(--sp-6); }
                .section-head {
                  display: flex; align-items: baseline; gap: var(--sp-2); margin-bottom: var(--sp-2);
                }
                .section-head h2 {
                  font-size: var(--fs-sm); text-transform: uppercase;
                  letter-spacing: 0.06em; color: var(--fg-muted);
                }
                .section-head .count {
                  font-size: var(--fs-xs); color: var(--fg-faint);
                  font-variant-numeric: tabular-nums;
                }

                .card {
                  background: var(--bg-panel); border: 1px solid var(--border);
                  border-radius: var(--radius); box-shadow: var(--shadow);
                }
                .list { display: flex; flex-direction: column; }
                .list > .row + .row { border-top: 1px solid var(--border); }
                .row {
                  display: flex; align-items: flex-start; gap: var(--sp-4);
                  padding: var(--sp-3) var(--sp-4);
                }
                .row:hover { background: var(--bg-inset); }
                .row-main { flex: 1 1 auto; min-width: 0; }
                .row-side {
                  flex: 0 0 auto; display: flex; align-items: center;
                  gap: var(--sp-3); padding-top: 1px;
                }
                .row-title {
                  display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--sp-2);
                }
                .company { font-weight: 600; font-size: var(--fs-md); }
                .role { color: var(--fg-muted); font-size: var(--fs-base); }
                .meta {
                  margin-top: 3px; display: flex; flex-wrap: wrap; align-items: center;
                  gap: var(--sp-2); font-size: var(--fs-sm); color: var(--fg-muted);
                }
                .meta .dot { color: var(--fg-faint); }
                .reason { margin-top: 3px; font-size: var(--fs-sm); color: var(--fg-muted); }
                .empty { padding: var(--sp-3) var(--sp-4); font-size: var(--fs-sm); color: var(--fg-faint); }

                .badge {
                  display: inline-block; padding: 1px 6px; border-radius: 3px;
                  font-family: var(--font-mono); font-size: var(--fs-xs); font-weight: 600;
                  letter-spacing: 0.02em; line-height: 1.5; white-space: nowrap;
                  border: 1px solid transparent;
                }
                .badge-warn { color: var(--warn); background: var(--warn-soft); border-color: var(--warn-border); }
                .badge-ok { color: var(--ok); background: var(--ok-soft); border-color: var(--ok); }
                .badge-bad { color: var(--bad); background: var(--bad-soft); border-color: var(--bad); }
                .badge-country {
                  color: var(--fg-muted); background: var(--bg-subtle);
                  border-color: var(--border); font-weight: 500;
                }
                .badge-origin {
                  color: var(--fg-muted); background: transparent;
                  border-color: var(--border-strong); font-weight: 500;
                }
                .badge-soft { color: var(--accent); background: var(--accent-soft); border-color: transparent; }
                .tag {
                  display: inline-block; padding: 0 5px; font-size: var(--fs-xs);
                  color: var(--fg-muted); background: var(--bg-subtle);
                  border: 1px solid var(--border); border-radius: 3px; white-space: nowrap;
                }

                .btn {
                  font: inherit; font-size: var(--fs-sm); padding: 4px 10px;
                  border-radius: var(--radius); border: 1px solid var(--border-strong);
                  background: var(--bg-panel); color: var(--fg); cursor: pointer;
                }
                .btn:hover { background: var(--bg-subtle); }
                .btn-primary {
                  background: var(--accent); border-color: var(--accent);
                  color: var(--accent-fg); font-weight: 600;
                }
                .btn-primary:hover { filter: brightness(1.06); background: var(--accent); }
                .btn:disabled, .btn-primary:disabled { opacity: 0.45; cursor: not-allowed; filter: none; }
                .btn-sm { font-size: var(--fs-xs); padding: 2px 7px; }

                .crumbs { font-size: var(--fs-sm); color: var(--fg-muted); margin-bottom: var(--sp-3); }
                .attempt-head { padding: var(--sp-4); margin-bottom: var(--sp-5); }
                .attempt-head h1 { font-size: var(--fs-xl); letter-spacing: -0.01em; }
                .attempt-head .role { font-size: var(--fs-md); margin-top: 2px; display: block; }
                .attempt-head .head-top { display: flex; align-items: center; gap: var(--sp-3); }
                .resume-note {
                  margin-top: var(--sp-3); padding-top: var(--sp-3);
                  border-top: 1px dashed var(--border); font-style: italic;
                  font-size: var(--fs-sm); color: var(--fg-muted);
                }

                .panel { margin-bottom: var(--sp-5); }
                .panel-head {
                  display: flex; align-items: center; justify-content: space-between;
                  gap: var(--sp-3); padding: var(--sp-2) var(--sp-4);
                  border-bottom: 1px solid var(--border); background: var(--bg-inset);
                  border-radius: var(--radius) var(--radius) 0 0;
                }
                .panel-head h2 {
                  font-size: var(--fs-sm); text-transform: uppercase;
                  letter-spacing: 0.06em; color: var(--fg-muted);
                }
                .panel-body { padding: var(--sp-4); }

                .check { border-left: 3px solid var(--accent); }
                .check .panel-head { background: var(--accent-soft); }
                .check-item { padding: var(--sp-3) var(--sp-4); }
                .check-item + .check-item { border-top: 1px solid var(--border); }
                .check-q { font-size: var(--fs-base); color: var(--fg-muted); margin: 0; }
                .check-a {
                  margin: 2px 0 0; font-family: var(--font-mono); font-size: var(--fs-md);
                  font-weight: 600; color: var(--fg);
                }
                .check-why { margin: 2px 0 0; font-size: var(--fs-xs); color: var(--fg-faint); }

                .callout-warn { border-color: var(--warn-border); background: var(--warn-soft); }
                .callout-warn .panel-head {
                  background: transparent; border-bottom: 1px solid var(--warn-border);
                }
                .callout-warn .panel-head h2 { color: var(--warn); }
                .blocked-list { margin: 0; padding: var(--sp-3) var(--sp-4); list-style: none; }
                .blocked-list li + li { margin-top: var(--sp-2); }
                .blocked-list .q { font-weight: 600; }
                .blocked-list .why { color: var(--fg-muted); font-size: var(--fs-sm); }

                .letter { max-width: 68ch; }
                .letter p { margin: 0 0 var(--sp-3); font-size: var(--fs-md); line-height: 1.6; }
                .letter p:last-child { margin-bottom: 0; }

                .table-wrap { overflow-x: auto; }
                table.fields {
                  width: 100%; border-collapse: collapse; table-layout: fixed;
                  font-size: var(--fs-sm);
                }
                table.fields th {
                  text-align: left; font-size: var(--fs-xs); text-transform: uppercase;
                  letter-spacing: 0.05em; color: var(--fg-faint); font-weight: 600;
                  padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid var(--border);
                  background: var(--bg-inset); position: sticky; top: 0;
                }
                table.fields td {
                  padding: 5px var(--sp-3); border-bottom: 1px solid var(--border);
                  vertical-align: top;
                }
                table.fields tr:hover td { background: var(--bg-inset); }
                .col-status { width: 78px; }
                .col-label { width: 38%; }
                .truncate {
                  display: block; max-width: 100%; overflow: hidden;
                  text-overflow: ellipsis; white-space: nowrap;
                }
                .val { font-family: var(--font-mono); font-size: var(--fs-xs); }
                .val-muted {
                  color: var(--fg-muted); font-family: var(--font-sans);
                  font-size: var(--fs-xs); font-style: italic;
                }
                .st { font-family: var(--font-mono); font-size: var(--fs-xs); font-weight: 600; }
                .st-filled { color: var(--ok); }
                .st-skipped { color: var(--fg-faint); }
                .st-failed { color: var(--bad); }
                .cell-label { display: flex; gap: var(--sp-2); align-items: baseline; }

                /* The screenshot is 1440x4536. Boxed and scrollable, or it is the page. */
                .shot-frame {
                  height: 420px; overflow-y: auto; overflow-x: hidden;
                  border: 1px solid var(--border); border-radius: var(--radius);
                  background: var(--bg-inset);
                }
                .shot-frame.is-expanded { height: 1200px; }
                .shot-frame img { width: 100%; display: block; }
                .caption { margin-top: var(--sp-2); font-size: var(--fs-xs); color: var(--fg-faint); }

                .segmented {
                  display: inline-flex; border: 1px solid var(--border-strong);
                  border-radius: var(--radius); overflow: hidden;
                }
                .segmented button {
                  font: inherit; font-size: var(--fs-sm); padding: 4px 10px; border: none;
                  background: var(--bg-panel); color: var(--fg-muted); cursor: pointer;
                }
                .segmented button + button { border-left: 1px solid var(--border-strong); }
                .segmented button[aria-pressed="true"] {
                  background: var(--bg-subtle); color: var(--fg); font-weight: 600;
                }
                .chips { display: flex; flex-wrap: wrap; gap: var(--sp-2); margin: var(--sp-3) 0; }
                .chip {
                  font: inherit; font-size: var(--fs-xs); padding: 2px 8px;
                  border: 1px solid var(--border); border-radius: 999px;
                  background: var(--bg-subtle); color: var(--fg-muted); cursor: pointer;
                }
                .chip:hover { color: var(--fg); border-color: var(--border-strong); }
                textarea.input {
                  width: 100%; min-height: 76px; resize: vertical; font: inherit;
                  font-size: var(--fs-base); padding: var(--sp-3); color: var(--fg);
                  background: var(--bg-inset); border: 1px solid var(--border-strong);
                  border-radius: var(--radius);
                }
                textarea.input:focus { outline: 2px solid var(--accent); outline-offset: -1px; }
                .assist-actions {
                  display: flex; align-items: center; gap: var(--sp-3); margin-top: var(--sp-3);
                }
                .result {
                  margin-top: var(--sp-4); padding: var(--sp-3);
                  border: 1px solid var(--border); border-radius: var(--radius);
                  background: var(--bg-inset);
                }
                .result p {
                  margin: 0; font-size: var(--fs-md); line-height: 1.55;
                  max-width: 68ch; white-space: pre-wrap;
                }
                .result-actions {
                  display: flex; align-items: center; gap: var(--sp-2); margin-top: var(--sp-3);
                }
                .note-muted { font-size: var(--fs-xs); color: var(--fg-faint); }
                #ar { margin-top: var(--sp-3); }

                .submit-block {
                  border: 1px solid var(--border-strong); border-top: 3px solid var(--warn);
                  border-radius: var(--radius); background: var(--bg-panel);
                  padding: var(--sp-4); margin-top: var(--sp-6);
                }
                .submit-block h2 {
                  font-size: var(--fs-sm); text-transform: uppercase; letter-spacing: 0.06em;
                  color: var(--warn); margin-bottom: var(--sp-2);
                }
                .submit-note { font-size: var(--fs-sm); color: var(--fg-muted); max-width: 72ch; }
                .confirm {
                  display: flex; gap: var(--sp-2); align-items: flex-start;
                  margin: var(--sp-4) 0; font-size: var(--fs-base);
                }
                .confirm input { margin-top: 2px; accent-color: var(--accent); }
                form { display: inline; }
                .row-side .note-muted { max-width: 90px; line-height: 1.25; }
                """;
    }
}
