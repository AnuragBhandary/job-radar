# Milestone 4 — change detection and the digest

**Goal:** report only what changed.
**Result:** a daily markdown digest; re-fetching a board removes it from the next
digest. 144 tests pass.

## What was built

| Path | Purpose |
|---|---|
| `domain/PostingStatus` | NEW / UPDATED / SEEN / CLOSED |
| `diff/ChangeDetector` | Classifies each fetch, closes stale postings |
| `digest/DigestService` | Decides what goes in the digest |
| `digest/DigestWriter` | Renders markdown, writes `digests/YYYY-MM-DD.md` |
| `digest/SalaryFloorAdvisor` | States the applicable floor and why |
| `cli/DigestCommand`, `cli/RunCommand` | `digest`, `run` |

## Two enums, not one

`Verdict` answers "is this worth applying to?"; `PostingStatus` answers "what did
today's fetch find?". They are independent, and collapsing them would lose the
common case: a posting that is a CANDIDATE and SEEN is eligible, was reported
yesterday, and should not be reported again.

## The rule that needed care

**A board that failed to fetch never closes its postings.** A 404 makes every
posting on that board look absent, so a naive sweep would mark them all CLOSED
and the digest would report that forty companies simultaneously stopped hiring.
`closeStale` takes only the boards whose fetch actually succeeded.

This is the same distinction board health exists for, and it is the one place in
the change detector where getting it wrong would be both silent and dramatic.

## Celonis, again

Diffing is always on `descriptionHash`, never on the board's own `updated_at`.
Celonis bulk-refreshes that field on every posting daily, so there it reports
change constantly and means nothing. A field that is wrong in a way that looks
like it is working is worse than no field at all.

`PostingMapper.hash` normalises whitespace only, so reformatting is not a change
— but "2 years" becoming "2+ years" is, which is the edit that matters most.

## What goes in which section

A candidate stating no years goes to **Needs human review**, not to **New
candidates**. Two reasons: the two sections would otherwise report the same
posting twice, and the candidate list stays worth trusting. Absence of a number
is a question, not evidence of an entry-level role.

On the real corpus that split is stark — **3 candidates with stated years, 25
needing review.** Most European postings state no experience requirement at all.

## Salary floors get a reader

`SalaryFloorAdvisor` prints the floor that applies to each candidate and the
reason it is that number:

```
Floor: Rs 14,00,000 (relocation: ~Rs 30k/month rent and food)
Floor: EUR 40.904 (CSEP basic salary; below EUR 48.000 Dublin rent makes it ~Mumbai 10L)
```

**No salary is ever estimated.** Most postings state none, and a guessed number
in a digest gets quoted back later as though it came from the posting. The floor
is stated so a human can compare once they know the real figure.

When `verify-by` has passed, the digest opens with a warning that the Blue Card,
CSEP and kennismigrant thresholds are re-indexed annually and need re-checking at
source.

## The one bug

Rupee amounts printed as `Rs 700,000` instead of `Rs 7,00,000`, which invites a
misread as seven million — in a digest whose entire purpose is comparing salary
against a floor.

Neither obvious fix works. `NumberFormat.getInstance(Locale.of("en","IN"))`
returned `700,000` on this JDK, and `DecimalFormat` only honours the *rightmost*
grouping interval of a pattern, so `"#,##,##0"` also produces `700,000` — the
lakh grouping cannot be expressed as a pattern at all. Written by hand and pinned
by tests, including `1,25,00,000`.

Worth noting as a category: this is the first bug in the project that was neither
silent nor caught by a test I wrote for it. It was visible in the output the
moment the digest was rendered.

## Verified

- `mvn test` — 144/144
- Full run from an empty database: 46 boards, 5,893 postings, 28 candidates,
  digest written to `digests/2026-09-06.md`
- Re-fetching Stripe alone: its 615 postings moved NEW → SEEN, the review section
  dropped 25 → 24, and rejections dropped 5,865 → 5,251. **A board that has not
  changed leaves the digest entirely.**

## Configuration changes

Defence boards and Forward Deployed Engineer roles are now excluded, at the
user's direction: defence roles require EU citizenship or security clearance, and
FDE roles are customer-facing with experience expectations that make them a poor
bet right now. Helsing is excluded by board rather than deactivated, so its
postings still count towards board health and re-including it is a config edit.

That took candidates from 56 to 28.

## Not done yet

`readExistingApplications` does not exist, so the digest cannot yet suppress
companies already applied to. Milestone 5 adds the remaining four fetchers.
