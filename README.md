# job-radar

The mechanical half of a one-person job search. Every morning it reads about 120
public ATS job boards and the Hacker News "Who is hiring?" thread, rejects what
can be ruled out on facts, and writes one markdown file of what is left, with
enough of each description to judge it. The judging (fit, order, what the resume
leads with, the cover letter) happens in a Claude session reading that file. The
decisions come back through `mark`, and applications are mirrored to a Google
Sheet.

```
Java 25 · Spring Boot 3.5 · Spring Data JPA / Hibernate · SQLite · Maven
Playwright (resume PDF only) · Google Sheets API · launchd
no test touches the network or opens a browser
```

The repository is public and holds no personal data. The applicant's profile and
resume live in `~/.config/job-radar/`; `applicant.example.yml` is the skeleton.

An earlier, larger version also filled application forms in a browser, answered
form questions from a scoped knowledge store, planned resumes from an evidence
bank and had a web UI. That was about 46,000 lines. Judging and drafting turned out
to be work a person reading the posting does better than rules, so it moved out of
the code. That version is kept at the tag
[`v1-full`](https://github.com/AnuragBhandary/job-radar/tree/v1-full).

## The daily loop

```
launchd 07:00 ─► run ─► fetch ─► screen ─► tracker sync ─► inbox/YYYY-MM-DD.md
                                                                   │
                         Claude session: judge, rank, draft ◄──────┘
                                   │
                   mark <id> skip | shortlist | applied ─► tracker sheet
```

- **fetch**: one fetcher per platform in `fetch/` (Greenhouse, Ashby, Lever,
  SmartRecruiters, Workday, Recruitee, amazon.jobs, Hacker News). Change detection
  compares a hash of the description, never the board's timestamp. A board that
  fails never closes its postings.
- **screen**: rejects on facts only: seniority in the title, a stated years
  requirement above the bar, a platform outside the stack (iOS, SAP, Simulink and
  the like), a location that cannot work. Anything uncertain passes, because a
  wrong rejection loses a job and a wrong pass costs one line of reading.
  Eligibility and priority are separate: `strategy:` in `application.yml` decides
  which countries are recommended, and relocation and remote work are separate
  flags per country.
- **handoff file**: each candidate with its id, location, work mode, lane, stated
  years, stated pay, salary floor and a trimmed description (the opening plus the
  requirements). No ranking. The header says when the data was fetched and counts
  everything withheld, so a short file can be told apart from a broken one.
- **mark**: records a decision. A marked posting never reappears. `applied` and
  later stages are appended to the tracker sheet; `skip` and `shortlist` never are.

## Commands

```
run                                  fetch + screen + tracker sync + handoff file
export --since=YYYY-MM-DD            handoff file for every open candidate since a day
mark <id> <decision> [--note=...]    shortlist | skip | applied | screening | interview |
                                     offer | rejected | withdrawn
resume --list                        every summary and bullet, with references
resume --summary=ID --pick=e1.3,p2.1 render the resume in a chosen order (PDF)
probe --tokens=a,b,c [--add]         test candidate board tokens on every ATS
fetch | screen | digest              the steps of run, one at a time
sheet-list | sheet-append            read or append to the tracker directly
```

`resume` reorders and omits bullets from the profile. It cannot add one, and
nothing in this project writes a claim about the applicant.

## Setup

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home
mvn package
mkdir -p ~/.config/job-radar
cp applicant.example.yml ~/.config/job-radar/applicant.yml
java -jar target/job-radar-1.0.0.jar run
```

Use JDK 25: a newer JDK breaks Hibernate's bytecode generation. The Google Sheet
id and service-account key path go in `~/.config/job-radar/secrets.yml`; without
them everything works except the tracker. Chromium downloads itself the first time
`resume` prints a PDF.

**Scheduling.** `deploy/com.anuragbhandary.job-radar.plist` is a launchd agent that
runs `run` at 07:00, and on wake if the Mac was asleep then. Install with
`cp deploy/*.plist ~/Library/LaunchAgents/` and
`launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.anuragbhandary.job-radar.plist`.

## Testing

`mvn test`. Fetchers run against trimmed real responses in
`src/test/resources/fixtures/`, persistence tests use a real SQLite file under
`target/`, and `CliContextTest` boots the context the way `main()` does. CI runs
`mvn verify` on every push to `main`, with no secrets.

Screening rules get one more check than tests: re-screen a copy of the real
database and diff every verdict against a backup. A green unit test has more than
once hidden a rule that fired on nothing in the real data.

## Design notes

**A posting's identity is `(source, boardToken, externalId)`, not its URL.**
Several ATSs rewrite public URLs without the posting changing.

**Change detection diffs the description hash, never the board's timestamp.** One
ATS bulk-refreshes every posting's `updated_at` daily, which makes that field
useless in a way that looks like it is working.

**`minYears` distinguishes "not screened" from "states no requirement".** Treating
the absence of a number as evidence of an entry-level role is the error the
screening rules exist to prevent. "Bachelor's degree, or 4+ years of equivalent
experience" is an alternative to a degree, not a four-year bar.

**A city name is not a place.** "Dublin, Ohio" is checked before the target lists
claim "Dublin", and place-name patterns are Unicode-aware, because `\b` in Java
regex is ASCII-only and silently never matched "Malmö".

**"Distributed" is not a remote marker.** It matched "Distributed Systems" in a
title and made an onsite role read as globally remote.

**Applying to a company is flagged, not used as a filter.** An application to one
Amazon team is no reason to hide every other Amazon role.

**`SchemaMigrator` runs before Spring.** Hibernate cannot widen a SQLite `CHECK`
constraint when an enum gains a value, and `ddl-auto` does not notice one is too
narrow, so a new `Source` would otherwise fail at the first insert.

**The tracker is append-only.** It is the one record that cannot be rebuilt by
re-fetching.

Build log, milestone by milestone, in [`docs/`](docs/).
