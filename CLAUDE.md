# job-radar: working with it from a Claude session

job-radar does the mechanical half of a job search: it fetches public ATS boards,
rejects postings on facts (seniority, stated years, a country or platform that
cannot work), remembers what it has seen and records decisions. The judging (fit,
order, what the resume leads with, what a letter says) happens in the Claude
session. There is no UI.

Build with `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home`
(JDK 26 breaks Hibernate's bytecode generation). Run as
`$JAVA_HOME/bin/java -jar target/job-radar-1.0.0.jar <command>`.

## Reviewing jobs

The full routine, including how to judge and rank, is the personal skill
`~/.claude/skills/job-openings/SKILL.md`, which triggers on "get me today's
openings" and similar from any directory. In short:

1. There is no scheduled run. If the last fetch (the `Last fetch:` line of the
   newest `inbox/` file) is more than a few hours old, run `run` first (a few
   minutes; boards are fetched in parallel, one request at a time per site, and
   the log ends with the five slowest boards).
2. `openings` writes `inbox/openings-<time>.md`: every posting that became a
   recommended candidate since the last review, dated by when it became one (so a
   rule change that makes an old posting eligible still shows up), plus the
   shortlist not yet applied to.
   Relisted copies of a role already decided (same employer and title on any
   board; short generic titles also need the same country) are withheld, and
   cross-board duplicates fold to the direct board's copy. A candidate whose
   description names several frontend technologies is flagged.
3. Judge each against the resume (`resume --list`), rank, reply briefly.
4. Record every decision: `mark <id>,<id>,... shortlist|skip --note="..."`, then
   `openings --done`, which closes the window at the moment the file was written.
   A note is added as a new line (history is kept, a repeat is not added);
   `--replace-note` makes it the only one. The handoff file shows the latest line.
5. Only after the user says they applied: `mark <id> applied`. This appends a row
   to the tracker Google Sheet, so never run it speculatively. Later stages:
   `screening`, `interview`, `offer`, `rejected`, `withdrawn`.
6. `export --since=YYYY-MM-DD` remains for looking back over a date range.
7. `links --ids=<picks>` finds the employer's own page for aggregator postings
   (Arbeitnow, Jobicy, We Work Remotely, HN) across Greenhouse, Lever, Ashby,
   SmartRecruiters, Recruitee, Workable and Personio, adds that employer's board
   when job-radar can fetch it, and checks every link (LIVE / DEAD / UNKNOWN;
   Workday maintenance is UNKNOWN, never dead). An UNKNOWN on a company board
   becomes DEAD when that board has fetched fine for 36 hours without the posting. `notify` runs the same check and
   drops dead links. When nothing is found, search the careers site by hand and
   record it with `mark <id> shortlist --url=<link>`.
8. `notify --ids=<picks in ranked order>` posts the picks to the user's Discord
   channel as cards (webhook in `secrets.yml` as `job-radar.notify.discord-webhook`).
   The last line of each posting's `--note` is the card text, and its first word
   sets the colour: "Apply" green, "Stretch" amber. `--dry-run` prints the JSON.
   A review with no picks sends `notify --quiet-day --note="..."` instead: one
   plain message that the review ran, with the shortlist still waiting.

## Preparing an application

- Resume: `resume --summary=<id> --pick=e1.3,e1.1,p2.1,... --out=resumes/<company>.pdf`.
  Refs are positions from `resume --list`. It only reorders and omits bullets from
  `~/.config/job-radar/applicant.yml`; it cannot add one, and nothing else should.
- Cover letters and form answers are drafted in chat, for the user to paste. Nothing
  submits an application. The user submits.

## Standing rules

- **Never invent or inflate a resume claim.** No years, no "expert", no technology
  the resume does not name. Selection and ordering only.
- **Write like a person.** No em or en dashes as punctuation, and none of the stock
  AI phrases ("I am excited to", "leverage", "passionate about").
- **This repository is public.** Personal data lives in `~/.config/job-radar/`
  (applicant.yml, secrets.yml), which is never copied into the repo, a commit
  message, a test fixture or a comment. Audit the diff before any push.
- **Commit locally; push only when asked.**
- **After touching a screening rule**, re-screen a copy of the database and diff the
  verdicts against a backup before trusting it. A green unit test has more than
  once hidden a rule that fired on nothing in the real data.
  `JOB_RADAR_DB=jdbc:sqlite:<copy> ... screen`
- Adding a board: `probe --tokens=a,b,c` tries every supported ATS; `--add` saves hits.
  `boards` shows what each board has yielded (`--idle`: stored 20+, never
  recommended); `boards --disable=SOURCE/token` stops fetching one.
  Check a hit's locations and a few descriptions before trusting it: SmartRecruiters
  answers for unknown companies, and some boards hold only placeholder postings.
- Rejections that are facts, not judgement: seniority and stack in the title, a
  stated minimum above 2 years ("up to N years" is a ceiling), a stated service bond, and German being required or the
  posting being written in German. At the employers in `big-tech-boards`
  (formal background checks) the cap is 1 year, and a stated non-internship or
  full-time experience requirement rejects; elsewhere that wording is ignored.
  Full-stack and frontend titles are rejected everywhere except Amazon
  (`full-stack-exclude`); a Hacker News header that also lists backend is kept.
  Abroad only: a stated refusal to sponsor on a
  relocation role, and a requirement to live in the country already.
  Internships: only in India or remote into India, and not when the text
  limits them to enrolled students (`Internship`); the candidate block shows
  whether one converts to full time and how long it runs. Big tech also
  includes the banks and large India offices (`big-tech-boards`).
  Not software: a title with no software word (and not a bare "Engineer") whose
  full description names fewer than two software tools (`SoftwareSignal`).
  Hacker News is exempt. The candidate block also flags stated pay below the
  relocation floor, in the floor's currency only.
- `probe` does not cover Workday. A Workday board is added to `WORKDAY` in
  `config/BoardTokenSeeder` as `tenant/wdN/site`, with an optional fourth part
  that becomes the site search (`mastercard/wd1/CorporateCareers/india`). Big
  global sites need it, or the page limit stops before the India desks.

## Where things are

| | |
|---|---|
| Fetchers, one per ATS or feed (incl. HN, Jobicy, We Work Remotely, Arbeitnow) | `fetch/` |
| Screening rules (title, years, geography, strategy) | `filter/`, `strategy/`, `application.yml` |
| Handoff file | `digest/` |
| Decisions and tracker sync | `pipeline/`, `sheets/`, `cli/MarkCommand` |
| Resume rendering | `resume/` |
| Earlier form filling, knowledge resolver and web UI | tag `v1-full` |
