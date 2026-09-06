# job-radar

A scheduled command-line pipeline that reads public ATS job boards, screens the
results against a fixed eligibility and salary profile, works out what changed
since yesterday, and writes a short daily digest.

No web UI, no REST API. It is a batch job that produces a markdown file and
updates a spreadsheet.

```
98 boards · 8,954 postings · 87 candidates · 87 rejection reasons you can argue with
```

---

## The problem

Job boards are built for browsing, not for a daily delta. Checking forty company
boards by hand means re-reading the same postings every morning to find the two
that are new — and the interesting signal, a posting whose *description* quietly
changed, is invisible entirely.

Worse, the postings that look most promising are often the ones that waste the
most time. "Remote (Argentina)" is remote *within Argentina*: the country is a
hiring restriction, not a perk. A "New Grad" title can still require five years.
An "Associate" ladder can turn out not to be an engineering ladder at all.

job-radar reads the boards through their public APIs, keeps a local history, and
each morning reports only what is new, what changed, and what needs a human
decision — with the exact phrase that disqualified everything else.

---

## Architecture

```
                    ┌──────────────────────────────────────────┐
  Greenhouse  ──┐   │  fetch/                                  │
  Ashby       ──┤   │    AtsFetcher ── HttpFetchClient         │
  Lever       ──┼──▶│      (throttled, retrying, one UA)       │
  SmartRecr.  ──┤   │    PostingMapper ── one RawPosting→Posting│
  amazon.jobs ──┘   └────────────────┬─────────────────────────┘
                                     │
                                     ▼
                    ┌──────────────────────────────────────────┐
                    │  diff/ChangeDetector                     │
                    │    NEW · UPDATED · SEEN · CLOSED         │
                    │    diffs SHA-256 of the description,     │
                    │    never the board's own timestamp       │
                    └────────────────┬─────────────────────────┘
                                     ▼
                    ┌──────────────────────────────────────────┐
                    │  filter/ScreeningService                 │
                    │    GeoFilter → TitleFilter → YearsExtractor
                    │    first rejection wins, reason recorded │
                    └────────────────┬─────────────────────────┘
                                     ▼
      ┌──────────────┐  SQLite  ┌────┴──────────┐   Google Sheets
      │ repo/  JPA   │◀────────▶│ digest/       │◀───── tracker read
      │ Posting      │          │ DigestService │       (suppresses
      │ BoardToken   │          │ DigestWriter  │        companies
      └──────────────┘          └────┬──────────┘        applied to)
                                     ▼
                          digests/YYYY-MM-DD.md
```

Five fetchers behind one interface; mapping to the domain happens in exactly one
place. The JPA layer is database-agnostic — moving to PostgreSQL is a datasource
URL and a dialect in `application.yml`.

---

## Stack

Java 25 · Spring Boot 3.5 · Spring Data JPA / Hibernate 6.6 · SQLite ·
Maven · JUnit 5 + Mockito (**189 tests**) · `java.net.http.HttpClient` · Jackson ·
Google Sheets API · Docker

No test contacts a live endpoint. Every fixture is a trimmed copy of a real
response captured during a live run.

---

## Setup

Requires JDK 25 and Maven.

```bash
git clone <this repo> && cd job-radar
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home
mvn test
mvn spring-boot:run -Dspring-boot.run.arguments="run"
```

`JAVA_HOME` matters: Homebrew's Maven pulls in its own JDK 26, and Spring Boot
3.5 is not tested against it.

### Configuration

Everything user-specific lives in `application.yml` and reads from environment
variables. No path, key or spreadsheet id is hardcoded in Java source.

| Variable | Meaning | Default |
|---|---|---|
| `JOB_RADAR_SHEET_ID` | Tracker spreadsheet id | unset — Sheets features off |
| `JOB_RADAR_GOOGLE_KEY` | Service-account JSON | `~/.config/job-radar/google-key.json` |
| `JOB_RADAR_OUT` | Digest output directory | `./digests` |
| `JOB_RADAR_DB` | JDBC URL | `jdbc:sqlite:./job-radar.db` |
| `JOB_RADAR_SCHEDULE` | Enable the daily schedule | `false` |

The screening rules and salary floors are also configuration, under
`job-radar.screening` and `job-radar.salary-floors`. That is deliberate: visa
thresholds are re-indexed annually, and the title exclusion list grows every time
a board invents a new senior-sounding word.

The service-account key must never enter the repository; `.gitignore` covers
`config/*.json`, `.env`, `*.db`, `/digests/` and `/fixtures/`.

---

## Commands

| Command | Does |
|---|---|
| `fetch [--source=X] [--token=Y]` | Read boards into the database |
| `screen` | Apply the filters, record verdicts and reasons |
| `digest` | Write `digests/YYYY-MM-DD.md` and print it |
| `run` | fetch + screen + digest |
| `probe --tokens=a,b,c [--add]` | Test candidate tokens across four platforms |
| `serve` | Stay running for the daily schedule |
| `sheet-list` | Print the application tracker (read-only) |
| `sheet-append --posting-id=N` | Record an application, after confirming |

---

## Sample digest

