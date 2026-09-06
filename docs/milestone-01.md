# Milestone 1 — skeleton, entities, SQLite

**Goal:** a Spring Boot project that boots, owns a SQLite database, and has the
two core entities persisted correctly. No fetching yet.

## What was built

| Path | Purpose |
|---|---|
| `pom.xml` | Boot 3.5.16, Java 25, SQLite + community dialects |
| `application.yml` | All user-specific config, secrets via env vars |
| `domain/Posting` | One job posting on one board |
| `domain/BoardToken` | A board plus the health of its last fetch |
| `domain/{Source,Verdict,Country}` | Enums |
| `repo/*Repository` | Spring Data JPA repositories |
| `config/AppProperties` | Typed binding for `job-radar.*` |
| `config/SqliteDialect` | **Patched dialect — see below** |
| `PostingRepositoryTest` | 4 tests proving the wiring |

## Versions and why

- **Java 25.** The machine has JDK 25 (system) and JDK 26 (pulled in by
  Homebrew's Maven). Boot 3.5.x is not tested on 26, so the build pins 25.
  Always build with `JAVA_HOME` set — see the README.
- **Spring Boot 3.5.16.** Its BOM manages both `hibernate-community-dialects`
  (6.6.53.Final) and `sqlite-jdbc` (3.49.1.0), so neither needs an explicit
  version. That is what keeps the PostgreSQL swap a config change.

## The three things that went wrong

All three were caught by running the code, not by reading it. Worth
understanding, because two of them fail *silently*.

### 1. Composite unique constraints were silently discarded

`Posting`'s identity is `(source, boardToken, externalId)`. The
`@UniqueConstraint` for it never reached the schema.

Cause: Hibernate's community `SQLiteDialect` extends `AlterTableUniqueDelegate`
and overrides `getAlterTableToAddUniqueKeyCommand` to return `""`, because SQLite
has no `ALTER TABLE ... ADD CONSTRAINT`. Single-column uniqueness survives (it is
inlined on the column); multi-column keys disappear with no error and no warning.

Why it mattered: without that constraint, every re-fetch would insert a duplicate
row rather than match the existing posting. Change detection — the whole product —
would have reported everything as NEW forever, and the bug would have surfaced at
Milestone 4 as "the digest is wrong" rather than as a schema problem.

Fix: `config/SqliteDialect` supplies a delegate that emits
`CREATE UNIQUE INDEX IF NOT EXISTS` instead. Valid on SQLite and PostgreSQL both.

Two Hibernate-supplied delegates look like they would work and do not:
- `CreateTableUniqueDelegate` inlines the key into `CREATE TABLE` — correct for
  `ddl-auto: create`, but on the *migrate* path it delegates back to
  `ALTER TABLE` and the constraint vanishes again.
- `AlterTableUniqueIndexDelegate` emits an index only when the key contains a
  nullable column. Ours are all `not null`, so it took the `ALTER TABLE` path.

### 2. The test suite was testing a different code path than production

Tests used `ddl-auto: create-drop`; production used `update`. Those run different
Hibernate components — `SchemaCreator` vs `SchemaMigrator` — which emit different
DDL. The result: **all four tests passed while the real database was missing the
constraint.**

Fix: tests now use `ddl-auto: update`, the same as production. `@DataJpaTest`
rolls back data between tests, so reusing the schema across runs is harmless.

Generalisable lesson: if the test config differs from the production config, the
difference is the part you are not testing.

### 3. `description_text` was capped at 32,600 characters

`@Lob` maps to CLOB, which Hibernate writes via `setClob` — sqlite-jdbc does not
implement it. The obvious workaround, `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`,
produced `varchar(32600)`. SQLite ignores varchar lengths so it appeared to work,
but PostgreSQL enforces them, and Amazon `basic_qualifications` blocks exceed
32,600 characters.

Fix: `columnDefinition = "text"`. `text` is native and unbounded in both SQLite
and PostgreSQL. Looks like the less portable option; is the more portable one.

### Bonus: constraint violations arrived untyped

The stock dialect does not translate SQLite's error codes, so a unique violation
surfaced as `JpaSystemException` rather than `DataIntegrityViolationException`.
`SqliteDialect.buildSQLExceptionConversionDelegate` maps SQLITE_CONSTRAINT (19)
and its extended codes to Hibernate's `ConstraintViolationException`, which Spring
then translates properly.

## Design decisions

- **Natural key, not URL.** Several ATSs rewrite public URLs without the posting
  changing. `equals`/`hashCode` use the natural key, not the surrogate id.
- **`minYears`: `null` ≠ `-1`.** `null` means not yet screened; `-1` means
  screened and no years stated. Collapsing them would turn "we do not know" into
  "there is no requirement", which is the mistake the screening rules exist to
  prevent.
- **`BoardToken.recordFailure` keeps the last good posting count**, so the digest
  can say "was 412, now erroring" instead of "0 postings". A board that quietly
  breaks and a board with no matching jobs must not look the same.
- **`spreadsheet-id` defaults to empty** rather than being required, so fetch,
  screen and digest work without Sheets configured. `Google.isConfigured()`
  gates that lazily.

## Verified

- `mvn test` — 4/4 pass
- App boots and exits cleanly (no web layer, by design)
- Booting twice is idempotent; no DDL errors on the second run
- Duplicate natural key is rejected by the database itself (checked directly
  against the file with `sqlite3`, not only through Hibernate)

## Not done yet

No fetchers, no filters, no CLI, no scheduler, no seed data. Milestone 2 adds
`GreenhouseFetcher`, the `fetch` command, and the Greenhouse token seeder.
