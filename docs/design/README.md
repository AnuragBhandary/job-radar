# Where the interface came from

The stylesheet and page structure in `web/Ui.java` and `web/UiController.java`
were designed in **Lovable** (September 2026) and ported here by hand.

- Project: `Job Scout UI`, workspace `Anurag's Lovable`
- <https://lovable.dev/projects/359a46ba-6b64-4f3c-adad-5b0fc4d40080>
- The first pass lives there as `public/job-radar/{index,attempt-19}.html` and
  `public/job-radar/app.css`
- The second pass (September 2026, after the match score, the board and the
  assistant were added) is `public/{feed,board,chat,posting-9039}.html` and
  `public/job-radar.css`. That stylesheet is copied here as `job-radar.css` and
  is the one carried in `Ui.css()`

## Why it was briefed as a static prototype

Lovable's default output is a React app on its own hosting. Neither half of that
fits here.

**Hosting.** The UI has to be same-origin with the API, because the API is a
Spring server on `localhost:8080` and the data is a home address, EEO answers and
the contents of real applications. A page served from `https://…lovable.app`
calling `http://localhost:8080` is blocked as mixed content, and the alternative
is either a local TLS certificate or sending the data somewhere else.

**Toolchain.** There is no Node on this machine, and a React build would mean
`npm` in a project that is otherwise one `mvn spring-boot:run`.

So the brief asked for two static HTML pages and one plain CSS file with custom
properties and semantic class names, explicitly so it could be lifted into a
server-rendered Java page. That is what came back, and the port was mechanical.

## Re-designing it

Change the prototype in Lovable, read the files back, and re-port. The CSS is
carried verbatim in `Ui.css()`; the markup is rebuilt in `UiController` because
it is generated from real rows rather than hard-coded.

Four things to keep if you regenerate:

- **No webfonts and no CDN.** The tool is expected to work with the network off.
- **The screenshot frame.** The captured form is 1440 by 4536 pixels. Boxed at
  420px it is a panel; unboxed it is three thousand pixels of page and everything
  below it is unreachable.
- **A floor under the board columns.** Six columns inside the 1120px reading
  width put "Amazon Development Centre Ireland" on six lines. The board opts into
  `.page.wide` and its columns have a `186px` minimum.
- **The score classes work in two sizes.** `.score-strong` and friends set custom
  properties; `.row > .score` and `.head-top > .score` enlarge them into a plate,
  and everywhere else they stay an inline chip. Styling the plate directly means
  the board's inline scores lose their colour.
