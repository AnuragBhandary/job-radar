# Phase brief: lean core, Claude as the frontend

Status: draft for review, 2026-09-19. Nothing below has been built.

## Why

job-radar has two halves. One is mechanical: fetch postings, throw out the ones
that can be ruled out on facts, remember what has been seen, record what was
applied to. The other is judgement: is this role a good fit, which one first, what
should the resume lead with, what should the letter say.

The repo spent most of its effort on the second half, and in code. The numbers from
the audit on 2026-09-19:

| | |
|---|---|
| Main source | ~46,000 lines, 290 files |
| apply, knowledge, web, chat, evidence, bench | ~32,000 lines (about 70%) |
| Application attempts made through all of that | 7 |
| Postings in the database | 10,341 |
| Gulf postings (AE, QA, SA) | 54, of which 3 candidates |
| Postings open to remote-from-India | 33, of which 1 candidate |
| US postings (excluded for relocation) | 3,609 |
| Daily launchd job | written, never loaded; last fetch Sept 16 |
| Today's "Start here" #1 | a Bosch Simulink (MBD) role |

So the mechanical half is not running on its own, is reading the wrong part of the
market, and the ranking at the end is not trustworthy. Judgement is what a person
reading the description does well and rules do badly, so it moves out of the code
and into a Claude session.

## The split

**job-radar does:** fetch every day without being asked, apply fact-based filters,
dedup, track open/closed, write one daily handoff file, record decisions and push
applications to the tracker sheet.

**Claude does, in chat:** read the handoff file, judge fit against the real resume,
return a short ranked list with a reason for each, choose which resume bullets to
lead with (selection and ordering only, never a new claim), and draft cover letters
and form answers. Anurag pastes and submits. Nothing is ever submitted by software.

**The rule the filters now follow:** a wrong rejection loses a job, and a wrong pass
costs Claude one line of reading. So the filters reject only on facts, and anything
uncertain goes through to the handoff file.

## Scope

### Keep (the core)

`fetch`, `filter`, `domain`, `repo`, `strategy`, `config` (including
`SchemaMigrator`), `diff`, `sheets`, `digest` (slimmed down, see below), and the CLI
commands `fetch`, `screen`, `run`, `probe`, `sheet-list` and `sheet-append`.

Every one of these packages already depends only on the others in this list. The
only imports that cross the line are:

- `match/MatchScorer` → `apply.resume.ResumeModel`, `prep.TechVocabulary`
- `money/SalaryGuide` → `apply.ApplicantProfile`
- `config/SchemaMigrator` references enums from the cut packages

### Cut from main

`apply` (form filling, Playwright, cover letters, HumanTone, resume tailoring),
`knowledge`, `evidence`, `web`, `chat`, `bench`, `prep`, `worklist`, `pipeline`,
`followup`, `mail`, `match`, and their commands and tests. The Gmail, Playwright
and web starter dependencies come out of `pom.xml`, unless decision 2 keeps
Playwright.

Freeze, do not lose: tag the current HEAD `v1-full` and push the tag before cutting,
so the knowledge resolver and the rest stay public as portfolio work and can be
restored.

**Database:** no table is dropped and no row is deleted. `application_attempt`,
`application_field`, `assertion` and `question_sighting` stay in the file and
simply stop being read. `SchemaMigrator` keeps the CHECK-constraint values for the
removed enums so old rows still load.

### Add

**1. A daily handoff file, `inbox/YYYY-MM-DD.md`, written by `run`.** One entry per
new or updated candidate:

```
## 4812 · N26 · Backend Engineer, Payments
DE · hybrid · remote from: - · lane PRIMARY · years: 2+ · posted 3d ago
Stated pay: EUR 60-70k        Floor: EUR 55k
https://...
Why it passed: no exclusion matched; years 2 <= 2
--- description (trimmed to requirements, max 2,500 chars) ---
...
```

A header counts what was fetched, rejected (grouped by reason, as the digest does
now), set aside as stale, and closed. No ranking and no score: ranking is Claude's
job now.

**2. `export --since=YYYY-MM-DD`** rewrites the same format for any window, so a
missed day can be caught up in one read.

**3. `mark <posting-id> applied|skip|shortlist [--note "..."]`** writes to
`job_interest` (the table the board already uses). `applied` also runs the existing
`sheet-append --yes` path. A skipped posting never reaches a handoff file again.
This is how decisions made in chat get back into the tool.

