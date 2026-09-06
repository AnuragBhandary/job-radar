# job-radar

A scheduled command-line pipeline that reads public ATS job boards, screens the
results against a fixed eligibility and salary profile, works out what changed
since yesterday, and writes a short daily digest.

There is no web UI and no REST API. It is a batch job that produces a markdown
file and updates a spreadsheet.

> **Status: Milestone 2 of 8.** Reads the 46 verified Greenhouse boards —
> 5,893 postings, idempotent on re-run. No filtering or digest yet.
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
| `screen` | milestone 3 |
| `digest` | milestone 4 |
| `probe`, `sheet-append` | milestones 6-7 |

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

The pattern across all three: **the dangerous bugs were the ones that produced no
error.** Every one was found by looking at the actual database rather than at a
green test suite.
