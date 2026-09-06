# Milestone 2 — Greenhouse fetcher, `fetch` command, seeder

**Goal:** read the 46 verified Greenhouse boards into the database.
**Result:** 5,893 postings, 46/46 boards healthy, second run fully idempotent.

## What was built

| Path | Purpose |
|---|---|
| `fetch/AtsFetcher` | The interface every ATS implements |
| `fetch/RawPosting` | A posting as the board gave it, before interpretation |
| `fetch/GreenhouseFetcher` | One request per board via `content=true` |
| `fetch/Html` | Unescape, strip tags, collapse whitespace |
| `fetch/PostingMapper` | The single RawPosting → Posting conversion |
| `fetch/HttpFetchClient` | The only way this app talks to someone else's server |
| `fetch/FetchService` | Orchestration, upsert, board health |
| `fetch/FixtureRecorder` | Saves every raw response *(addition)* |
| `config/BoardTokenSeeder` | The 46 verified Greenhouse tokens |
| `config/IsoDateJdbcType` | **Timezone bug fix — see below** |
| `cli/JobRadarCli`, `cli/FetchCommand` | `fetch [--source] [--token]` |
| `.github/workflows/ci.yml` | `mvn verify` on push *(addition)* |

## The three additions

**1. Fixture recording.** Every raw response is written to
`fixtures/<date>/<source>-<token>.json`. It supplied the `GreenhouseFetcherTest`
fixture directly — a trimmed copy of a real Tines response — which is why that
test exercises a trailing space in a title and a parenthesised location, details
a hand-written fixture would have tidied away. It also means "why was this
rejected?" stays answerable from the bytes the decision was made on.

Recording never fails a fetch: a full disk loses a debugging aid, not the run.

**2. CI.** `mvn verify` on push and PR, JDK 25. No test contacts a live endpoint,
so CI needs no secrets and no network beyond dependency resolution.

**3. Rules in YAML, not Java.** `job-radar.screening.*` and
`job-radar.salary-floors.*` now hold the geography lists, title include/exclude
lists, graduate signals, years threshold and the five salary floors.

Two reasons. The visa thresholds are re-indexed annually — `verify-by` records
when to re-check them at source — and the title exclusion list grows every time a
board invents a new senior-sounding word. Neither should require a recompile.
It also puts every rule on one screen, which is what you want when the question
is "why was this rejected?"

Milestone 3 consumes these; nothing reads them yet.

## The timezone bug

The one real bug this milestone, and it would only have appeared in production.

After the first full fetch, `posted_date` in the database read `1788114600000`.
SQLite has no date type, so sqlite-jdbc stores a `LocalDate` by converting it to
`java.sql.Date` and writing the epoch milliseconds of **local midnight**, using
the JVM's default timezone in both directions.

That value is midnight on 2026-08-31 in IST — and 18:30 on **2026-08-30** in UTC.
A JVM in a different zone reads the same row back as the previous day. CI runs in
UTC. So does the Docker image at Milestone 8. Every posted date would have
shifted by one day, in production only, while passing every test locally.

Fixed in `config/IsoDateJdbcType`, registered by `SqliteDialect.contributeTypes`:
DATE is bound and extracted as an ISO-8601 string, which has no timezone to get
wrong. Done at the dialect layer rather than with an annotation on the entity, so
PostgreSQL keeps a native `date` column and the swap stays a config change.

The regression test reads the raw column with `JdbcTemplate`, bypassing Hibernate
— asserting the round-trip through Hibernate alone passes on this machine even
with the bug present, because the same wrong zone is used in both directions.

Verified by running the same fetch under `Asia/Kolkata`, `UTC` and
`America/New_York`: all three now store and read `2026-06-24`.

## Other decisions

**Boards are fetched sequentially.** The throttle in `HttpFetchClient` is global,
so four threads against a 500 ms inter-request delay would take the same
wall-clock time with four times the ways to go wrong. The spec's "no more than
~4 concurrent" is satisfied by using one.

**One board, one transaction.** Via `TransactionTemplate`, not `@Transactional` —
`fetchBoard` is called from another method of the same bean, so the proxy would
never see the call and the annotation would have silently done nothing. This is
a classic Spring trap and it fails quietly, which is why it is worth the explicit
template.

**`firstSeen` is never overwritten** on re-fetch. It answers "how long has this
been open?", which a later fetch cannot recover.

**A posting with no id is skipped**, not stored. Without an id it cannot be
matched across runs, so it would appear as new in every digest forever.

**HTTP 404 is not retried.** A wrong token will not become right, and retrying
would only be rude. 429 and 5xx get exponential backoff.

**An empty board is not an error.** `FetchException` means the board could not be
read; an empty list means it was read and has nothing. Conflating them is exactly
what board health exists to prevent.

**`spring-boot-starter-json` had to be added explicitly.** Jackson normally
arrives with `starter-web`, which this project deliberately does not have.

## Verified

- `mvn test` — 26/26 pass
- Full fetch: **46 boards, 5,893 postings, 0 failed**
- Second full fetch: **0 new, 0 updated** — upsert on the natural key works,
  which is the Milestone 1 unique-constraint fix proving itself
- Dates stable across three timezones
- Fixtures written for all 46 boards

Largest description stored: 22,515 characters — comfortably past the
`varchar(32600)` cap that Milestone 1 removed, and a reminder that the PostgreSQL
limit would have been hit for real.

## Data observations

Worth noting for Milestone 3, from the real corpus:

- Stripe has live **"Software Engineer, New Grad"** requisitions — but for
  Bucharest, Toronto, San Francisco and London. None in Dublin today.
- Stripe also has **"Operations Associate, New Grad (Mexico)"** — precisely the
  country-locked-remote trap the geography filter exists to catch, sitting in the
  corpus on day one.

## Not done yet

Nothing reads the screening config. No filters, no verdicts, no digest.
Milestone 3 adds `YearsExtractor`, `GeoFilter`, `TitleFilter`, `ScreeningService`
and the `screen` command, with the full unit-test set.
