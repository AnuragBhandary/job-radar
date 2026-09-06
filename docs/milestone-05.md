# Milestone 5 — the remaining four ATS integrations

**Goal:** Ashby, Lever, SmartRecruiters and Amazon.
**Result:** 93 boards, 10,386 advertised postings, 8,683 stored, 94 candidates.
171 tests pass.

## What was built

| Path | Notes |
|---|---|
| `fetch/AshbyFetcher` | One request per board; `descriptionPlain` is already plain |
| `fetch/LeverFetcher` | Bare JSON array; description is split across `lists[]` |
| `fetch/SmartRecruitersFetcher` | Paginates, then fetches detail per shortlisted posting |
| `fetch/AmazonFetcher` | One "board" per country code; screens on `basic_qualifications` |
| `fetch/FetchBatch` | Separates board size from postings kept |
| `config/BoardTokenSeeder` | 93 tokens across five sources, plus `KNOWN_ABSENT` |

Every parser was written against a live response captured first, and every
fixture is a trimmed copy of one — never hand-written JSON.

## What each board actually returns

- **Ashby** publishes drafts through the same feed, flagged `isListed: false`.
  It also splits multi-site roles across `location` and `secondaryLocations`, so
  a role open in San Francisco *and* Dublin lists only San Francisco unless the
  secondaries are read.
- **Lever** returns a bare array, and splits the description: `descriptionPlain`
  is the opening blurb, while the requirements live in `lists[]` as HTML.
  **Reading only `descriptionPlain` would screen every Lever posting on its
  marketing copy.** `createdAt` is epoch milliseconds, not an ISO string.
- **SmartRecruiters** has no descriptions in its list response at all.
- **Amazon** writes dates as `September  4, 2026` — two spaces, because the day
  is padded rather than trimmed, which a strict formatter rejects outright. Its
  `location` is `IN, KA, Bengaluru`, unreadable by the geography filter;
  `normalized_location` gives `Bengaluru, Karnataka, IND`.

## The one place per-posting fetching is correct

SmartRecruiters costs a request per posting, so `GeoFilter` and `TitleFilter`
run **inside the fetcher**, before the detail fetch. Only survivors are fetched
individually.

The numbers justify it. DeliveryHero advertises 982 postings; one survives.
Coolblue advertises 478; twelve survive. Fetching every detail would have been
1,460 requests to keep 13 postings — and they belong to someone else's server.

This is the only place a fetcher may reasonably know about the filters, and it is
why those two are injected into it.

## Board size and postings kept are different numbers

Introducing that filter created a reporting bug immediately: `Freshworks`
reported **1 posting** when the board advertises 149. Board health was recording
the shortlist.

That matters because board health exists to distinguish "this company has nothing
for me" from "this board stopped working". Recording the shortlist collapses the
two. `FetchBatch` now carries both numbers: `boardTotal` for health, the posting
list for the digest.

## A failure mode the design did not anticipate

**Eight of the fourteen seeded SmartRecruiters tokens now return HTTP 200 with
`totalFound: 0`.**

```
Personio, Siemens, Bosch, Contentful, N26, TradeRepublic, GetYourGuide, Zalando
```

Verified by hand against the API — the endpoint accepts any company name and
answers with an empty board rather than a 404.

The board-health design assumed a broken board would announce itself with an
error status. Here there is no error to record, and a dead token is
indistinguishable from a company with no openings. The digest now names any board
that returned nothing, as a thing to verify by hand rather than a clean negative.

Four of the eight — N26, Trade Republic, GetYourGuide and Zalando — are also on
Greenhouse, where they work, so the coverage loss is limited to **Personio,
Siemens, Bosch and Contentful**.

## Amazon, and the rule that finally has data

The non-internship rule has had nothing to bite on for three milestones. It now
rejects **101 postings**, all of them Amazon's own phrasing:

> 5+ years of non-internship professional software development experience

And the graduate line the search was built to find:

| Source | Title | Location |
|---|---|---|
| AMAZON/IRL | Software Development Engineer – 2026 | Dublin |
| AMAZON/DEU | Software Development Engineer - 2026 | Berlin |
| AMAZON/DEU | Software Development Engineer - 2026 | Berlin |
| GREENHOUSE/stripe | Software Engineer, New Grad | Dublin |

**Amazon Dublin runs a graduate requisition line separate from Berlin's**, and
sweeping all four country codes is what surfaced it.

Amazon's description is deliberately `basic_qualifications` plus
`preferred_qualifications`, not the role blurb. The years extractor takes the
smallest number it finds, and prose about how long a team has existed is exactly
the stray small number that would flip a genuine rejection. It also sharpens
change detection: a reworded blurb is not worth a digest line, an edited
requirement is.

## Four more false positives, all config fixes

The first multi-ATS run surfaced titles no single-source run could have:

- **"Legal Counsel, EU Employment Law (Platform Workers)"** — matched the include
  token `platform`.
- **"Developer Relations Engineer - Developer Advocate"** — devrel, not software.
- **"Logistiek Engineer"** — Dutch for logistics engineer.

Added `legal`, `counsel`, `attorney`, `paralegal`, `compliance officer`,
`developer relations`, `developer advocate`, `devrel`, `logistiek`, `logistics`,
`warehouse`. 98 → 94 candidates, no code changed.

## Verified

- `mvn test` — 171/171, none touching a live endpoint
- Full run: 93 boards, 0 failed, 10,386 advertised, 8,683 stored, 94 candidates
- Per-source candidates: Amazon 40, Greenhouse 28, Ashby 18, SmartRecruiters 7,
  Lever 1

## Worth a decision

**Coolblue supplies 9 of the 11 SmartRecruiters candidates.** Its published bands
straddle the €4,357 kennismigrant floor, so a junior offer may not support the
permit at all, and many of its ads are Dutch-only. It is currently fetched and
screened like any other board.

## Not done yet

The digest still cannot suppress companies already applied to. Milestone 6 adds
the Sheets client — read first, append second.
