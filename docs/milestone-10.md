# Milestone 10 — the loop after the application

**Goal:** everything the roadmap listed, and whatever a real form turned up.
**Result:** 419 tests, six new commands, a sixth ATS, a schema migrator, and eight
silent defects found by driving one live Ashby application end to end.

## What was built

| Path | Notes |
|---|---|
| `followup/` | `follow-up` — reads the tracker's status column, which nothing ever read |
| `apply/AnswerBank` | `learn` — questions that blocked forms, as profile entries |
| `mail/` | `inbox` — Gmail read-only, classify replies, propose tracker updates |
| `fetch/RecruiteeFetcher` | A sixth ATS. One request per board, descriptions included |
| `config/SchemaMigrator` | Widens enum CHECK constraints so a new `Source` is not a rebuild |
| `cli/LoginCommand` | `login` — sign in by hand, once per Workday employer |
| `web/` | `ui` — the review queue, and the only reason there is a web layer |
| `prep/` | `prep` — interview pack; the useful half is set arithmetic |
| `apply/VariantReport` | `variants` — which resume opening produced replies |

## The eight bugs one form found

Every one reported success while the page disagreed.

1. **The resume path was relative.** `setInputFiles` refused it, and the failure
   landed on whatever field the file input's label resolved to — on Ashby, "Name".
2. **A referral field was filled with the applicant's own name.** *"If you have
   discussed this role with a current Camunda employee, please enter their full
   name here"* contains "full name". That states on an application that he was
   referred by himself, which is a claim that gets checked.
3. **The selector fallback was `input:nth-child(3)`**, not scoped to a parent, so
   it matched the third child of any element in the document. Playwright's
   `.first()` picked the hidden resume input, and "Location" failed with *"Input
   of type file cannot be filled"*.
4. **Checkbox groups were read one field per option**, so the blocked list filled
   with "I agree" and "Less than 2 years" — option text posing as questions.
5. **Yes/No toggles built from `<button type="submit">` were invisible.** Two
   required questions sat empty while the report said zero blockers. This is the
   worst thing the tool can output: a form that looks submittable and is not.
6. **Location and country are comboboxes.** `fill()` sets a value the widget's own
   state never sees, so the field submits empty.
7. **`extra-answers` was a `Map<String, String>`.** Spring canonicalises map keys,
   so a key with a space or a slash — every real question — bound mangled or not
   at all. Silently: the map bound fine, just without the entries that mattered.
8. **`Answer.profile("")` produced a PROFILE answer holding an empty string**, so
   the blocked list printed `Post Code — null`.

And a ninth, introduced while fixing the second: the referral guard matched
`referred` as a substring, so *"What is your preferred name?"* became UNKNOWN.
Third time in this file. **Short matcher tokens need word boundaries. Always.**

Result on that form: 19 of 24 fields, no blockers. The five it leaves are correct
— an autofill uploader, an unset optional, a referral name, one group whose
question cannot be located, and a consent checkbox.

## Consent is its own kind, and is never ticked

Agreeing to a company's terms on someone's behalf is not form-filling. `CONSENT`
exists so the review says *"tick it yourself after reading it"* rather than filing
it under "no configured answer" as though the profile were incomplete.

## Enum values are no longer a database rebuild

`SchemaMigrator` patches the DDL SQLite stores rather than regenerating it, so
column order is preserved and `INSERT ... SELECT *` is safe. It **unions** rather
than replaces the value list — a retired value still in the rows would fail the
copy — and recreates indexes explicitly, because they follow the table through a
`RENAME` and die with the `DROP`.

Verified in production: constraint widened, 9,000 rows kept, all three indexes
back including the composite unique key that the custom dialect exists to defend.

## Three platforms investigated and not added

- **Workable** — the public widget returns account metadata and an empty `jobs`
  array for every board tried; `spi/v3` needs an OAuth token.
- **Personio** — its XML publishes an empty `jobDescriptions`, and the job pages
  are client-rendered. Screening depends on the description, so the feed alone is
  not enough and the alternative is a browser per posting.
- **Teamtailor** — no public feed; the API needs a per-company key.

Recorded on `Source` so the next person does not spend the afternoon.

## What the reports refuse to say

- **`inbox` drops acknowledgements.** Every application produces one within a
  minute, so counting them as replies clears the follow-up list and reports total
  success.
- **`inbox` never contradicts a human.** A row already at "Round 2" only moves for
  an offer or a rejection.
- **`variants` prints its sample size and refuses to conclude below it.** Two
  replies from five against one from six looks like a 140% improvement and is
  three coin flips.
- **`learn` only ever proposes a decline.** Anything else would be the tool
  deciding the applicant's answer to a question he has not seen.

## Still open

- Nothing has been submitted through this path. Filling is exercised against real
  pages; `Submitter` is not.
- One Ashby checkbox group's question cannot be located, so it reports as
  unanswerable rather than being answered wrongly. Correct, and still a gap.
- `inbox` matches on company name, so two applications to one company cannot be
  told apart.
