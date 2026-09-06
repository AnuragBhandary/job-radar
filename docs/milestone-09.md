# Milestone 9 — prepare the application, stop at the submit button

**Goal:** turn a candidate in the digest into a filled-in application without
twenty minutes of retyping the same address.
**Result:** 311 tests, per-posting resume tailoring, a cover letter that only
appears where there is a box for it, a form filler that cannot submit, and a
review file that says what is about to be sent.

## What was built

| Path | Notes |
|---|---|
| `apply/ApplicantProfile` | Name, address, EEO, salary bands. Its own properties root so the personal file can live outside a public repo |
| `apply/resume/ResumeModel` | The resume as data. The only source of claims in the project |
| `apply/resume/ResumeTailor` | Picks the summary, the projects, the bullets. Cannot write a sentence |
| `apply/resume/ResumeRenderer` | Print CSS, A4, one page |
| `apply/form/PdfWriter` | HTML → PDF through the browser already on the classpath |
| `apply/form/FormReader` | One injected script; label resolution the way a person reads a page |
| `apply/form/FieldClassifier` | Label → `FieldKind`, ordered rules, first match wins |
| `apply/form/FieldMapper` | `FieldKind` + posting → `Answer`. Pure, and where the country logic lives |
| `apply/form/OptionMatcher` | Answer → one of the dropdown's real options, or nothing |
| `apply/form/FormFiller` | Types it in. Has no submit button |
| `apply/form/Submitter` | The only class with a click in it |
| `apply/letter/CoverLetterWriter` | Drafts, then validates, then falls back |
| `apply/ApplicationAttempt` | The audit record. New table, so no CHECK-constraint rebuild |
| `cli/ApplyCommand` | `apply`, `--submit`, `--all`, `--resume-only` |

## Where the line is, and why it is there

Everything up to the submit button is mechanical. The submit button is not.

Most boards accept **one application per posting, forever**. That changes the cost
of being wrong. A form filled from a misread label does not cost a rejection — it
costs the good application that could have been made instead, at a company that
keeps the record. So the default run ends with a filled browser window, a
screenshot and `review.md`, and sending is a second, explicit act.

`--all` refuses `--submit`. It is the most obviously useful combination and the
one that makes this a liability: twenty applications sent unattended from a form
misread once is not twenty chances taken.

Four refusals in `Submitter`: an unsubmittable `FillReport`, an unconfirmed call,
a captcha on the page, and no button it can positively identify. A captcha is the
board saying it wants a human; it gets one, and the browser is left open.

## Tailoring is selection, never generation

Every sentence on the rendered resume was written by the applicant and lives in
`applicant.yml`. The tailor chooses which of five summaries opens, which two
projects lead and which bullets survive. It has no code path that produces text.

The alternative — hand the posting and the resume to a model, ask for a tailored
version — reads better and is not worth it. It promotes "integrated ElevenLabs
TTS" into "led speech infrastructure", and that sentence then has to be defended
in an interview against a work history that has no payroll record behind it.

The model is used for one thing: a cover letter, only where the form has a text
box. Its output is **validated and discarded, never repaired** — a draft
containing a years-of-experience claim, an unfilled `[Company]`, or a sign-off
falls back to the template. A model that invented once has not earned a retry.

## The two questions that are actually one fact

```
posting country      "authorised to work here?"     "need sponsorship?"
INDIA / REMOTE                yes                          no
anywhere else                 no                           yes
```

Asked separately, in opposite polarity, and both are auto-reject triggers. Neither
is stored as a value; both are derived from `Posting.country`, which the screening
pipeline already computes. A constant answer to either is wrong for one of those
two rows every time.

`REMOTE` follows India because a globally-remote role is worked from home on an
Indian contract — no permit is involved.

There is a third form of the question, and it is the trap:
`"...authorised to work in the US without sponsorship?"` contains the word
*sponsorship* and its polarity follows **authorisation**. Reading it as the
sponsorship question submits the opposite of the truth.

## An unrecognised question stops the application

`FieldKind.UNKNOWN` is the most important value in the enum. A label the
classifier cannot place is never guessed at, never filled with something
plausible, and never left blank in the hope that it was optional. A *required*
unknown field blocks the run and names the question.

That is also the feedback loop: `applications --status=NEEDS_HUMAN` prints every
question that stopped a run, and each line is a candidate entry for
`extra-answers`. The profile gets better by being used.

`OptionMatcher` refuses the same way. On a two-option yes/no field the closest
wrong option is the opposite answer, so "closest" is the most dangerous fallback
available.

## Three bugs found by looking at the output

**"Race / Ethnicity" classified as CITY.** "ethnicity" contains "city". This is
the `distributed` bug from milestone 3, one milestone later and in a new file, and
it would have typed the applicant's home city into a US EEO dropdown. Every short token in the
classifier now matches on word boundaries. The rule: *a matcher token that is also
a fragment of common English needs a boundary, and the short ones always are.*

**A default that competes is not a default.** The general-purpose summary was
tagged `backend` — a word in nearly every title this tool surfaces — so it scored
3 on the title every time and the four specialist summaries could never win. The
fallback now carries no tags and wins only at zero.

**Padding that loses a cascade looks like padding that is absorbed.** The skills
column had no gap between label and values. `box-sizing: border-box` on a
shrink-to-fit cell was a plausible cause and was wrong: `table.skills td` is
(0,1,2) on specificity and a bare `.sk-items` is (0,1,0), so the rule never
applied at all.

All three were invisible to the test suite and visible in the first rendered PDF.

## Verified against the real corpus

Three postings from the live database, same resume data, three different documents,
one page each:

| Posting | Summary chosen | Led with |
|---|---|---|
| Celonis — Associate Software Engineer, Java | `java` | Distributed Job Scheduling |
| N26 — Backend Engineer, Lending | `java` | Distributed Job Scheduling |
| Supabase — Postgres Engineer (remote) | `distributed` | Distributed Job Scheduling |

## Still open

- `postal-code` is blank in the profile. Deliberate: the locality spans several
  PIN codes and a guess is a wrong answer on a form. The first posting that requires
  it will stop and say so.
- No board has been submitted to through this path yet. The fill step is exercised
  against real pages; `Submitter` is not.
- Workday needs an account per employer tenant. The persistent browser profile
  makes one manual sign-in last, but the first one is manual.
