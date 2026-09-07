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
  Recruitee   ──┤   │      before fetching descriptions        │
  amazon.jobs ──┘   │                                          │
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
Maven · JUnit 5 + Mockito (**419 tests**) · `java.net.http.HttpClient` · Jackson ·
Google Sheets API · Gmail API · Playwright · Spring MVC · Docker

The web layer is opt-in per run: `main()` picks `WebApplicationType.NONE` for
every command except `ui`, so `fetch` does not start a servlet container to make
HTTP requests and `digest` does not hold a port while writing a markdown file.

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
| `learn [--write]` | Questions that blocked forms, as profile entries |
| `follow-up [--days=14] [--close-abandoned]` | Applications that have gone quiet |
| `inbox [--days=60] [--apply]` | Read replies, update the tracker's status column |
| `login --url=... \| --list` | Sign in to a board by hand, once per employer |
| `prep --posting-id=N [--print]` | Interview pack: gaps, questions, your own answers |
| `variants` | Which resume opening has actually produced replies |
| `ui` | Feed, board, chat and review at http://localhost:8080 |
| `board [--import]` | The pipeline in the terminal; `--import` seeds it from the sheet |

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

### After it is sent

```
inbox      ──▶ classify each reply ──▶ propose a tracker status ──▶ --apply writes it
follow-up  ──▶ rows still "Applied" after 14 days, oldest first
learn      ──▶ every question that stopped a form, as profile entries to paste
prep       ──▶ what this posting names that your resume does not
variants   ──▶ which resume opening produced replies (and whether that means anything)
```

**Acknowledgements are not replies.** Every application produces one within a
minute, so counting them clears the follow-up list and reports total success.

**Rejections are written to sound like near-misses**, so they share almost all
their vocabulary with invitations: *"we would like to invite you to the next
stage"* and *"we have decided not to invite you to the next stage"* differ by two
words. The negation carries the meaning, so rejection markers are phrases and are
checked first.

**`variants` prints its own sample size and refuses to conclude below it.** Two
replies from five against one from six looks like a 140% improvement and is three
coin flips.

### The feed, the board and the chat

`ui` serves four pages.

**The feed** ranks every candidate by a 0-100 match score rather than by date.
Sorting by date was close to random: a posting is not more relevant for being
newer. The score is arithmetic - skills 40, experience 25, geography 20,
freshness 10, signals 5 - and every point traces to a rule, which is what lets
the posting page show it as five bars with the reasoning under each.

It is **not a filter**. Screening already decides what is eligible and says why
per rejection; the score only orders what survived.

**The board** is the pipeline: saved, prepared, applied, screening, interview,
then offer, rejected or dropped. `board --import` seeds it from the spreadsheet,
which is worth running once - the tracker holds applications made before any of
this existed, and a board that started empty would be a worse record than the
sheet on its first day.

The database is the source of truth and the sheet is a mirror. Writes go local
first; a failed sheet write is logged and rolls nothing back. Nothing before
APPLIED is mirrored, because the tracker is the record of applications sent and
filling it with bookmarks would destroy the one question it answers.

**The chat** is a Gemini assistant with six tools: it can search postings, read
one in full, summarise the board and the profile, bookmark a job and move a card.
It cannot submit an application and does not offer to.

Two things to know about the free tier. It allows **twenty requests a minute**,
and one chat turn with tool calling spends three or four, so a rate limit is the
normal failure rather than an exceptional one. The client reads the delay out of
the API's own error and waits it out once. And Gemini 2.5 thinks by default,
charging those tokens against `max_tokens`, so a small cap returns HTTP 200 with
an empty message and no error - hence `reasoning-effort: none`.

### The assistant

The review page has a panel with three modes, separated because they carry
different risk:

- **Draft an answer** to an application question. Grounded in the profile, and
  told to reply `NOTHING` rather than invent one. Asked about Kubernetes and
  Terraform, which are nowhere in the resume, it returns nothing at all.
- **Rewrite the letter**, optionally against a typed steer. Saved to the attempt.
- **Ask about this posting**, which is for reading and goes nowhere.

Anything that would be sent to an employer runs through `HumanTone`:

**Dash punctuation is rewritten, machine phrasing is rejected.** Different
mechanisms on purpose. A dash is a formatting habit and removing one changes
nothing the sentence claims; the tells are whole phrases, and cutting one out
leaves the sentence around it still shaped wrong. Only punctuation dashes go, so
`event-driven` and `scikit-learn` survive intact.

