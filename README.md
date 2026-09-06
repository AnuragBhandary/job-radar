# job-radar

A scheduled command-line pipeline that reads public ATS job boards, screens the
results against a fixed eligibility and salary profile, works out what changed
since yesterday, and writes a short daily digest.

There is no web UI and no REST API. It is a batch job that produces a markdown
file and updates a spreadsheet.

> **Status: Milestone 7 of 8.** Five ATS integrations across 98 boards, screening
> 8,771 postings into a daily digest, reading the Google Sheets tracker to
> suppress companies already applied to, and probing candidate tokens to find
> boards that are on no list. Scheduling and Docker to go.
> See [`docs/`](docs/) for the build log.

## The problem

Job boards are optimised for browsing, not for a daily delta. Checking forty
company boards by hand means re-reading the same postings every morning to find
the two that are new, and the interesting signal — a posting whose *description*
quietly changed — is invisible entirely.

job-radar reads the boards directly through their public ATS APIs, keeps a local
history, and each morning reports only what is new, what changed, and what needs
a human decision.

## Stack

Java 25 · Spring Boot 3.5 · Spring Data JPA / Hibernate 6.6 · SQLite ·
Maven · JUnit 5 + Mockito · Java `HttpClient` · Jackson · Google Sheets API

The JPA layer is kept database-agnostic; moving to PostgreSQL is a change of the
datasource URL and dialect in `application.yml`.

## Setup

Requires JDK 25 and Maven.

```bash
git clone <this repo> && cd job-radar
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home
mvn test
```

`JAVA_HOME` matters: Homebrew's Maven pulls in its own JDK 26, and Spring Boot
3.5 is not tested against it.

### Configuration

Everything user-specific lives in `application.yml` and reads from environment
variables. No path, key or spreadsheet id is hardcoded in Java source.

| Variable | Meaning | Default |
|---|---|---|
| `JOB_RADAR_GOOGLE_KEY` | Google service-account JSON | `~/.config/job-radar/google-key.json` |
| `JOB_RADAR_SHEET_ID` | Target spreadsheet id | unset — Sheets features disabled |
| `JOB_RADAR_OUT` | Digest output directory | `./digests` |
| `JOB_RADAR_DB` | JDBC URL | `jdbc:sqlite:./job-radar.db` |

The service-account key file must never enter the repository; `.gitignore`
covers `config/*.json`, `.env`, `*.db` and `/digests/`.

## Running

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="fetch"
```

| Command | Status |
|---|---|
| `fetch` | working — all active boards |
| `fetch --source=GREENHOUSE` | working — one ATS |
| `fetch --source=GREENHOUSE --token=stripe` | working — one board |
| `screen` | working — applies filters, records verdicts |
| `digest` | working — writes `digests/YYYY-MM-DD.md` |
| `run` | working — fetch + screen + digest |
| `sheet-list` | working — prints the tracker, read-only |
| `sheet-append --posting-id=N` | working — confirms before writing |
| `probe --tokens=a,b,c [--add]` | working — tests a token across four platforms |

### Sample digest

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

## Needs human review — no years stated (25)

- **Stripe** — Software Engineer, New Grad — Dublin
  Years: none stated | Graduate signal: yes

## Rejected (5865)
- 3645 — outside target geographies
- 585 — country-locked remote
- 391 — title excluded on 'senior'

## Board health
All 46 boards healthy — 5893 postings.
```

Run it twice and the second digest is nearly empty — that is the point.

Screening rules and salary floors live in `application.yml` under
`job-radar.screening` and `job-radar.salary-floors`, not in Java — the visa
thresholds are re-indexed annually and the title exclusion list grows steadily.

Every raw API response is saved to `fixtures/<date>/` for replay and
after-the-fact debugging.

## Design notes

**A posting's identity is `(source, boardToken, externalId)`, not its URL.**
Several ATSs rewrite public URLs without the posting having changed.

**Change detection diffs the description hash, never the board's own timestamp.**
Celonis bulk-refreshes every posting's `updated_at` daily, which makes that field
useless as a change signal — and it is useless in a way that looks like it is
working.

**The tracker is append-only.** It is the only record of where applications have
gone, and unlike everything else here it cannot be rebuilt by re-fetching. The
header row is located by content rather than by offset — it was documented as row
6 and found at row 7 — because the same assumption in the append path would
eventually write over a real row.

**An empty board is not proof of anything.** Greenhouse, Ashby and Lever all 404 an
unknown token, so on those three an empty board is a real board with no openings.
SmartRecruiters answers HTTP 200 with `totalFound: 0` for any company name at all,
so there an empty result and a token that never existed are the same response.
`probe` reports the difference rather than flattening it, and the digest names
empty boards as things to verify rather than as clean negatives.

**Board size and postings kept are different numbers.** SmartRecruiters postings
are filtered before their descriptions are fetched, so recording the shortlist as
board health would make a busy board with no matches look dead.

**A board that failed to fetch never closes its postings.** A 404 makes every
posting on that board look absent, and a naive staleness sweep would report that
forty companies stopped hiring on the same morning.

**A board that breaks must not look like a board with no results.** `BoardToken`
records the last fetch's posting count and error separately, and keeps the last
known good count across a failure, so the digest can distinguish "nothing
matched" from "this board started returning 404 three days ago".

**`minYears` distinguishes `null` from `-1`.** `null` is "not screened yet";
`-1` is "screened, and the posting states no requirement". Those are different
things, and treating the absence of a number as evidence of an entry-level role
is exactly the error the screening rules exist to prevent.

## What I learned

Three bugs in Milestone 1, and two of them failed silently.

The one worth repeating: **Hibernate's community SQLite dialect discards
composite unique constraints without raising anything.** It has no
`ALTER TABLE ... ADD CONSTRAINT` to emit, so it emits an empty string. The schema
comes out missing the constraint you declared, and nothing tells you. Since the
whole tool is built on `(source, boardToken, externalId)` being unique, this
would have surfaced at Milestone 4 as "the digest reports everything as new" —
four milestones from its actual cause. Details in
[`docs/milestone-01.md`](docs/milestone-01.md).

The more generalisable one: **the tests passed while the production schema was
broken**, because the tests ran `ddl-auto: create-drop` and production ran
`update`. Those are two different Hibernate components emitting different DDL.
Any difference between test config and production config is, by definition, the
part you are not testing.

Milestone 2 produced a third of the same family. SQLite has no date type, so
sqlite-jdbc stored `LocalDate` as the epoch milliseconds of **local midnight** —
a value that reads back as the previous day in any other timezone. It passes every
test on a developer machine, because the same wrong zone is used to write and to
read, and breaks silently in CI and in Docker, both of which run UTC. The fix is
`config/IsoDateJdbcType`; the test reads the raw column with `JdbcTemplate`,
because a round-trip through Hibernate cannot see the bug.

Milestone 3 added a fourth, and then the fix for the whole class: **Hibernate's
`ddl-auto` logs DDL failures and continues.** Three separate schema bugs had hidden
behind that. `hibernate.hbm2ddl.halt_on_error: true` now makes a rejected DDL
statement a startup failure instead of a log line nobody reads.

The pattern across all of them: **the dangerous bugs were the ones that produced no
error.** Every one was found by looking at the actual database or the actual
output, not at a green test suite.

Screening made the same point in a different way. The filters passed 129 tests
while classifying a Neo4j role in Malmö as remote-hireable — because `distributed`
was a remote marker and the title said "Distributed Systems" — and while accepting
31 hardware roles at a defence company on the strength of the word "engineer".
Neither is visible in a unit test you wrote yourself. Both are obvious in thirty
seconds of reading real output.
