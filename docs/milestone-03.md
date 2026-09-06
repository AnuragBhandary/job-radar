# Milestone 3 — screening

**Goal:** turn 5,893 fetched postings into a verdict each.
**Result:** 56 candidates, 5,837 rejected, every rejection carrying the phrase
that disqualified it. 129 tests pass.

## What was built

| Path | Purpose |
|---|---|
| `filter/YearsExtractor` | Reads required years out of a description |
| `filter/GeoFilter` | India / Germany / Ireland / Netherlands / remote-into-India |
| `filter/TitleFilter` | Entry-level software titles, and graduate signals |
| `filter/ScreeningService` | Runs the filters in order, records the verdict |
| `cli/ScreenCommand` | `screen` |
| `filter/RealConfig` (test) | Binds tests against the shipped `application.yml` |

## Designing the years regex against the corpus

The spec supplies a regex. Rather than implement it as written, the 5,893
descriptions already in the database were surveyed first. That produced 4,897
mentions of a number of years, and the distribution decided the design:

| Occurrences | Shape | Example |
|---|---|---|
| 3,563 | `N+ years` | `3+ years of experience in a technical role` |
| 545 | `N years` | `5 years of experience in solution engineering` |
| 301 | `N-N years` | `5-8 years experience in an IT Operations role` |
| 161 | `N–N years` | `3–4 years in deal desk` (en dash, not hyphen) |
| 66 | `N to N years` | `4 to 8 years of experience in finance` |
| 53 | `N–N+ years` | `2-12+ years of industry software engineering` |
| 20 | `N - N years` | `6 - 10 years of full-cycle recruiting` |
| 18 | `N-year` | `a fast-track, 2-year development programme` |
| 12 | `N + years` | `5 + years experience working with` |

Every one of these is now a test case with its expected lower bound.

### The one that mattered

`N-year` is not an experience requirement. It is a duration, and the example the
corpus supplied is **Celonis advertising a graduate fast-track as a "2-year
development programme"** — which the naive regex reads as "2 years required" and
rejects. That is a false rejection on precisely the kind of role being searched
for. A hyphen directly before a singular "year" now disqualifies the match.

### Taking the minimum, and checking it was safe

The rule "take the smallest number stated" is generous by design, and generosity
here means false acceptances. So it was measured rather than assumed: of the
3,997 postings mentioning years, only **21 (0.5%)** contain both a number below 2
and a number of 5 or more — the case where a stray small number could turn a real
rejection into an acceptance. All 21 were Program Manager, Director or Team Lead
titles that the title filter rejects first.

Requiring an "experience" keyword near the match was tried and rejected: 9.7% of
matches have no such keyword nearby, and those include genuine requirements
("Minimum 12 years of relevant customer success") alongside the noise ("upon 2
years of service"). Filtering on context would have dropped real requirements to
remove noise that the minimum rule already tolerates.

**The spec's rule was right. It is now right for a reason that was checked.**

## Three bugs the corpus found that tests did not

All three passed a green test suite and were caught by reading the output.

**1. `distributed` was a remote marker.** It matched "Distributed Systems" in a
job title, which classified a Neo4j role in Malmö as globally remote and
hireable into India. A remote marker has to be a word about working
arrangements, not one that is also core software vocabulary.

**2. Only Roman numerals counted as seniority levels.** MongoDB advertises
"Software Engineer 2" in Dublin, and it read as entry-level for a full screening
run. `\b(ii|iii|iv|v|[2-9])\b` now covers both notations, with I and 1 excluded
because "SDE I" and "Engineer 1" are targets.

**3. The bare token "engineer" was far too broad.** In the first run **31 of 80
candidates were Helsing** — a defence company — with titles like Avionics
Hardware Engineer, RF & EMC Engineer, Stress & Dynamics Engineer and Camera
Engineer. That is a different discipline, not a near miss. The exclusion list
grew a hardware section, a customer-facing section (solution / presales /
escalations / technical services / onboarding), Celonis's Value Engineer ladder,
and `recipe`, for HelloFresh's "Junior Recipe Developer".

That first run is exactly what the YAML-config decision was for: all three fixes
except the seniority regex were configuration edits.

## Filter ordering

Geography, then title, then years — and the first rejection wins.

Cheapest first: geography and title are string checks over a few dozen
characters, while the years extractor scans a description averaging several
kilobytes. It also produces better reasons. A senior role in Buenos Aires could
be rejected three ways, and "country-locked remote" is the useful one, because
no amount of experience would change the answer.

## Two more silent-DDL findings

**`ddl-auto: update` refused to add the new `graduate_signal` column** and, as
usual, said nothing. SQLite cannot `ADD COLUMN NOT NULL` without a default, so
the migration failed and only surfaced later as `no such column`. Fixed with
`columnDefinition = "boolean not null default false"` — `false` rather than `0`
because PostgreSQL rejects the latter for a boolean.

This is the third silent DDL failure in three milestones, so the cause is now
addressed directly: **`hibernate.hbm2ddl.halt_on_error: true`**. A DDL statement
the database rejects is now a startup failure instead of a log line nobody reads.

**The test `application.yml` was shadowing the main one entirely.** A file of the
same name in `src/test/resources` replaces the main file rather than merging with
it, so the filter tests could not see the screening rules at all. Renamed to
`application-test.yml` with `@ActiveProfiles("test")`, which is the Spring idiom
and means tests now layer overrides on top of the real configuration instead of
replacing it.

Tests bind the shipped `application.yml` through `RealConfig`, so a typo in the
screening rules fails the build. A test carrying its own copy of the rules would
be the Milestone 1 create-drop mistake in a different place.

## Results

```
5893 screened | 56 candidates | 5837 rejected
  of the candidates: 53 need human review (no years stated), 1 has a graduate signal

    3683  outside target geographies
     585  country-locked remote
     409  title excluded on 'senior'
     250  title excluded on 'manager'
     232  title is not a software role
      66  N years required
```

**585 country-locked remote** is the number worth noting. That single rule, which
exists because of applications made to such roles by mistake, rejected 585
postings that a naive "contains remote" check would have accepted.

By country: Germany 28, Netherlands 8, India 6, Ireland 6 (of which
**Stripe's "Software Engineer, New Grad" in Dublin**, carrying a graduate
signal), plus Helsing's 22 genuine software and AI roles in Munich and Berlin.

53 of 56 state no years at all, which is why "needs human review" is a digest
section rather than an accept.

## Not done yet

Verdicts exist; nothing reports them. Milestone 4 adds `ChangeDetector` and
`DigestWriter`, and the salary floors in `application.yml` still have no reader.
