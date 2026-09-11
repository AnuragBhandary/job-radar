# job-radar

A job search for one person, run from their own laptop. It reads public ATS job
boards, screens and ranks what it finds, tailors a resume from an evidence bank
of approved career facts, fills application forms in a real browser and stops
before the submit button, then tracks what happens next.

It is a Spring Boot application with two faces: a command-line tool (every
command below) and a local web application at `localhost:8080`. Both read one
SQLite database.

```
Java 25 · Spring Boot 3.5 · Spring Data JPA / Hibernate 6.6 · SQLite · Maven
Playwright · Google Sheets API · Gmail API (read-only) · server-rendered HTML
1,160 tests · no test touches the network or opens a browser
```

The repository is public and holds no personal data. The applicant's profile,
resume and evidence bank live in `~/.config/job-radar/`, outside the repository;
the committed `*.example.yml` files are skeletons.

---

## Contents

- [Product vision](#product-vision)
- [What exists today](#what-exists-today)
- [Architecture](#architecture)
- [Finding jobs](#finding-jobs)
- [Understanding and ranking](#understanding-and-ranking)
- [Resume intelligence](#resume-intelligence)
  - [Requirement extraction](#requirement-extraction)
  - [Coverage ledger](#coverage-ledger)
  - [Evidence bank](#evidence-bank)
  - [Deterministic tailoring](#deterministic-tailoring)
  - [Variants, rendering and validation](#variants-rendering-and-validation)
- [Where language models are used](#where-language-models-are-used)
- [The rewrite benchmark and its safety layer](#the-rewrite-benchmark-and-its-safety-layer)
- [Preparing applications](#preparing-applications)
- [Knowledge: answering form questions](#knowledge-answering-form-questions)
- [Tracking](#tracking)
- [The web application](#the-web-application)
- [Commands](#commands)
- [Configuration](#configuration)
- [Setup](#setup)
- [Scheduling](#scheduling)
- [Testing](#testing)
- [Project structure](#project-structure)
- [Known limitations](#known-limitations)
- [Future architecture](#future-architecture)
- [Design notes](#design-notes)

---

## Product vision

```
FIND ─▶ UNDERSTAND ─▶ RANK ─▶ TAILOR ─▶ GENERATE ─▶ AUTOFILL ─▶ TRACK ─▶ LEARN
```

The goal is a personal job-search and application system: find postings, read
what they ask for, rank them, tailor application material from facts the
applicant has approved, fill forms, and learn from what happens. A person
decides what gets sent.

Out of scope by decision: networking and referrals, interview preparation, and a
general career-assistant chatbot. Two earlier features touch the last two - an
interview-prep pack (`prep`) and a chat assistant (`/chat`). They still work and
are documented below; they are not being extended.

## What exists today

Each line is one of three things: **implemented** (works now), **foundation**
(the architecture a later feature will build on is in place, the feature is not),
or **planned** (not started).

| Area | Feature | Status |
|---|---|---|
| Discovery | Job aggregation from 7 ATS platforms | Implemented |
| | Fresh-job detection (new, updated, closed) | Implemented |
| | Job quality filtering (titles, years, country-locked remote, excluded boards) | Implemented |
| | Visa / work-authorisation strategy by country, relocation and remote kept apart | Implemented |
| | Advanced filters on the job list | Implemented |
| | Match scores (0-100, five factors) | Implemented |
| | Requirement analysis | Implemented |
| | Match explanation / gap analysis | Implemented in the CLI (`ledger`, `evidence --plan`) and as score bars on the posting page; not yet a gap view in the UI |
| | Salary intelligence | Partial: visa salary floors, stated-pay extraction, an asking price per country, INR conversion. No market data |
| | Personalised recommendations | Partial: the ranked, strategy-filtered feed and the daily worklist |
| | Job alerts | Partial: a daily markdown digest. No notification delivery |
| | External job import | Partial: applications made elsewhere import from the Google Sheet. No import of arbitrary job URLs |
| | AI job matching | Foundation: the coverage ledger and evidence bank; matching today is deterministic |
| Resume | Evidence bank | Implemented |
| | Deterministic tailoring from approved evidence | Implemented |
| | Coverage analysis | Implemented |
| | Resume variants (approved summaries, approved bullet wordings) | Implemented |
| | Resume rendering to one-page PDF | Implemented |
| | Resume match score | Foundation: the ledger computes weighted coverage; not shown as a score |
| | ATS optimisation | Foundation: skills ordered by what the posting requires, evidence ranked by it; no ATS-specific scoring |
| | AI constrained editing | Foundation: the rewrite validators and benchmark exist; no editor |
| | Cover letters | Implemented (optional model, validated, template fallback); not yet drawn from the evidence bank |
| | Application answers | Implemented through the knowledge resolver; open-ended answers are drafted for approval |
| Automation | Form autofill in a real browser | Implemented |
| | Tailored resume attached to the form | Implemented |
| | Review before submission | Implemented |
| | Submission | Implemented behind a human confirmation; batch mode never submits |
| Tracking | Application tracker (board + Google Sheet mirror) | Implemented |
| | Job history | Implemented |
| | Follow-ups and reminders | Implemented |
| | Outcome tracking from the inbox | Implemented (Gmail, read-only) |
| | Application analytics | Partial: the home-page funnel and `variants` |

---

## Architecture

```
  Greenhouse  Ashby  Lever  SmartRecruiters  Workday  Recruitee  amazon.jobs
      └────────┴──────┴─────────┬──────┴────────┴─────────┴──────────┘
                                ▼
   fetch/     AtsFetcher per platform ─ HttpFetchClient (throttled, retrying)
              PostingMapper: one RawPosting → Posting, in one place
   diff/      ChangeDetector: NEW · UPDATED · SEEN · CLOSED, on a description hash
   filter/    ScreeningService: title, years, location → CANDIDATE or REJECTED + reason
              LocationClassifier: ISO country, work mode, hiring region
   strategy/  CountryStrategy: which eligible postings belong on today's list
   match/     MatchScorer: 0-100, orders what survived
                                │
                   SQLite (repo/, JPA) ◀──────────────┐
                                │                      │
   ┌────────────────────────────┴───────────────┐      │
   │ resume intelligence (apply/resume/)        │      │
   │  PostingRequirements ─▶ CoverageAnalyzer   │      │
   │    ─▶ CoverageLedger ─▶ EvidenceBank       │      │
   │    ─▶ TailoringPlanner ─▶ ResumeVerifier   │      │
   │    ─▶ ResumeRenderer ─▶ PdfWriter          │      │
   └────────────────────────────┬───────────────┘      │
                                ▼                      │
   apply/     ApplyService: read form ─▶ knowledge resolver ─▶ fill ─▶ STOP
              Submitter: the only class that submits, after a human confirms
   knowledge/ Concept · Assertion · Scope · KnowledgeResolver · positioning
   pipeline/  the board: saved → prepared → applied → … ; mirrored to Sheets
   mail/      read-only inbox scan → proposed status changes
   worklist/  what needs a decision today
   digest/    daily markdown digest
   web/       server-rendered pages and a small JSON API, localhost only
```

The web layer is opt-in per run: `main()` starts a servlet container for `ui`
only, so `fetch` and `digest` never bind a port.

Network traffic leaves the machine for the job boards, and - only when
configured - for the Google Sheet, the Gmail API, an OpenAI-compatible model
endpoint, and a local Ollama server used by the benchmark commands.

---

## Finding jobs

**Seven platforms, one interface.** `fetch/` has a fetcher each for Greenhouse,
Ashby, Lever, SmartRecruiters, Workday, Recruitee and amazon.jobs. Each reads the
board's public API; `PostingMapper` is the single place raw responses become a
`Posting`. Workable, Personio and Teamtailor were investigated and are not
viable - the reasons are recorded on the `Source` enum.

**Boards** are rows in `board_token`, seeded by `BoardTokenSeeder` (about 110
company boards in code) and extended with `probe --tokens=a,b --add`, which
tests candidate tokens across platforms. At the time of writing the local
database held 117 boards, 109 active, and 9,032 postings. A board retired on
purpose is recorded with a `retired:` reason and is not reported as broken.

**Change detection** compares a SHA-256 of the description, never the board's own
timestamp. A board that fails to fetch never closes its postings.

**Captured responses.** With `job-radar.fixtures.enabled`, every raw response is
written to `./fixtures/` (gitignored), which is where test fixtures come from.

## Understanding and ranking

**Screening** (`filter/ScreeningService`) gives each posting a verdict -
`CANDIDATE` or `REJECTED` - and the reason for a rejection, first rule wins:

- `TitleFilter`: include and exclude lists in `application.yml` (seniority,
  non-software disciplines, sales titles), whole-word where a token is also a
  word fragment.
- `YearsExtractor`: the required years, anchored on the requirements section;
  `max-min-years` is the cut-off. "No number stated" is its own outcome, not a pass.
- Location: `LocationClassifier` assigns an ISO country, a `WorkMode` (onsite,
  hybrid, remote country-locked / regional / global) and a hiring region.
- `SignalExtractor`: visa-sponsorship and stated-pay sentences, reported and never
  decisive.
- Whole boards can be excluded with a stated reason.

**Eligibility is not priority.** The verdict answers "could this be pursued at
all" and rejects only on facts. `strategy/CountryStrategy` answers "should it be
on today's list" from `job-radar.strategy`: each country has a relocation tier
(primary, secondary, opportunistic, low, excluded) with relocation and remote as
separate switches, plus a salary floor with its basis and a re-verify date. A
posting's strategic class is one of home, other-in-country, international
relocation, international remote, unclassified.

**Scoring** (`match/MatchScorer`) orders what survived, 0-100: skills 40,
experience 25, location 20, freshness 10, signals 5. Every point traces to a
rule, and the posting page shows the five factors with their reasoning. It is
not a filter.

**Salary.** `money/` shows a foreign figure in rupees at rates configured in
`application.yml` with the date they were set, and gives an asking price per
country from the profile's bands. A band borrowed from another country is never
auto-filled into a form.

**The digest** (`digest`, or `run` = fetch + screen + digest) writes
`digests/YYYY-MM-DD.md`: new candidates, postings stating no years, rejection
counts by reason, companies already applied to, and board health.

---

## Resume intelligence

### Requirement extraction

`apply/resume/analysis/PostingRequirements` reads a posting without a model. It
finds the technologies `prep/TechVocabulary` knows and a short hand-written list
of non-technology requirements (REST APIs, testing, on-call, real-time systems,
backend engineering...), and decides how strongly each is asked for from the
section it appears in: `REQUIRED`, `PREFERRED` or `SIGNAL`. The title counts as
required. Every requirement carries a quote that is a substring of the posting.

`CompoundRequirements` splits "Python / Go" and "Kafka, Redis and PostgreSQL"
into separate requirements - alternatives counted once, at the best option -
only when every part is recognised, so "CI/CD" and "TCP/IP" stay whole. Versions
are stripped and aliases merged ("Postgres" is PostgreSQL, "K8s" is Kubernetes).

A local model can also propose requirements (`ledger --model`); only those whose
quote is found in the posting are kept. This path is an experiment and is not
used when applying.

### Coverage ledger

`CoverageAnalyzer` builds a `CoverageLedger`: for each requirement, the evidence
level the resume supports and the resume items that carry it. The level comes
from `knowledge/experience/ExperiencePositioner`, which walks the resume's
technologies (`ExperienceIndex`) and a hand-written `SkillGraph`:

| Level | Meaning | What it allows on a resume |
|---|---|---|
| DIRECT | the resume names it | claim it |
| ADJACENT | a neighbouring technology he has (Kubernetes → Docker) | show the neighbour, never name the requirement |
| CONCEPTUAL | a technology implementing the same idea | show it, never name the requirement |
| TRANSFERABLE | only general engineering foundations | nothing specific |
| NONE | nothing | leave it out |

Every resume item has a stable id (`ResumeSources`): written on the bullet in the
profile, or derived from its parent and a hash of its text.

### Evidence bank

The architectural rule this project now rests on: **the candidate's factual
career history has one source of truth.** Resume content is selected from it;
cover letters and application answers are meant to draw on it next. A consumer
asks the bank for evidence instead of reading resume prose and guessing:

```java
bank.strongestFor("event-driven backend systems")   // ranked items, each with a reason
bank.evidenceFor(ledgerEntry)                       // the same, respecting the ledger's level
```

When nothing supports a requirement the answer is an empty list, never a nearest
guess.

**Where it lives.** `~/.config/job-radar/evidence.yml` (`JOB_RADAR_EVIDENCE`),
outside the repository. `evidence.example.yml` is the committed skeleton, with
every field explained. It is plain YAML, read directly rather than through
Spring's relaxed binding: an unknown or misspelt field refuses the whole file,
because a misspelt `qualifers:` would otherwise silently drop the qualifier it
holds.

**Shape.** Sources are jobs and projects; items are claims.

```yaml
version: 1
sources:
  - id: example
    kind: employment            # or project
    name: "Example Inc."        # exactly as the resume writes it
    stack: [Python, Kafka]      # true of the whole source
items:
  - id: example-order-replay
    source: example
    claim: >-
      Built the consumer side of a Kafka-based order replay service handling
      approximately 2,000 messages a day with deduplication.
    technologies: [Kafka, Python]
    concepts: [replay, deduplication, event-driven]
    metrics: ["approximately 2,000 messages a day"]
    qualifiers: ["the consumer side of"]
    attribution: shared
    categories: [backend, distributed-systems]
    strength: high
    variants:
      - id: events-first
        text: >-
          Built the consumer side of an event-driven order replay service on
          Kafka, handling approximately 2,000 messages a day with deduplication.
        emphasis: [event-driven]
        approved: true
```

| Field | Meaning | Checked by `EvidenceValidator` |
|---|---|---|
| `id` | stable name, never positional | letters, digits, `.`, `_`, `-`; unique |
| `source` | the job or project | must be a valid source |
| `claim` | the approved sentence; the default wording | taken as true; everything else is checked against it |
| `technologies` | what the work used | named in the claim, or in the source's stack |
| `concepts` | ideas it shows | visible in the claim's words, or an idea one of its technologies implements; product names refused |
| `metrics` | figures as the claim writes them | present in the claim; every figure in the claim must be declared |
| `qualifiers` | words that limit the claim | present in the claim |
| `attribution` | `individual` (default) or `shared` | shared needs a qualifier saying which part was his |
| `categories` | kinds of role it speaks to | lower-case words joined by hyphens; ranking only |
| `strength` | `high`, `medium` (default), `low` | ranking only |
| `context-only` | technologies named but not used ("React clients") | in the claim; never counted as evidence |
| `variants` | other wordings of the same claim | see below |

A **variant** must keep every metric and qualifier, may emphasise only what the
item lists, must differ from the claim, and must pass `ResumeClaimValidator`
against the claim - the validator built for the rewrite benchmark: no new
technology, product, number or responsibility; no lost qualifier or hedge ("roughly
fourfold" cannot become "fourfold", "production-style" cannot become
"production"); nothing tacked onto the end; still recognisably the same
accomplishment. A variant is printed only with `approved: true`.

An item with an error is left out of the bank; a variant with an error is left out
of its item. Nothing is repaired.

**Matching** (`EvidenceMatcher`) is deterministic and explainable in one line.
Kinds of match, strongest first: the item lists the technology; the item's own
words show the idea; one of its technologies implements the idea (`SkillGraph`:
Kafka implements event-driven); the requirement names its technology inside a
longer term ("Kafka Streams" - never a claim); only a general idea matches
("backend engineering"); only the role category matches. The kind always decides
first; within a kind, strength, work over projects, and a stated measured result.
A requirement naming a product can only be answered by an item that lists that
product - "shows streaming" is not evidence of Kafka.

Through the ledger, the bank respects the positioner: a Kubernetes requirement
puts the Docker item forward, marked as not supporting a claim; a product the
ledger places at NONE gets nothing, whatever the bank's concepts say; an idea the
ledger cannot place ("deduplication") is found in the bank's own words.

**Consistency with the resume.** For as long as the profile still carries resume
bullets, each one must be the claim or an approved variant of an item from the
matching source (`ResumeConsistency`). If not, the bank is not used and resumes
are tailored as before, with the reason. `evidence --check` shows every problem.

### Deterministic tailoring

```
posting ─▶ PostingRequirements ─▶ CoverageAnalyzer ─▶ CoverageLedger
        ─▶ EvidenceBank.evidenceFor(each requirement)
        ─▶ TailoringPlanner
             bullets   the most relevant items per job and project, up to the
                       caps, printed in the order the file lists them
             wording   the claim, or the approved variant whose emphasis answers
                       what this posting asks of that item
             projects  by total relevance, the resume's order breaking ties
             skills    SkillOrdering: what the ledger found first; nothing added
             summary   the approved paragraph ResumeTailor chooses
        ─▶ ResumeVerifier ─▶ ResumeRenderer ─▶ PdfWriter
```

`apply/resume/plan/ResumePipeline` is what applications call. Relevance is the
sum, over the ledger's requirements, of the requirement's weight (required 3,
preferred 1, signal 0.5) times the match score. No model is consulted anywhere on
this path, and a test fails the build if a class on it gains a dependency on one.

`ResumeVerifier` checks the result independently of how it was produced: every
bullet is an approved wording of evidence from the source it is printed under;
headers, education and extras are the resume's own; the skills list is a
reordering of the resume's.

It steps aside - the resume is tailored by `ResumeTailor` exactly as it was
before the bank existed - when there is no bank, when the bank and resume
disagree, when verification fails, when the planner throws, or when
`JOB_RADAR_EVIDENCE_TAILORING=false`. A posting that asks for nothing the resume
has renders byte-for-byte the same resume either way.

`evidence --plan --posting-id=N` prints the whole plan - each requirement with
the evidence chosen for it or the reason there is none, each item considered
with its relevance and wording - and `--html=FILE` renders it without a browser.

`ResumeTailor` itself is unchanged: selection and ordering by tag match, used as
the fallback and by `prep`.

### Variants, rendering and validation

**Variants** are of two kinds, both written and approved by the applicant: several
summary paragraphs in the profile (chosen by tag match; `variants` reports which
opening produced replies, and refuses to conclude on a small sample), and
approved wordings of evidence items.

**Rendering.** `ResumeRenderer` produces print-ready HTML for A4 (every value
escaped); `PdfWriter` prints it with the Playwright Chromium the tool already
uses. The caps on projects and bullets per section keep it to one page.

**Every layer that checks a claim:**

| Check | Guards |
|---|---|
| `EvidenceValidator` | the bank: grounding of technologies, concepts, metrics, qualifiers; every variant |
| `ResumeClaimValidator`, `QualifierGuard`, `RewriteQuality` | a wording against its claim (used for variants) |
| `ResumeConsistency` | the resume says nothing the bank does not approve |
| `ResumeVerifier` | the planned resume, before it is rendered |
| `knowledge/experience/ClaimValidator` | answers to "have you used X?" against the positioning |
| `apply/llm/HumanTone` | generated prose: dash punctuation, machine phrasing, structure |
| `CoverLetterWriter` | discards a letter claiming years of experience or with an unfilled placeholder |

---

## Where language models are used

| Where | Model | What it does | Limits |
|---|---|---|---|
| Cover letters (`apply/letter/CoverLetterWriter`) | OpenAI-compatible endpoint, off by default | a letter, only when the form has a text box for one | validated; a template when the model is off or the draft fails |
| Open-ended form questions (`knowledge/ai/AnswerProposer`) | same | drafts an answer only after every deterministic path has failed | checked against the experience positioning; needs approval |
| Assistant panel on the preparation screen (`web/AssistantService`) | same | draft an answer, rewrite the letter, ask about the posting | nothing reaches a form without approval |
| Chat (`chat/ChatService`, `/chat`) | same, with tool calling | six tools: `search_jobs`, `get_posting`, `board_summary`, `profile_summary`, `save_job`, `move_job` | cannot prepare or submit an application |
| Interview prep (`prep/PrepService`) | same, when configured | part of the prep pack | out of product scope; not extended |
| `bench-llm`, `ledger --model` | local Ollama | requirement-extraction experiments | ungrounded requirements dropped |
| `bench-rewrite` | local Ollama | shadow resume-rewrite benchmark | output is a report; unreachable from `apply` |

No model is used to fetch, screen, score, extract requirements when applying,
build the coverage ledger, select resume content, or answer factual form fields.
The model never writes a resume sentence.

## The rewrite benchmark and its safety layer

Before the evidence bank, the project measured whether a local model
(`gemma4:26b` through Ollama) should rewrite resume bullets per posting. Across
two benchmark runs on the same five postings it produced a handful of genuine
improvements and more bullets made worse than better - tacked-on keywords,
inflated objects, awkward wording - while never adding coverage the deterministic
resume lacked. It was not adopted.

What that work left, and where it lives now:

- **Kept and used on the production path:** `ResumeClaimValidator`,
  `QualifierGuard` and `RewriteQuality` (they validate every evidence variant),
  `SkillOrdering` (the planner's skill order), `CompoundRequirements`, the
  source-id scheme, and the coverage ledger.
- **Kept as an experiment, unreachable from `apply`:** `RewritePrompt` (source ids
  pinned in the JSON schema), `RewritePlanner`, `EvidenceScope`, `RewriteParser`,
  `GenerativeTailor`, `ResumeRewriteBenchmark`, `bench-rewrite`. AI summary
  rewriting was removed outright.

The architecture intended for any future model help is narrower: the planner
finds one specific edit opportunity, a model proposes that edit, the same
validators accept or reject it, and an accepted wording becomes a variant only
when the applicant approves it.

---

## Preparing applications

```
apply --posting-id=N
  1. resume     ResumePipeline ─▶ ResumeRenderer ─▶ PdfWriter
  2. open       Playwright, persistent Chromium profile, headed by default
  3. read       FormReader (one injected script) ─▶ FieldClassifier
  4. resolve    KnowledgeResolver ─▶ AnswerPlan ─▶ KnowledgeAnswers ─▶ SemanticOptions
  5. letter     only if the form has a text box for one
  6. fill       FormFiller: types, selects, uploads the resume; cannot submit
  7. stop       screenshot + review.md; the attempt waits for a person
```

`Submitter` is the only class that submits. It runs after a typed `yes` at the
terminal (`apply --posting-id=N --submit`) or after the preparation screen's
"I have read it" box is ticked. `apply --all` prepares a batch and refuses
`--submit`: most boards accept one application per posting, forever.

In the web application, **Prepare** returns at once and `PreparationRunner` does
the browser work on one background thread, recording progress as a stage on the
attempt (queued, tailoring, opening, reading, resolving, filling, captured,
finished, failed). An attempt ends ready for review, awaiting approval, awaiting
an answer, manual required (no form, captcha, unsupported control or site, login
required, automation error) or failed. Each field records two things apart: what
Job Radar knows, and what the browser managed. A resumed attempt reuses the
rendered resume and settled answers and re-reads only the form.

An unrecognised question is never guessed. An answer that matches no option is
refused rather than approximated. A consent checkbox is never ticked. `login`
signs in to a board by hand once; the session lives in the browser profile.

Every attempt writes to `./applications/` (gitignored): the PDF, the letter, a
screenshot and `review.md`.

## Knowledge: answering form questions

`knowledge/` holds what the tool may say on a form.

- **Concepts** (sponsorship, work authorisation, notice period, years of
  experience...) with stable ids and a category: factual, contextual,
  experience, open-ended, sensitive.
- **Assertions**: stored answers with a **scope** (application, company, country,
  work mode, strategic class, global) and a source (session, user input, user
  rule, profile, resume, derived, historical, AI-proposed), in that order of
  authority. A context-sensitive concept cannot be stored globally: a sponsorship
  answer learned for Germany is never a candidate on an Indian form.
- **Derivations**: sponsorship and work authorisation from the country the
  employee will actually sit in (not the job's country); years of experience from
  the resume's dates, refusing if any period is unparseable.
- **Experience positioning** for "have you used X?", with the five levels above.
  A positioning is never stored.

`KnowledgeResolver` decides real form answers
(`job-radar.knowledge.resolver-authoritative`, default true;
`JOB_RADAR_KNOWLEDGE_AUTHORITATIVE=false` rolls back). The older `FieldMapper`
stays compiled in as the fallback and as the oracle for `knowledge --shadow`,
which compares the two across every recorded question and posting.

`/knowledge` reviews assertions that arrived without a scope; `/answers` answers
questions that blocked a form, effective on the next form without a restart.

## Tracking

- **The board** (`pipeline/`): saved, prepared, applied, screening, interview,
  offer, rejected, dropped - with notes, a date applied and a reminder date.
  `board --import` seeds it from the Google Sheet.
- **Google Sheets**: the tracker is append-only and a mirror; the database is the
  source of truth. Nothing before "applied" is written to it.
- **Inbox** (`inbox`, `/setup`): Gmail with the `gmail.readonly` scope. Replies are
  matched to applications and classified; acknowledgements do not count as
  replies, and rejection phrases are checked first. `--apply` writes the proposed
  status.
- **Follow-ups** (`follow-up`): applications quiet for 14 days, oldest first;
  `--close-abandoned` marks six-week silences.
- **Today** (`worklist/`): one task per posting - answer a blocked question,
  approve a draft, send a prepared application, finish a manual one, chase a
  quiet one, act on a reminder, fix a broken board.

## The web application

`ui` serves `http://127.0.0.1:8080`: server-rendered HTML, one stylesheet and
two small scripts in `src/main/resources/static/`, no build step.

| Route | Page |
|---|---|
| `/` | Today: what needs a decision, then outcomes (sent, replies, in process, new this week) |
| `/jobs` | the ranked feed, filtered on strategic lane, country, work mode, freshness and status; 25 per page |
| `/posting/{id}` | one posting: the match factors with their reasoning, and Prepare |
| `/attempt/{id}` | the preparation screen: progress, what needs the applicant, fields, resume |
| `/board` | the pipeline board |
| `/knowledge`, `/answers` | stored knowledge to review; blocked questions to answer |
| `/strategy` | lanes, countries by relocation tier, salary floors (read-only) |
| `/chat` | the assistant |
| `/setup` (`/mail`) | Gmail connection and board health |

A JSON API under `/api/preparation` and `/api/field/{id}` backs the preparation
screen; the page also works with JavaScript off.

**Localhost only.** It binds `127.0.0.1` and has no login. The machine holds live
job-board session cookies, a Gmail token and a Google service-account key: do not
expose the port. For access from another device, use a private network such as
Tailscale.

---

## Commands

Run with `mvn spring-boot:run -Dspring-boot.run.arguments="<command> <options>"`.

| Command | Does |
|---|---|
| `fetch [--source=X] [--token=Y]` | read boards into the database |
| `screen` | apply the filters; record verdicts and reasons |
| `digest` | write and print today's digest |
| `run` | fetch + screen + digest |
| `probe --tokens=a,b,c [--add]` | test candidate board tokens |
| `serve` | stay running for the daily schedule |
| `sheet-list` / `sheet-append --posting-id=N` | read the tracker / record an application, after confirming |
| `apply --posting-id=N [--submit]` | prepare an application and stop; `--submit` asks at the terminal |
| `apply --posting-id=N --resume-only` | render the tailored resume only |
| `apply --all [--limit=5]` | prepare the candidate list; refuses `--submit` |
| `applications [--status=X]` | what has been prepared, sent or blocked |
| `learn [--write]` | questions that blocked forms, as profile entries |
| `follow-up [--days=14] [--close-abandoned]` | applications that have gone quiet |
| `inbox [--days=60] [--apply]` | read replies; propose or write status changes |
| `login --url=... \| --list` | sign in to a board by hand |
| `ui` | the web application |
| `board [--import]` | the pipeline in the terminal; seed it from the sheet |
| `variants` | which resume opening has produced replies |
| `prep --posting-id=N [--print]` | interview-prep pack (legacy, not extended) |
| `knowledge` | concepts and assertions; `--migrate`, `--review`, `--shadow [--verbose] [--attempts]`, `--explain="..." [--posting-id=N]`, `--position=X`, `--audit`, `--adopt`, `--repair[=dry]` |
| `evidence` | what the bank holds and whether resumes use it |
| `evidence --check` | every problem in the file and against the resume |
| `evidence --for="a requirement" [--limit=5]` | the strongest evidence for one requirement |
| `evidence --plan --posting-id=N [--html=FILE]` | the resume plan for a posting; `--html` renders it |
| `ledger --posting-id=N [--verbose]`, `--bench`, `--sources` | the coverage ledger (read-only; JSON under `build/reports/`) |
| `bench-llm [--models=a,b]` | compare local Ollama models on requirement extraction |
| `bench-rewrite [--posting-ids=a,b]` | the shadow rewrite benchmark |

## Configuration

`src/main/resources/application.yml` holds strategy and rules; personal data and
keys are imported from outside the repository:

| File | Holds | Committed? |
|---|---|---|
| `~/.config/job-radar/applicant.yml` | profile, compensation bands, resume (`job-radar.resume`) | no; `applicant.example.yml` is the skeleton |
| `~/.config/job-radar/evidence.yml` | the evidence bank | no; `evidence.example.yml` is the skeleton |
| `~/.config/job-radar/secrets.yml` | model endpoint and key (`job-radar.llm.*`) | no |
| `~/.config/job-radar/google-key.json` | Sheets service-account key | no |
| `~/.config/job-radar/gmail-oauth.json`, `gmail-tokens/` | Gmail OAuth client and refresh token | no |

| Environment variable | Meaning | Default |
|---|---|---|
| `JOB_RADAR_DB` | JDBC URL | `jdbc:sqlite:./job-radar.db` |
| `JOB_RADAR_PROFILE`, `JOB_RADAR_SECRETS` | profile and secrets files | `~/.config/job-radar/…` |
| `JOB_RADAR_EVIDENCE` | evidence bank file | `~/.config/job-radar/evidence.yml` |
| `JOB_RADAR_EVIDENCE_TAILORING` | plan resumes from the bank | `true` |
| `JOB_RADAR_KNOWLEDGE_AUTHORITATIVE` | knowledge resolver decides form answers | `true` |
| `JOB_RADAR_OUT` | digest directory | `./digests` |
| `JOB_RADAR_SCHEDULE`, `JOB_RADAR_CRON` | daily schedule | `false`, `0 0 7 * * *` |
| `JOB_RADAR_FIXTURES`, `JOB_RADAR_FIXTURE_DIR` | capture raw responses | `true`, `./fixtures` |
| `JOB_RADAR_APPLICATIONS` | attempt output directory | `./applications` |
| `JOB_RADAR_BROWSER_PROFILE`, `JOB_RADAR_HEADLESS` | Chromium profile, headless mode | `~/.config/job-radar/browser`, `false` |
| `JOB_RADAR_SHEET_ID`, `JOB_RADAR_GOOGLE_KEY` | tracker spreadsheet, service-account key | unset (Sheets off), `~/.config/job-radar/google-key.json` |
| `JOB_RADAR_GMAIL_KEY`, `JOB_RADAR_GMAIL_TOKENS` | Gmail OAuth client, token directory | `~/.config/job-radar/…` |
| `JOB_RADAR_LLM`, `JOB_RADAR_LLM_URL`, `JOB_RADAR_LLM_MODEL`, `JOB_RADAR_LLM_KEY`, `JOB_RADAR_LLM_REASONING` | model endpoint | off; any OpenAI-compatible endpoint |
| `JOB_RADAR_UI_PORT`, `JOB_RADAR_UI_BIND` | web server | `8080`, `127.0.0.1` |

Screening rules, the country vocabulary, the strategy, salary floors, match
weights and currency rates are all in `application.yml`, commented.

For Gemini's OpenAI-compatible endpoint set `reasoning-effort: none`: Gemini 2.5
spends thinking tokens against `max_tokens` and otherwise returns HTTP 200 with an
empty message. The free tier allows about 20 requests a minute; the client waits
out a rate limit once.

## Setup

Requires JDK 25 and Maven.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home
mvn test
mkdir -p ~/.config/job-radar
cp applicant.example.yml ~/.config/job-radar/applicant.yml
cp evidence.example.yml ~/.config/job-radar/evidence.yml
mvn spring-boot:run -Dspring-boot.run.arguments="evidence --check"
mvn spring-boot:run -Dspring-boot.run.arguments="run"
mvn spring-boot:run -Dspring-boot.run.arguments="ui"
```

Point `JAVA_HOME` at 25: Homebrew's Maven brings a newer JDK, which breaks
Hibernate's bytecode generation. Chromium downloads itself on first use. Every
command except `apply` works without a profile; applying needs the profile, and
resumes are planned from the bank only once `evidence --check` reports it
consistent with the resume.

## Scheduling

`serve` with `JOB_RADAR_SCHEDULE=true` runs fetch, screen and digest at 07:00
Asia/Kolkata - only while the process is running; a missed slot is not caught up.
Two better options ship with the repository:

- `deploy/com.anuragbhandary.job-radar.plist`: macOS launchd, which reruns a
  missed calendar job when the machine wakes.
- `Dockerfile`: builds the jar and runs `serve` with the schedule on; mount a
  volume at `/data` for the database, digests and captured responses.

`SchemaMigrator` runs before Spring starts and widens SQLite `CHECK` constraints
when an enum gains a value, which Hibernate cannot do.

## Testing

```bash
mvn test
```

1,160 tests. None contacts a live endpoint or opens a browser: fetchers run
against trimmed real responses in `src/test/resources/fixtures/`, persistence
tests use a real SQLite file under `target/`, and form logic is pure functions
tested on wording real boards use. `CliContextTest` boots the CLI context exactly
as `main()` does for non-web commands. CI (`.github/workflows/ci.yml`) runs
`mvn verify` on every push and pull request to `main`, with no secrets.

The evidence architecture's own suites: `EvidenceBankLoaderTest`,
`EvidenceValidatorTest`, `EvidenceMatcherTest`, `ResumeConsistencyTest`,
`TailoringPlannerTest`, `PipelineSafetyTest`, `EvidenceCommandTest`. They run on
an invented resume and bank (`EvidenceFixtures`) and check, among other things,
that no printed bullet is anything but an approved wording, that metrics and
qualifiers survive every wording, that an unapproved variant is never printed,
that the result is deterministic, that a posting asking for nothing renders the
old resume byte-for-byte, and that the committed example files agree.

## Project structure

```
src/main/java/com/anuragbhandary/jobradar/
  fetch/          one fetcher per ATS, HTTP client, raw-response recorder, token prober
  diff/           change detection
  filter/         screening, location and work-mode classification, years extraction
  strategy/       country tiers and outcomes
  match/          0-100 match score
  money/          salary floors, asking price, currency display
  digest/         the daily digest
  domain/ repo/   Posting, BoardToken, enums; Spring Data repositories
  evidence/       the evidence bank: model, loader, validator, matcher, resume consistency
  apply/          ApplyService, preparation runner, attempts, fields, answers
    form/         form reading, classification, filling, option matching, Submitter, PDF
    resume/       ResumeModel, ResumeTailor, ResumeRenderer
      analysis/   requirement extraction, compound requirements, coverage ledger, source ids
      plan/       TailoringPlanner, ResumePipeline, ResumeVerifier, TailoringPlan
      rewrite/    claim validators and the rewrite experiment
    letter/ llm/  cover letters; the model client and HumanTone
  knowledge/      concepts, assertions, scopes, resolver, derivations, shadow comparison
    experience/   experience index, skill graph, positioning, claim validation
    ai/           drafted answers to open-ended questions
  pipeline/ sheets/ mail/ followup/ worklist/   tracking
  chat/ prep/     the assistant and the interview-prep pack
  bench/          local-model benchmarks
  web/            controllers and the shared page components
  cli/            one class per command
  config/ schedule/
src/main/resources/   application.yml, static/ (app.css, app.js, prep.js)
docs/                 milestone-by-milestone build log; design/ (the UI stylesheet source)
deploy/               launchd job
applicant.example.yml, evidence.example.yml   skeletons for the files in ~/.config/job-radar/
```

Gitignored local output: `job-radar.db`, `digests/`, `fixtures/`,
`applications/`, `build/reports/`.

---

## Known limitations

- **The resume bullets live in two places for now.** The profile still carries
  them, because the experience index, the ledger's source ids and the fallback
  tailor read them there; the bank must repeat each one as a claim or approved
  variant, and `ResumeConsistency` enforces that they agree. Moving those readers
  onto the bank is the next step toward a single copy.
- **Summaries are not in the bank.** The approved summary paragraphs stay in the
  profile, and their facts are not checked against the bank.
- **The skills list is not evidence.** A technology in the skills list or in a
  bullet's tags is DIRECT to the ledger even with no bullet showing it in use; the
  plan reports such requirements as "no evidence item shows it in use". A tag on a
  bullet that only delivers to a technology (React clients) has the same effect.
- **Matching is lexical.** Stems, curated phrases and the skill graph; an
  equivalence nobody wrote down does not match.
- **The planner does not measure page length.** The caps keep a resume to one page;
  an approved variant much longer than its claim could push it over.
- **Cover letters and answers do not read the bank yet.** They use the tailored
  resume and the profile.
- **The deterministic requirement reader is literal.** A posting with no section
  headings yields `SIGNAL` for everything.
- **One user, one machine.** SQLite, localhost, one browser profile, one
  preparation at a time.

## Future architecture

Planned, not implemented. Each item names what it would build on.

- **One copy of the facts**: the experience index, source ids and fallback tailor
  read the bank; resume bullets leave the profile.
- **Cover letters and application answers from evidence**: `strongestFor` per
  requirement, claims and approved variants only, the same validators.
- **Constrained editing**: the planner names one edit opportunity, a model
  proposes it, `ResumeClaimValidator` judges it, the applicant approves it into a
  variant.
- **Resume match score and gap view in the UI**: from the ledger's weighted
  coverage and the plan's coverage rows.
- **ATS optimisation**: on top of evidence ranking and skill ordering, never by
  adding unsupported keywords.
- **Job alerts and external import**: notification delivery for the digest; a
  posting from an arbitrary URL through the same mapper and screening.
- **Salary intelligence**: market data beside the floors and asking prices.
- **Analytics and learning**: outcomes by lane, country and resume variant.

## Design notes

**A posting's identity is `(source, boardToken, externalId)`, not its URL.**
Several ATSs rewrite public URLs without the posting changing.

**Change detection diffs the description hash, never the board's timestamp.** One
ATS bulk-refreshes every posting's `updated_at` daily, which makes that field
useless in a way that looks like it is working.

**`minYears` distinguishes "not screened" from "states no requirement".** Treating
the absence of a number as evidence of an entry-level role is the error the
screening rules exist to prevent.

**A city name is not a place.** "Dublin, Ohio" is checked before the target lists
claim "Dublin", and place-name patterns are Unicode-aware, because `\b` in Java
regex is ASCII-only and silently never matched "Malmö".

**"Distributed" is not a remote marker.** It matched "Distributed Systems" in a
title and made an onsite role read as globally remote.

**Workday reports its own size inconsistently.** Only the largest total a board
reports is trusted, and the crawl stops on a short page as well.

**The tracker is append-only.** It is the one record that cannot be rebuilt by
re-fetching.

**Selection, never generation, for anything that states a fact about the
applicant.** Tailoring picks among approved sentences; a model that rewrote
bullets produced better prose and claims that had to be defended in an interview.
The evidence bank makes the approved sentences explicit, checkable data.

**An unknown field refuses the evidence file.** A misspelt key that binds as
nothing is the most dangerous failure a data file can have, because everything
still appears to work.

Full build log, milestone by milestone, in [`docs/`](docs/).
