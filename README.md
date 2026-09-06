# job-radar

A scheduled command-line pipeline that reads public ATS job boards, screens the
results against a fixed eligibility and salary profile, works out what changed
since yesterday, and writes a short daily digest.

No web UI, no REST API. It is a batch job that produces a markdown file and
updates a spreadsheet.

```
106 boards · 9,000 postings · 64 candidates · 8,936 rejection reasons you can argue with
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
  Workday     ──┤   │    SmartRecruiters and Workday filter    │
  amazon.jobs ──┘   │      before fetching descriptions        │
                    └────────────────┬─────────────────────────┘
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
                    │    SignalExtractor adds visa and pay,    │
                    │      reported but never decisive         │
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

Six fetchers behind one interface; mapping to the domain happens in exactly one
place. The JPA layer is database-agnostic — moving to PostgreSQL is a datasource
URL and a dialect in `application.yml`.

---

## Stack

Java 25 · Spring Boot 3.5 · Spring Data JPA / Hibernate 6.6 · SQLite ·
Maven · JUnit 5 + Mockito (**311 tests**) · `java.net.http.HttpClient` · Jackson ·
Google Sheets API · Playwright · Docker

No test contacts a live endpoint and none opens a browser. Every fixture is a
trimmed copy of a real response captured during a live run, and the form logic —
which label means what, which answer follows from the posting's country, which
dropdown option matches — is pure functions tested on the wording real boards use.

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
| `apply --posting-id=N` | Tailor the resume, fill the form, **stop before submit** |
| `apply --posting-id=N --submit` | The same, then ask at the terminal before sending |
| `apply --posting-id=N --resume-only` | Render the tailored resume only. No browser, no board |
| `apply --all [--limit=5]` | Prepare the candidate list. Refuses `--submit` |
| `applications [--status=NEEDS_HUMAN]` | What has been prepared, sent or blocked |

---

## Applying

`apply` does everything up to the submit button and then stops.

```
posting ──▶ resume/ResumeTailor ──▶ ResumeRenderer ──▶ PdfWriter ──▶ tailored.pdf
                 (selects and orders; cannot write a sentence)

        ──▶ form/FormReader ──▶ FieldClassifier ──▶ FieldMapper ──▶ FormFiller
                 (one injected     (label → kind)    (kind + posting    (types it in;
                  script reads                        → answer)          has no submit
                  the live page)                                         button)

        ──▶ letter/CoverLetterWriter    only if the form has a text box
        ──▶ screenshot + review.md ──▶ STOP
                                        └─ Submitter, only on a typed 'yes'
```

Everything before the last line is mechanical and costs twenty minutes a posting
by hand. The last line is not, so it is not automated.

**Why it stops.** Most boards accept one application per posting, forever. A form
filled from a misread label does not cost a rejection — it costs the good
application that could have been made instead. `--all` therefore refuses
`--submit`: batch mode prepares, a human sends.

**Tailoring is selection, never generation.** Every sentence on the resume was
written by the applicant and lives in `applicant.yml`. The tailor chooses which
summary opens, which projects lead and which bullets survive; it has no way to
write a sentence. Handing the posting and the resume to a model produces better
prose and quietly promotes "integrated ElevenLabs TTS" into "led speech
infrastructure" — a sentence that then has to be defended in an interview.

**A cover letter goes in a text box and nowhere else.** An optional attachment slot
on an ATS is read by nobody. The letter is checked before it is used: a draft
containing a years-of-experience claim, an unfilled `[Company]`, or a sign-off is
discarded in favour of the template rather than repaired.

**An unrecognised question stops the application.** It is never guessed at, never
filled with something plausible and never left blank in the hope that it was
optional. `applications --status=NEEDS_HUMAN` prints every question that stopped a
run, which is the list of edits that make the next one go further.

### Setup

```bash
cp applicant.example.yml ~/.config/job-radar/applicant.yml   # then fill it in
```

That file holds a home address, EEO self-identification and salary bands, so it
lives outside this repository and `application.yml` imports it as `optional:`.
Everything except `apply` works without it.

The cover-letter model is optional and off by default. Any OpenAI-compatible
endpoint works:

```bash
export JOB_RADAR_LLM=true
export JOB_RADAR_LLM_KEY=...        # Groq's free tier, or Gemini's OpenAI-compatible endpoint
```

Chromium downloads itself on first use, into `~/Library/Caches/ms-playwright`.

---

## Sample digest

```markdown
# job-radar — 2026-09-06

## New candidates (8)

- **Netradyne** — Associate Database Engineer — Bengaluru, Karnataka, India
  Years: 1 | Graduate signal: no | posted: 4d ago
  Floor: Rs 14,00,000 (relocation: ~Rs 30k/month rent and food)

- **Grafana Labs** — Backend Engineer - Platform - Stacks | Ireland | Remote
  Years: 1 | Graduate signal: no | posted: 110d ago — stale
  Floor: EUR 40.904 (CSEP basic salary; below EUR 48.000 Dublin rent makes it ~Mumbai 10L)
  Stated: the Base compensation range for this role is EUR 81,000 - EUR 102,000.

## Needs human review — no years stated (56)

- **N26** — Junior iOS Engineer - Payments — Berlin
  Years: none stated | Graduate signal: no | posted: 5d ago
  Floor: EUR 45.934,2 (Blue Card shortage-occupation threshold)
  Visa: supportive: A relocation package with visa support for those who need it

## Rejected (8936)
- 5333 — outside target geographies
- 760 — country-locked remote
- 544 — title excluded on 'senior'
- 216 — title excluded on seniority level
- 127 — non-internship experience required

