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
                """;
    }
}
