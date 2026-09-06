# Milestone 6 — the Google Sheets writer

**Goal:** read the application tracker, and append to it safely.
**Result:** live read verified against the real sheet; 17 applications found;
9 candidates suppressed in the digest. 182 tests. **Nothing has been written to
the sheet.**

## What was built

| Path | Notes |
|---|---|
| `sheets/ApplicationRow` | One tracker row, in the sheet's column order |
| `sheets/SheetsClient` | `readExistingApplications`, `appendApplication`, `updateStatus` |
| `config/SheetsConfig` | Lazy service-account credentials |
| `cli/SheetListCommand` | `sheet-list` — read-only *(addition)* |
| `cli/SheetAppendCommand` | `sheet-append --posting-id=N`, confirms before writing |

## Append only, and why it is worth being strict about

The tracker is the only record of where the user has applied. A bug that
overwrites a row destroys information that exists nowhere else, and no amount of
re-fetching brings it back — unlike everything else in this program, which can be
rebuilt from the boards in two minutes.

So the write surface is deliberately narrow:

- `appendApplication` only ever adds, using `INSERT_ROWS` so it inserts rather
  than overwriting whatever follows the data.
- `updateStatus` is the only method that writes over an existing cell. It writes
  exactly one, the caller has to name the row, and it refuses any row above the
  data.
- `sheet-append` prints the full row and asks before writing. With no console —
  from a scheduler, say — it refuses rather than assuming yes.

`valueInputOption=RAW` matters: it stops Sheets reinterpreting what is written,
turning a date into a serial number or a role like "Engineer I" into something it
takes for a formula. Writing through the API also sidesteps the autocomplete
corruption that affects typing into the grid, where Sheets offers a longer
previous entry and Tab accepts it silently — the bug that once turned "Junior
Software Engineer" into "Junior Software Engineer (Java/Spring)".

## What the live read found

**The header is at row 7, not row 6.** The spec documents rows 1–5 as
instructions and row 6 as the header; on the live sheet a line had been added
above, shifting everything down. The first implementation hardcoded row 7 as the
first data row and duly read the header as an application — 18 applications
instead of 17, with a company called "Company".

This is precisely what the spec warned about: *find the first empty row below the
header rather than assuming a fixed offset*. `findFirstDataRow` now locates the
header by looking for a first cell reading "Company" and starts after it, falling
back to row 7 only if no header exists at all.

It is a small bug with a large blast radius. The same offset assumption in
`appendApplication` would eventually have written a row over real data.

**Row 13's company is `"\nStripe"`** — a leading newline, from manual entry.
Harmless here because comparison lowercases and trims, but it is the sort of
thing that makes an exact-match lookup silently miss.

## Failing softly, and being able to check that it did

`appliedCompanies()` returns an empty set when Sheets is unconfigured or
unreachable, and logs a warning. Losing the tracker should cost the digest its
suppression, not its existence — fetch, screen and digest all work with no
credential at all.

But quiet degradation needs a way to be checked deliberately, which is why
`sheet-list` exists. It is not in the original command set; it was added because
"the digest suppressed nothing" and "the digest could not read the tracker" look
identical from the outside otherwise.

## Suppression

The digest now hides candidates at companies already in the tracker, and **says
how many it hid**. A digest that quietly shrinks is one you stop trusting.

On the live data that is 9 candidates, taking the review queue from 86 to 78.

## Credentials

The service-account key lives at `~/.config/job-radar/google-key.json`, mode 600,
referenced by path only. Nothing from it is logged: failures report the path and
the reason, never the contents. `~` expansion is done explicitly, because the JVM
does not do it.

## Verified

- `mvn test` — 182/182
- `sheet-list` against the live tracker: 17 applications, rows 8–24, correct
  companies, roles and statuses
- `digest` with the tracker configured: 9 candidates suppressed
- **No write has been performed.** `sheet-append` has not been run against the
  real sheet.

## Not done yet

`probe` (Milestone 7), then scheduling, Docker and the README (Milestone 8).