_9 candidate(s) hidden — already applied to that company._

## Board health
All 106 boards healthy — 12228 postings.
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
gone and, unlike everything else here, cannot be rebuilt by re-fetching. Only a
`SUBMITTED` attempt reaches it; twelve rows saying `PREPARED` would destroy the
one question the sheet answers.

**"Authorised to work here?" and "need sponsorship?" are the same fact asked in
opposite polarity, and both depend on the posting's country.** India and
global-remote: yes and no. Everywhere else: no and yes. Neither is stored as a
value — a constant answer is wrong for one of those two cases every time, and both
questions are auto-reject triggers. `REMOTE` follows India because a global-remote
role is worked from home on an Indian contract.

**An answer that matches no dropdown option is refused, not approximated.** On a
two-option yes/no field the closest wrong option is the opposite answer.

---

**A number in the "Preferred" section is not the bar.** Amazon's Network Dev
Engineer I required "2+ years of IT Security experience" and preferred "1+ years
of automation scripting". Taking the smallest number in the document read the
requirement as 1 and shipped a two-year role as entry level. The years extractor
now anchors on the requirements heading — the *last* one, because Stripe invites
you to apply "even if you don't meet all the preferred qualifications" 57
characters before its real "Minimum requirements" heading, and cutting at the
first mention threw the requirements away entirely.

**"Experience (non-internship) in professional software development" states no
number, and is still a rejection.** It is Amazon's SDE II wording. The
disqualifier used to require `N years of` in front of it, so 26 mid-level AWS
roles — Firecracker, Shield, RDS Platform — sat in the candidate list, 30% of
everything in it. Matching the bare token everywhere would be simpler and wrong:
"internship and non-internship candidates welcome" is an invitation.

**Workday reports its own size inconsistently.** Philips answers `total: 809` at
offset 0, `total: 0` at offsets 780 and 800, and `total: 809` again at 820 —
which is past its own end and still returns a full page. Believing the latest
figure set the total to zero mid-crawl and ended the fetch on page one, so 809
postings were read as 20. Only the largest figure a board ever reports can be
trusted, and the crawl needs both a short-page and a total-based stop.

**A city name is not a place.** `dublin ohio` sat in the exclusion list and could
never fire: the target list matched "dublin", returned Ireland and never reached
the exclusions. False friends are now checked first, and on adjacency after
punctuation is flattened — so "Dublin, Ohio" is caught while "Dublin, Ireland;
Columbus, Ohio" is not.

**"(v/m/x)" is Dutch for "f/m/x", not Roman numeral five.** The seniority pattern
read that "v" as a level and rejected Coolblue's Dutch postings as senior roles.
Gender tags are stripped before any pattern sees the title. Bare digits had the
same shape: "6 month contract" was a seniority rejection, and "Software Engineer,
2 Year Rotational Programme" — the exact graduate fast-track the years extractor
protects — would have been discarded by the title filter before the years logic
ever ran.

**Visa and salary are reported, never decisive.** Of 1,428 postings in the target
geographies, 55 mention sponsorship, 218 mention relocation and 306 state a
figure. That is a fifth of the list carrying the two facts that decide most, so
they are extracted — but absence means nothing, and a filter built on them would
throw away the other four fifths. No salary is parsed into a number and no
comparison against the floors is made: the floor is printed beside the stated
figure and a human does the subtraction.

**Adding a value to an enum is a schema migration.** `Source.WORKDAY` failed on
insert against `check (source in (...))`, which SQLite cannot `ALTER`. It failed
loudly rather than silently, which is `hbm2ddl.halt_on_error` earning its keep —
but the fix is to rebuild the table. Let Hibernate create a fresh database, copy
the rows across with explicit column lists (the column *order* differs), and swap
the files. `Country` has the same constraint and will need the same treatment.


## What I learned
**A guard that only fires when the data is shaped as expected is not a guard.**
Splitting descriptions at "Preferred" fixed the case it was written for and broke
three postings it was not: 6-, 8- and 10-year Stripe roles became candidates,
because the split had thrown away the section stating those numbers. The tests
still passed. The regression was visible only in a before-and-after diff of the
real corpus — which is now the last step of every filter change, ahead of the
test suite.

**Every new board teaches the filters something.** Adding Workday brought in NXP
and Philips, and with them 1,569 postings of chip design and medical devices. The
first run offered "Digital Physical Design Engineer" and "X-Segment Physics
Engineer" as candidates, and classified "Remote Service Engineer - CT" — a field
engineer who drives to hospitals — as globally remote, because "remote" was the
first word of the job title rather than a working arrangement. Exactly the shape
of the "distributed" bug, found the same way: by reading the output.


**"Race / Ethnicity" classified as a city field.** "ethnicity" contains "city".
This is the "distributed" bug again, one milestone later and in a new file, and it
would have put the applicant's home city in a US EEO dropdown. The rule that finally came out of it:
a matcher token that is also a fragment of common English needs a word boundary,
and the short ones always are. Every short token in the field classifier now
matches on boundaries.

**A default that competes is not a default.** The general-purpose resume summary
was tagged `backend`, which appears in almost every title this tool surfaces — so
it scored on the title every time and the four specialist summaries could never
win. The fallback now carries no tags at all and wins only at zero.

**Padding that loses a cascade looks exactly like padding that is absorbed.** The
skills column had no gap. `box-sizing: border-box` on a shrink-to-fit cell was a
plausible cause and was the wrong one: `table.skills td` beats a bare `.sk-items`
on specificity, so the rule never applied. The visible symptom of a specificity
loss and of a box-model quirk are identical.

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