**A rejected draft is regenerated once, with the offending words named.** The
second attempt is a different request from the first. A third would be the same
request again.

**Structure is checked, not just vocabulary.** The first draft that passed every
phrase filter was still obviously generated: one unbroken block, nine sentences,
eight of them starting with "I", no mention of the job. Every sentence was fine
and the shape was wrong.

### It runs on your machine only

`ui` binds `127.0.0.1`. There is no login, because there is no second user and no
route in: the port is not reachable from another machine, including one on the
same wifi.

That is a deliberate trade and it rests on the binding. The machine holds a
Chromium profile with live session cookies for every job board signed into, a
Gmail refresh token and a Google service-account key, so **do not expose this
port**. To reach it from a phone, put the machine on a private network with
[Tailscale](https://tailscale.com) rather than the app on a public one, and add a
password first.

**GitHub Pages cannot host this.** Pages serves static files; this is a Spring
Boot server that drives a browser.

### Setup

```bash
cp applicant.example.yml ~/.config/job-radar/applicant.yml   # then fill it in
```

The model is optional and lives in a separate file, because a profile is personal
data you might one day show someone and a key is not:

```yaml
# ~/.config/job-radar/secrets.yml   (chmod 600, gitignored)
job-radar:
  llm:
    enabled: true
    base-url: https://generativelanguage.googleapis.com/v1beta/openai
    model: gemini-2.5-flash
    api-key: ...
    reasoning-effort: none
```

Any OpenAI-compatible endpoint works, including Anthropic's. `reasoning-effort:
none` matters on Gemini 2.5: it thinks by default and charges those tokens
against `max_tokens`, so a small cap returns HTTP 200 with an empty message and
no error at all.

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

**A consent checkbox is recognised and never ticked.** Agreeing to a company's
terms on someone's behalf is not form-filling. It is left for the human along
with the submit button.

**Adding a `Source` value is no longer a database rebuild.** Hibernate writes a
SQLite `CHECK` constraint listing the enum names, SQLite cannot alter one, and
`ddl-auto` will not notice it is too narrow — so the failure arrives at the first
insert, on a table that looks correct. `SchemaMigrator` patches the stored DDL
before Spring starts, unions rather than replaces the value list, and recreates
the indexes that the `DROP` would otherwise take with it.

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

**One live form found more than the whole test suite.** Driving a single Ashby
application end to end turned up eight defects, every one of which reported
success while the page on screen disagreed: a relative resume path whose failure
surfaced on a field called "Name"; a referral question filled with the applicant's
own name because its label contains the words "full name"; a selector fallback of
`input:nth-child(3)` that was not scoped to a parent and resolved to a hidden file
input three sections away; checkbox groups read as one field per option, so the
blocked list filled with entries like "I agree"; Yes/No toggles built from bare
`<button type="submit">` that a reader querying `input, select, textarea` cannot
see at all — two required questions sat empty while the report said zero blockers;
comboboxes where `fill()` sets a value the widget never notices; a
`Map<String, String>` of profile answers whose keys Spring canonicalised, so every
key with a space or a slash bound mangled or not at all; and a blank profile value
that printed as `null`.

**The `Map<String, String>` one is the one to remember.** It bound without error,
the property was present, and it was simply empty of the entries that mattered —
so the escape hatch appeared to work while answering nothing. A list of records
binds every character as written.

**A form that looks submittable and is not is the worst output this tool has.**
Worse than a crash, which is visible. That is why unreadable widgets are now read,
and why a click that navigates is a hard failure rather than a logged warning.

**`"\s"` in a Java string is not the regex whitespace class.** It is the
escaped-space literal added in Java 15, so `split("(?<=[.!?])\s+")` compiles,
runs, and quietly splits on spaces only. A four-sentence list read as three and
slipped under a check written to catch exactly it. Two rounds of prompt
engineering went into a missing backslash.

**A cleanup regex ate the thing it was about to be judged on.** `removeDashes`
collapsed runs of whitespace with `\s{2,}`, which includes newlines, so every
paragraph break was destroyed before the structure check ran, and the model was
then told off for writing one unbroken block it had not written.

**A few-shot example gets its words copied, not just its shape.** Adding a worked
letter was the only thing that produced paragraph breaks, and the very next draft
lifted its closing two sentences verbatim. Twenty applications carrying the same
last line is precisely the problem the example was added to solve, so the
example's own phrasing is now contraband and checked for.

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
