package com.anuragbhandary.jobradar.web;

/**
 * The page chrome and the stylesheet, in one place.
 *
 * <p>Hand-written HTML rather than a template engine. Four pages do not justify
 * Thymeleaf, and the same argument that kept Picocli out of the CLI applies here:
 * a dependency earns its place when the alternative is worse, and for four pages
 * it is not.
 *
 * <p>Everything user-supplied is escaped by {@link #esc}. The values on these
 * pages come from job boards - titles, labels, cover-letter text - which is
 * third-party content rendered into a page that has buttons which submit real
 * applications. It is a local single-user server, which lowers the stakes and
 * does not remove them.
 */
final class Ui {

    private Ui() {
    }

    static String page(String title, String body) {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s · job-radar</title><style>%s</style></head><body>
                <header><a class="home" href="/">job-radar</a><span class="crumb">%s</span></header>
                <main>%s</main></body></html>
                """.formatted(esc(title), css(), esc(title), body);
    }

    static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Escaped, and with newlines preserved - for cover letters and field logs. */
    static String pre(String value) {
        return "<pre>" + esc(value) + "</pre>";
    }

    /**
     * The assistant panel.
     *
     * <p>Three modes, and they are separated because they carry different risk. An
     * <em>answer</em> and a <em>letter</em> are text that will go to an employer
     * under his name, so both are grounded in the profile and policed for tone; an
     * <em>ask</em> is for him to read and is neither. The panel says which is
     * which rather than presenting one box that quietly does three things.
     *
     * <p>Nothing here writes to a form. The answer box has a save button that
     * appends to applicant.yml, which is a second, deliberate click.
     */
    static String assistant(long attemptId, boolean usable, java.util.List<String> questions) {
        if (!usable) {
            return """
                    <h2>Assistant</h2>
                    <div class="note">No model configured. Put a key in
                    <code>~/.config/job-radar/secrets.yml</code> under
                    <code>job-radar.llm</code> and restart.</div>
                    """;
        }

        StringBuilder chips = new StringBuilder();
        for (String question : questions) {
            chips.append("<button type=\"button\" class=\"chip\" data-q=\"")
                    .append(esc(question)).append("\">")
                    .append(esc(shorten(question))).append("</button>");
        }

        return """
                <h2>Assistant</h2>
                <div class="assist">
                  <div class="modes">
                    <button type="button" class="mode on" data-mode="answer">Draft an answer</button>
                    <button type="button" class="mode" data-mode="letter">Rewrite the letter</button>
                    <button type="button" class="mode" data-mode="ask">Ask about this posting</button>
                  </div>
                  <div class="chips">%s</div>
                  <textarea id="ap" rows="3" placeholder="Paste the question, or type what you want changed."></textarea>
                  <div class="row">
                    <button type="button" id="ag" class="primary">Draft it</button>
                    <span class="meta" id="as"></span>
                  </div>
                  <div id="ao" hidden>
                    <pre id="at"></pre>
                    <div class="row">
                      <button type="button" id="ac">Copy</button>
                      <button type="button" id="av">Save to applicant.yml</button>
                      <span class="meta" id="am"></span>
                    </div>
                  </div>
                </div>
                <script>
                (function () {
                  var id = %d, mode = 'answer', lastQuestion = '';
                  var box = document.getElementById('ap'),
                      out = document.getElementById('ao'),
                      text = document.getElementById('at'),
                      status = document.getElementById('as'),
                      saved = document.getElementById('am');

                  document.querySelectorAll('.mode').forEach(function (b) {
                    b.onclick = function () {
                      document.querySelectorAll('.mode').forEach(function (o) {
                        o.classList.remove('on');
                      });
                      b.classList.add('on');
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
                    fetch('/assistant', {
                      method: 'POST',
                      headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify({attemptId: id, mode: mode, text: box.value})
                    }).then(function (r) { return r.json(); }).then(function (d) {
                      status.textContent = d.note || '';
                      out.hidden = !d.ok;
                      text.textContent = d.text || '';
                      if (!d.ok) { status.textContent = d.note || 'nothing came back'; }
                      if (mode === 'letter' && d.ok) { status.textContent += ' saved to this attempt'; }
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
        return flat.length() <= 46 ? flat : flat.substring(0, 44) + "...";
    }

    private static String css() {
        return """
                :root {
                  --bg:#fbfaf8; --fg:#1a1a1a; --muted:#666; --line:#e2ded8;
                  --card:#fff; --accent:#1c5d3a; --warn:#8a5a00; --bad:#9b2c2c;
                }
                @media (prefers-color-scheme: dark) {
                  :root { --bg:#16181a; --fg:#e8e6e3; --muted:#9a9a9a; --line:#2c2f33;
                          --card:#1d2023; --accent:#57b07f; --warn:#d8a138; --bad:#e07070; }
                }
                * { box-sizing:border-box; }
                body { margin:0; background:var(--bg); color:var(--fg);
                  font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; }
                header { display:flex; gap:10px; align-items:baseline; padding:14px 22px;
                  border-bottom:1px solid var(--line); position:sticky; top:0;
                  background:var(--bg); }
                .home { font-weight:600; color:var(--fg); text-decoration:none; }
                .crumb { color:var(--muted); }
                main { max-width:1000px; margin:0 auto; padding:22px; }
                h2 { font-size:14px; text-transform:uppercase; letter-spacing:.07em;
                  color:var(--muted); margin:26px 0 10px; font-weight:600; }
                h2:first-child { margin-top:0; }
                .card { background:var(--card); border:1px solid var(--line);
                  border-radius:8px; padding:12px 14px; margin-bottom:8px; }
                .row { display:flex; justify-content:space-between; gap:14px;
                  align-items:baseline; flex-wrap:wrap; }
                .title { font-weight:600; }
                .meta { color:var(--muted); font-size:12.5px; }
                a { color:var(--accent); }
                button { font:inherit; padding:6px 13px; border-radius:6px;
                  border:1px solid var(--line); background:var(--card); color:var(--fg);
                  cursor:pointer; }
                button.primary { background:var(--accent); color:#fff; border-color:transparent; }
                button[disabled] { opacity:.45; cursor:not-allowed; }
                .tag { font-size:11.5px; padding:1.5px 7px; border-radius:99px;
                  border:1px solid var(--line); color:var(--muted); }
                .tag.warn { color:var(--warn); border-color:var(--warn); }
                .tag.bad { color:var(--bad); border-color:var(--bad); }
                .tag.ok { color:var(--accent); border-color:var(--accent); }
                pre { white-space:pre-wrap; word-break:break-word; font-size:12.5px;
                  background:var(--bg); border:1px solid var(--line); border-radius:6px;
                  padding:10px; overflow-x:auto; margin:0; }
                img.shot { width:100%; border:1px solid var(--line); border-radius:6px; }
                .empty { color:var(--muted); padding:8px 0; }
                form { display:inline; }
                label.check { display:flex; gap:8px; align-items:flex-start;
                  margin:14px 0; color:var(--muted); }
                .note { border-left:3px solid var(--warn); padding-left:11px;
                  color:var(--muted); margin:14px 0; }
                .assist { background:var(--card); border:1px solid var(--line);
                  border-radius:8px; padding:13px 14px; }
                .modes { display:flex; gap:6px; flex-wrap:wrap; margin-bottom:9px; }
                .mode.on { background:var(--accent); color:#fff; border-color:transparent; }
                .chips { display:flex; gap:6px; flex-wrap:wrap; margin-bottom:9px; }
                .chip { font-size:12px; padding:3px 9px; color:var(--muted); }
                .chip:hover { color:var(--fg); }
                textarea { width:100%; font:inherit; padding:9px; border-radius:6px;
                  border:1px solid var(--line); background:var(--bg); color:var(--fg);
                  resize:vertical; }
                #ao { margin-top:11px; }
                #ao .row { margin-top:9px; justify-content:flex-start; }
                """;
    }
}
