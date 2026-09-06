# Milestone 8 — scheduling, Docker, README

**Goal:** make it a thing that runs by itself, and a thing a recruiter can read.
**Result:** 189 tests, a runnable jar, a Dockerfile, a launchd job, and a README
that leads with the problem rather than the stack.

## What was built

| Path | Notes |
|---|---|
| `schedule/DailyRun` | `@Scheduled(cron, zone = "Asia/Kolkata")`, off by default |
| `cli/ServeCommand` | `serve` — keeps the process alive, states the caveat |
| `Dockerfile` | Multi-stage, non-root, `/data` volume |
| `deploy/*.plist` | launchd job for macOS |
| `README.md` | Problem, diagram, stack, setup, sample digest, what I learned |

## The scheduling caveat, stated where it matters

`@Scheduled` fires only while the process is running. Spring's scheduler has no
memory of missed runs — if the machine is asleep or off at 07:00, nothing happens
then and nothing catches up afterwards.

On a laptop that is the normal case, not the exception. A scheduler that silently
does nothing is worse than no scheduler, so `serve` prints the caveat every time
it starts rather than leaving it in the documentation:

```
job-radar is running.

  schedule    0 0 7 * * * (Asia/Kolkata)
  digests     ./digests
  sheets      configured

This only fires while this process is alive. If the machine is
asleep or off at that time, the run is missed and is not caught
up - see the README for launchd, which reruns a missed job on
wake, or run it in Docker somewhere that stays on.
```

**launchd is the better answer on macOS**, and the plist ships in `deploy/`.
launchd reruns a missed `StartCalendarInterval` job once the machine wakes; a
long-running JVM cannot, because it was not running. `run` is a plain command
precisely so any external scheduler can invoke it.

Scheduling is off unless `JOB_RADAR_SCHEDULE=true`, and only `serve` keeps the
process alive — every other command does its work and exits, so a scheduler bean
in those would never fire anyway.

## Docker

Multi-stage: Maven image builds, JRE image runs. Dependencies are resolved in
their own layer so a source-only change does not re-download them. Runs as a
non-root user. The database, digests and captured responses all live under
`/data`, which is the volume, because those are the three things worth keeping
across restarts.

The image defaults to `serve` with scheduling on, since the only good reason to
containerise this is to run it somewhere that stays on. Override the command with
`run` for a one-shot invocation from an external scheduler.

## Two more discoveries from the probe run

Running `probe` across 36 new candidates found 13 live boards, and two of them
taught the same lesson twice.

**`bird` on Greenhouse is Bird the e-scooter company** in New Jersey — not Bird
(formerly MessageBird), the Amsterdam CPaaS on the IND recognised-sponsor
register. A probe finds a live board; it does not check whose. The board was
removed and the trap recorded in `BoardTokenSeeder` next to the existing one,
where the Ashby token `navi` belongs to a San Francisco aviation startup rather
than the Indian fintech.

**Catawiki's "Junior Business Developer" arrived as a software candidate**,
because `developer` is an include token. `business developer`,
`business development`, `account executive` and friends are now excluded.

Both are the same shape as every filter bug in this project: invisible in tests,
obvious in output.

## The README

Written for the audience that actually reads it. It leads with the problem, and
specifically with the three ways a promising posting wastes time —
country-locked remote, a graduate title with five years behind it, an "Associate"
ladder that is not engineering. Then a diagram, the stack, setup, a real digest,
design notes, and what went wrong.

The "what I learned" section is the honest one: every serious bug in this project
was silent, and every one was found by looking at the database or the output
rather than at a passing test suite.

## Verified

- `mvn test` — 189/189
- `java -jar target/job-radar-1.0.0.jar` — prints usage, exits
- `serve` with scheduling off — explains how to turn it on
- `serve` with scheduling on and a five-second cron — **the scheduled run fires**
- Jar is self-contained: 68 MB, no Maven needed at runtime

## Where it stands

All eight milestones done. 98 boards across five ATSs, 8,954 postings, 87
candidates, a digest a human can scan in under a minute, and a tracker it will
not overwrite.