```markdown
# job-radar — 2026-09-06

## New candidates (3)

- **Celonis** — Associate Software Engineer - Java — Bangalore, India
  Years: 1 | Graduate signal: no
  Floor: Rs 14,00,000 (relocation: ~Rs 30k/month rent and food)
  https://job-boards.greenhouse.io/celonis/jobs/7791267003

- **Grafana Labs** — Backend Engineer - Platform - Stacks | Ireland | Remote
  Years: 1 | Graduate signal: no
  Floor: EUR 40.904 (CSEP basic salary; below EUR 48.000 Dublin rent makes it ~Mumbai 10L)

## Needs human review — no years stated (78)

- **Stripe** — Software Engineer, New Grad — Dublin
  Years: none stated | Graduate signal: yes

- **Amazon Ireland** — Software Development Engineer – 2026 — Dublin, IRL
  Years: none stated | Graduate signal: yes

## Rejected (8867)
- 3645 — outside target geographies
- 585 — country-locked remote
- 409 — title excluded on 'senior'
- 101 — non-internship experience required

_9 candidate(s) hidden — already applied to that company._

## Board health
- 8 boards returned nothing (token may be dead — verify by hand):
  SMARTRECRUITERS/Personio, SMARTRECRUITERS/Siemens, …
```

Run it twice and the second digest is nearly empty. That is the point.

---

## Scheduling

```bash
JOB_RADAR_SCHEDULE=true mvn spring-boot:run -Dspring-boot.run.arguments="serve"
```

`@Scheduled(cron = "0 0 7 * * *", zone = "Asia/Kolkata")`.

**This only fires while the process is running.** Spring's scheduler has no
memory of missed runs: if the machine is asleep or off at 07:00, nothing happens
then and nothing catches up afterwards. On a laptop that is the normal case.

Two better options ship with the repo:

- **macOS launchd** — `deploy/com.anuragbhandary.job-radar.plist`. launchd reruns
  a missed `StartCalendarInterval` job when the machine wakes, which a
  long-running JVM cannot, because it was not running. This is the right answer
  for a laptop.
- **Docker**, on anything that stays on. `docker build -t job-radar .` and mount
  a volume at `/data` for the database, digests and captured responses.

---

## Design notes

**A posting's identity is `(source, boardToken, externalId)`, not its URL.**
Several ATSs rewrite public URLs without the posting having changed.

**Change detection diffs the description hash, never the board's own timestamp.**
Celonis bulk-refreshes every posting's `updated_at` daily, which makes that field
useless as a signal — and useless in a way that looks like it is working.

**Board size and postings kept are different numbers.** SmartRecruiters postings
are filtered before their descriptions are fetched, so recording the shortlist as
board health would make a busy board with no matches look dead.

**An empty board is not proof of anything.** Greenhouse, Ashby and Lever all 404
an unknown token, so there an empty board is a real board with no openings.
SmartRecruiters answers HTTP 200 with `totalFound: 0` for any company name at
all, so there an empty result and a token that never existed are the same
response. `probe` reports the difference rather than flattening it.

**A board that failed to fetch never closes its postings.** A 404 makes every
posting on that board look absent, and a naive staleness sweep would report that
forty companies stopped hiring on the same morning.

**`minYears` distinguishes `null` from `-1`.** `null` is "not screened yet"; `-1`
is "screened, and the posting states no requirement". Treating the absence of a
number as evidence of an entry-level role is exactly the error the screening
rules exist to prevent — which is why those postings get their own digest section
instead of joining the candidates.

**The tracker is append-only.** It is the only record of where applications have
gone and, unlike everything else here, cannot be rebuilt by re-fetching.

---

## What I learned

**The dangerous bugs were the ones that produced no error.** Every serious defect
in this project was silent, and every one was found by looking at the actual
database or the actual output rather than at a green test suite.

Hibernate's community SQLite dialect *discards composite unique constraints*: it
has no `ALTER TABLE ... ADD CONSTRAINT` to emit, so it emits an empty string. The
schema comes out missing the constraint you declared and nothing tells you. Since
the whole tool rests on `(source, boardToken, externalId)` being unique, that
would have surfaced four milestones later as "the digest reports everything as
new". Three separate schema bugs hid behind `ddl-auto`'s habit of logging DDL
failures and continuing, until `hbm2ddl.halt_on_error` made the whole class loud.

**A difference between test config and production config is, by definition, the
part you are not testing.** Four tests passed against a schema production never
had, because the tests ran `ddl-auto: create-drop` and production ran `update` —
two different Hibernate components emitting different DDL. Later, a test
`application.yml` silently *shadowed* the main one, so the filter tests could not
see the rules that ship.

**Timezones break in production only.** SQLite has no date type, so sqlite-jdbc
stored `LocalDate` as the epoch milliseconds of local midnight — a value that
reads back as the previous day in any other zone. It round-trips perfectly on a
developer machine, because the same wrong zone is used to write and to read, and
shifts every date by a day in CI and Docker, which run UTC.

**Design filters against real data, not imagination.** The years regex was
written after surveying 4,897 real mentions across 5,893 postings. That is how
`2-year development programme` turned up — a graduate fast-track that the obvious
regex reads as "two years required" and rejects. The generosity of "take the
smallest number stated" was measured rather than assumed: only 0.5% of postings
could have a real rejection flipped by a stray small number, and every one was a
title the filter rejects earlier anyway.

**And the filters that passed 189 tests were still wrong.** `distributed` was a
remote marker, so "Distributed Systems" in a title made a Malmö role read as
hireable into India. Only Roman numerals counted as seniority, so "Software
Engineer 2" passed as entry-level. The bare token `engineer` pulled 31 hardware
roles at a defence company into the first 80 candidates. None of that is visible
in a test you wrote yourself; all of it is obvious in thirty seconds of reading
real output.

Full build log, milestone by milestone, in [`docs/`](docs/).
