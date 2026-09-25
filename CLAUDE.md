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

1. If the last fetch (header of the newest `inbox/` file, or `inbox/launchd.log`)
   is more than ~20 hours old, run `run` first.
2. `openings` writes `inbox/openings-<time>.md`: every posting that became a
   recommended candidate since the last review, dated by when it became one (so a
   rule change that makes an old posting eligible still shows up), plus the
   shortlist not yet applied to.
3. Judge each against the resume (`resume --list`), rank, reply briefly.
4. Record every decision: `mark <id>,<id>,... shortlist|skip --note="..."`, then
   `openings --done`, which closes the window at the moment the file was written.
5. Only after the user says they applied: `mark <id> applied`. This appends a row
   to the tracker Google Sheet, so never run it speculatively. Later stages:
   `screening`, `interview`, `offer`, `rejected`, `withdrawn`.
6. `export --since=YYYY-MM-DD` remains for looking back over a date range.

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
  Check a hit's locations and a few descriptions before trusting it: SmartRecruiters
  answers for unknown companies, and some boards hold only placeholder postings.
- Rejections that are facts, not judgement: seniority and stack in the title, a
  stated minimum above 2 years ("up to N years" is a ceiling), a stated service bond, and German being required or the
  posting being written in German. At the employers in `big-tech-boards`
  (formal background checks) the cap is 1 year, and a stated non-internship or
  full-time experience requirement rejects; elsewhere that wording is ignored.
  Abroad only: a stated refusal to sponsor on a
  relocation role, and a requirement to live in the country already.
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
