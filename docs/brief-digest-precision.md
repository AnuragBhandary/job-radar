# Brief: digest precision

Written 2026-09-15, after reading that day's digest by hand and throwing away
most of it. Everything below is a defect observed in that real digest, not a
hypothetical. The job of this phase is that tomorrow's digest needs no manual
curation for the classes of noise listed here.

## What went wrong on 2026-09-15

The run screened 10,160 postings and produced 6 candidates and 60 "needs human
review" rows. Of the 60, a reader applying the configured strategy kept about
eight. The rest:

| Noise | Examples from the real digest | Root cause |
|---|---|---|
| Onsite roles in countries the strategy excludes or has no policy for | DeepL and OpenAI in New York / San Francisco, Doctolib Milan, OpenAI Tokyo | `DigestService` filters candidates on `StrategyOutcome.RECOMMENDED` but builds the review list without that predicate |
| Student-only internships | Bosch "Pflichtpraktikum ...", "Internship in Software Testing", "PreMaster Programm" | title exclusions match whole words: `praktikum` never fires inside `pflichtpraktikum`, `intern` never fires inside `internship` |
| Non-software "engineer" titles | Lab engineer, Process Engineer, Maintenance Engineer, Product Stability Test Engineer, PLC & Industry automation engineer, Complaint Management, Werkvoorbereider / Detail Engineer, Junior Project Engineer Services | the include token `engineer` is broad and these manufacturing titles were not in the exclude list |
| Years stated only in the title | PhonePe "Site Reliability Engineer - AWS (4 to 8 Years)" | `YearsExtractor` is only ever given the description |
| Requisitions open for months or years | Databricks 411d, Scale AI 581d, OpenAI 190d, mixed in with today's postings | staleness is printed on the row but the row still sits in the main list |
| No Gulf lane | Scale AI "Software Engineer - New Grad", Doha: "no floor established for Qatar", tier UNKNOWN | AE, SA and QA exist in `geo.countries` but not in `strategy.countries`, although `match.country-preference` already says the Gulf leaves the highest surplus after remote-from-home |
| No ranking | the strongest role of the day was 3rd in one list and 9th in another | both lists sort by recency only; `MatchScorer` exists and is not used by the digest |

## Scope

### 1. The review list obeys the strategy

`needsHumanReview` rows must pass the same `candidate` predicate (verdict
CANDIDATE and outcome RECOMMENDED or null) as the candidate list. Nothing is
deleted: an excluded row is still stored, searchable, and on `/jobs` behind a
filter. It just stops being printed every morning.

### 2. Gulf countries get a strategy row

Add AE, SA and QA to `job-radar.strategy.countries` as `PRIMARY`, with **no salary
floor**. The config's own rule applies: a fabricated threshold would be quoted
back later as though it had been checked. The tier is a preference, recorded in a
comment as such, and is the first thing to revisit if the preference changes.

### 3. Title exclusions that match what boards actually write

- Student-only programmes: `internship`, `pflichtpraktikum`, `praktikant`,
  `premaster`, `abschlussarbeit`, `masterarbeit`, `bachelorarbeit`, `thesis`.
- Manufacturing and plant "engineer" titles seen in the corpus: `lab engineer`,
  `process engineer`, `maintenance engineer`, `stability test`, `plc`,
  `industry automation`, `complaint management`, `werkvoorbereider`,
  `detail engineer`, `project engineer`.

Every addition gets a test that it rejects its real title, and the existing
accepted-title list must keep passing. Titles that must **not** start failing:
"SAP Test Automation Engineer", "Software Engineer - Java", "Site Reliability
Engineer", "Platform Engineer", "Data Engineer", "Software Engineer, New Grad".

### 4. Years stated in the title

Screening also reads the title. The rule is narrower than the description rule,
because a title is short and a false positive there rejects a whole role:

- only the plural or range forms count (`4 to 8 Years`, `5+ years`, `3-5 yrs`);
  a singular `2 Year` is a duration ("2 Year Rotational Programme"), not a bar;
- a number followed closely by programme, rotational, contract, fixed-term or
  term is never a requirement;
- the stricter of the title and description minimums wins; the reject reason
  says which one it came from.

### 5. A digest that ranks, and puts old requisitions aside

- **Start here**: the top five of the day's candidate, review and updated rows,
  ranked by `MatchScorer`, deduplicated, excluding stale rows. One line each,
  with the score and its band. This is the only new section above the fold.
- **Stale rows** (posted at least `match.stale-days` ago) leave the candidate,
  review and updated lists and are summarised as one line with a count. Still
  never a filter on eligibility: an old requisition at a company worth applying
  to is still one click away on `/jobs`.

## Non-goals

- No change to eligibility (`Verdict`) semantics beyond the title and years
  rules above. No change to anything that fills an application form.
- No notification delivery. It needs a credential only the owner can create, and
  a digest that is still noisy should not be pushed to a phone.
- No new boards. Gulf employers on the supported ATS platforms are worth probing
  (`probe --tokens=...`), but adding boards changes what is fetched, and that is
  a separate decision.

## Verification

1. Unit tests for each rule, in the style of the existing `TitleFilterTest`,
   `YearsExtractorTest`, `CountryStrategyTest` and `DigestWriterTest`.
2. Full suite green.
3. Against the real database: `screen` then `digest`, and compare with the
   2026-09-15 digest. Report the before and after counts for each section, and
   name every row that moved, so a rule that is too greedy is caught by reading
   the list rather than by missing a job later.

## Result on real data (2026-09-15, same fetch, re-screened)

| Section | Before | After |
|---|---|---|
| Start here | - | 5 |
| New candidates | 6 | 3 |
| Needs human review | 60 | 19 |
| Stale requisitions set aside | - | 4 (counted) |

No row was added. Every row that left was read, and each is intended:

- 17 onsite roles in countries the strategy excludes or does not recommend
  (United States, Japan, Italy, Canada, Poland, Switzerland, Sweden);
- 14 Bosch student programmes, plant roles and coded senior titles;
- both PhonePe "(4 to 8 Years)" roles;
- Philips Process Engineer, and on the second pass Network Development Engineer,
  "Mid level - Fullstack" and "Software Leader-DXR" (variants of existing rules);
- 4 stale requisitions (Databricks, Camunda, GoCardless, Channable), counted.

Still in the review list and left for a decision rather than a rule: "Citizen
Developer", "MES Application Engineer" and "VM Brakes BSW Developer" (Bosch).
The Gulf lane works end to end: Scale AI's Doha new-grad role is recommended and
ranks third. It would rank higher if `match.country-preference`, which is keyed
on the legacy five-value `Country` enum, knew about the Gulf; it scores it as
OTHER.

1,203 tests, 0 failures (42 new).
