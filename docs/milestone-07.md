# Milestone 7 — `probe`

**Goal:** test a candidate token across all four company-board platforms.
**Result:** five live boards found on the first real run, from twelve guesses.
189 tests.

## What was built

| Path | Notes |
|---|---|
| `fetch/TokenProber` | One request per platform per token |
| `fetch/ProbeResult` | FOUND / EMPTY / ABSENT / ERROR / ALREADY_KNOWN / SKIPPED |
| `cli/ProbeCommand` | `probe --tokens=a,b,c [--add]` |
| `HttpFetchClient.getRaw` | Returns the status instead of throwing on it |

## The distinction the command is built around

Each platform was tested with a deliberately nonsense token first, because what
"not found" means turned out to differ:

| Platform | Unknown token |
|---|---|
| Greenhouse | `404 {"error":"Job not found"}` |
| Ashby | `404 Not Found` |
| Lever | `404 {"error":"Document not found"}` |
| **SmartRecruiters** | **`200 {"totalFound":0,"content":[]}`** |

So on three platforms absence is provable, and on the fourth it is not.
SmartRecruiters answers 200 for any company name whatsoever, which makes an empty
board and a token that never existed literally the same response.

`ABSENT` is therefore reserved for a real 404, and an empty SmartRecruiters
result reports *"answers 200 for unknown companies — not proof of absence"*.

**This also corrects Milestone 5.** The eight seeded SmartRecruiters tokens
returning nothing were described there as tokens that "may be dead". The API
cannot support even that much: they return empty, and no request to
SmartRecruiters can distinguish a renamed company from one with no openings.
Establishing which needs a careers page opened by hand.

## What is not probed

**Amazon.** Its tokens are country codes, not companies, and all four are swept
on every run.

**The `KNOWN_ABSENT` list.** Forty-nine companies confirmed absent from all four
platforms across two rounds of guessing. Probing one costs four requests to learn
what is already known. Recorded with them: the Ashby token `navi` belongs to a
San Francisco aviation startup, not to Navi the Indian fintech.

**Already-seeded boards**, which report `ALREADY_KNOWN` without a request.

## Token conventions differ

Greenhouse, Ashby and Lever use lowercase handles; SmartRecruiters uses the
company's own casing — `PHONEPELIMITED`, `DeliveryHero`. So `"Delivery Hero"` is
probed as `deliveryhero` on three platforms and as `Delivery Hero` on the fourth.

## `--add` is opt-in

A probe is a guess. A wrong guess that quietly joins the daily run is worse than
one that does not, so the command reports and stops unless told otherwise.

## The first real run

Twelve guesses, four requests each:

| Token | Platform | Postings |
|---|---|---|
| workhuman | **Ashby** | 10 |
| letsgetchecked | **SmartRecruiters** | 1 |
| gocardless | **Greenhouse** | 23 |
| primer | **Ashby** | 24 |
| zopa | **Lever** | 31 |

`juspay` was skipped as confirmed-absent, without a request.

**Workhuman and LetsGetChecked are both on the user's own Ireland CSEP
volume-sponsor list, and neither was on any token list.** That is exactly the
gap this command exists to close: the best match of the search so far came from
probing a company that appeared on no list.

## And the honest result

All five boards were added and fetched — 89 postings — and produced **zero
candidates**.

Workhuman has three Dublin roles: two Customer Service Executive and one Senior
Product Manager. The remaining seven are Massachusetts or UK. LetsGetChecked's
single posting failed geography and title before its description was ever
fetched.

That is not a failure of the probe. It found five real boards that were invisible
before, two of them at companies with documented Irish sponsorship history. They
are now checked every morning for free, and the first Dublin engineering
requisition either posts will appear in a digest without anyone remembering to
look.

A clean negative is a real result.

## Verified

- `mvn test` — 189/189
- Live probe of twelve tokens: five found, one skipped, the rest correctly ABSENT
- All five added, fetched and screened: 89 postings, 0 candidates