**4. Stack exclusions as config.** Titles that are facts about Anurag's stack, not
judgement: iOS, Android, Salesforce, SAP, embedded/BSW, MBD/Simulink, PLC, and so
on, added as a list in `application.yml`, recorded as their own reject reason so
the header still counts them.

**5. Sources that match the strategy.** This matters more than anything else in
this brief. Verify each source's terms and response format before writing a
fetcher:

- Remote job feeds with a location field: Remotive's public API and RemoteOK's
  JSON feed. Keep a posting only when its location explicitly allows India or
  worldwide.
- Hacker News "Who is hiring" through the Algolia HN API. It is free text, so the
  fetcher stores each comment raw with a light filter (REMOTE, or a matching
  country), and Claude reads the rest. The split makes a messy source like this
  cheap for the first time.
- Gulf employers on ATS platforms the tool already supports. Build a shortlist
  (fintech and consumer tech in Dubai, Riyadh and Doha), run `probe` on each, and
  add only the ones that pass. Nothing is assumed about which ATS a company uses.
- Remote-first companies that hire in India on Greenhouse, Ashby or Lever,
  through the same probe process.
- LinkedIn, Naukri and the Gulf job portals are out of scope: they have no public
  API, and scraping them breaks their terms.

**6. Actually schedule it.** Load `deploy/com.anuragbhandary.job-radar.plist` with
`launchctl` so it runs every morning at 07:00. launchd runs a missed calendar job
once when the Mac wakes. Log to a file, and put the last run time and its outcome
in the handoff file header, so a silent failure shows up the next time Claude reads
it.

**7. `CLAUDE.md` at the repo root** with the workflow, so any session knows it:
where the handoff file is, which command to run if today's file is missing, how to
mark a decision, and the standing rules (no invented resume claims, never submit,
the repo is public so no personal data).

### The slimmed digest

`digest` stops ranking (`MatchScorer` goes) and becomes the header of the handoff
file. The separate `digests/*.md` output can go; the handoff file replaces it.

## Decisions for Anurag

1. **Delete or disable?** Recommended: delete from main after the `v1-full` tag. A
   13k-line codebase someone can read beats a 46k-line one nobody can, and the tag
   keeps the rest. The alternative is keeping them behind a Spring profile, which
   means they keep compiling, keep their tests, and keep costing effort.
2. **Keep a resume PDF renderer?** The PDF is made through Playwright (`PdfWriter`).
   Recommended: keep one small command, `resume --order=3,1,7 --out=...`, that renders
   `applicant.yml`'s bullets in the order Claude picks. That is mechanical work worth
   keeping, and it is the only reason Playwright would stay. The alternative is one
   fixed resume PDF and no dependency.
3. **The salary floor line.** `SalaryGuide` needs `ApplicantProfile` from `apply`.
   Recommended: move the few fields it reads into a small `profile` package in the
   core, rather than dropping floors from the handoff file.
4. **The years rule for "Bachelor's degree, or N years".** It currently rejects
   entry-level Amazon and Google roles as needing N years. Under the new rule,
   uncertain means it passes. Recommended: stop reading N from that construction.

## Order of work

1. Tag `v1-full` and push the tag. Back up `job-radar.db` somewhere durable, not
   the scratchpad.
2. Handoff file, `export`, `mark` and the stack exclusions, built on the current
   code. Nothing is cut yet, so the new output can be checked against the old
   digest on the real database.
3. The cut. Build, run the remaining tests, and run `run` against a copy of the
   real database.
4. New sources, one at a time, each with a fixture test and a count of what it
   added on the real database.
5. launchd, `CLAUDE.md`, and a README rewrite (the current README is 44 KB, and
   most of it describes code that is leaving).

## Done when

- `run` writes `inbox/<today>.md` from the real database with no manual step, and
  launchd has run it at least once on its own.
- Verdicts on the real database, diffed against the backup before the change,
  differ only where this brief says they should (stack exclusions, the years rule).
  Every other change is explained or reverted.
- `mark ... applied` shows up in the Google Sheet.
- At least one new source is live, and the handoff file has more Gulf and
  remote-from-India candidates than the 3 and 1 on record today.
- `mvn test` is green, and the repo builds with no reference to a removed package.
- A personal-data audit of the diff before any push (the repo is public).

## Not in this phase

Any UI. Any LLM call from Java: the Gemini client goes with `apply`. Automatic
submission, which stays off permanently.
