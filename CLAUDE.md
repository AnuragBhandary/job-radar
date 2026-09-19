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

1. Read `inbox/<today>.md`. launchd writes it at 07:00, or on wake if the Mac was
   asleep. If it is missing, or its "Last fetch" line says it is days old, run
   `run` (a few minutes; it fetches ~110 boards) and then read it.
2. After a gap, `export --since=YYYY-MM-DD` writes `inbox/since-<date>.md` with every
   open candidate first seen since then. A daily file only has what was new that
   morning.
3. Judge every candidate against the resume (`resume --list` prints it). Give a
   short ranked list with a one-line reason each, and say plainly why the others
   were dropped.
4. Record each decision: `mark <id> skip --note="..."` or `mark <id> shortlist`. A
   marked posting never appears in a handoff file again.
5. Only after the user says they applied: `mark <id> applied`. This appends a row
   to the tracker Google Sheet, so never run it speculatively. Later stages:
   `screening`, `interview`, `offer`, `rejected`, `withdrawn`.

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

## Where things are

| | |
|---|---|
| Fetchers, one per ATS | `fetch/` |
| Screening rules (title, years, geography, strategy) | `filter/`, `strategy/`, `application.yml` |
| Handoff file | `digest/` |
| Decisions and tracker sync | `pipeline/`, `sheets/`, `cli/MarkCommand` |
| Resume rendering | `resume/` |
| Earlier form filling, knowledge resolver and web UI | tag `v1-full` |
