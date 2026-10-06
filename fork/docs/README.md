# Project notes

What this project is for, what has been decided, what is known, and what comes next. Written on
2026-10-03 from the first working sessions, so the context survives outside any one conversation.

Rules for the code itself are in [`AGENTS.md`](../../AGENTS.md). These notes are the background.

## Reading order

| File | What it answers |
|---|---|
| [01-vision-and-priorities.md](01-vision-and-priorities.md) | What are we building, and what matters most? |
| [02-decisions.md](02-decisions.md) | What has been decided, why, and what is still open? |
| [03-whoop5-status.md](03-whoop5-status.md) | What does a WHOOP 5.0 give us on Android today? |
| [04-sleep-recovery-engine.md](04-sleep-recovery-engine.md) | How does the core score sleep and recovery, and where is it weak? |
| [05-whoop-scoring-model.md](05-whoop-scoring-model.md) | How did WHOOP itself score sleep and recovery? |
| [06-validation-plan.md](06-validation-plan.md) | How do we check our numbers against a Garmin and other references? |
| [07-roadmap.md](07-roadmap.md) | What is done, and what is next? |
| [08-runbook.md](08-runbook.md) | How to build, install, pair, read logs and pull data. |
| [09-background-and-risks.md](09-background-and-risks.md) | What the original app is, how far to trust it, and what to avoid. |
| [10-app-structure.md](10-app-structure.md) | How is the app put together since the cut to the core? |

## State on 2026-10-06

- **Direction:** this is the owner's own app, LHOOP, built on the Bluetooth, protocol, storage and
  analytics core of the app it began as. Everything else in that app was cut. See [02-decisions.md](02-decisions.md).
- **Renamed on 2026-10-06.** The app is LHOOP in every package, file and identifier. The rename is in
  the working tree, not committed: the owner deleted the GitHub repository that day and will create a
  new one. Nothing is committed or pushed unless the owner asks.
- **Code:** the cut, the stand-ins, the driver, the manifest overlay, two screens (last night, strap
  status), nights built from the strap's own sleep state and the app's own WHOOP-style scores. It
  builds an APK and passes the unit tests. See [10-app-structure.md](10-app-structure.md).
- **Phone:** a Pixel 9a (Android 17), with the WHOOP 5.0 fully paired, still runs the last build made
  before the rename (tag `phone-2026-10-06b`), under the old package name. A build of the renamed code
  is a different app to Android and would start empty beside it. Moving across is not built yet: see
  [08-runbook.md](08-runbook.md).
- **WHOOP history:** 240 nights (2025-08-02 to 2026-06-29) are saved locally and analysed.
- **Nights so far:** three recorded (3, 4 and 5 October), each complete, and all three analysed. The
  core's sleep detector was wrong on the first two and right on the third. Since 2026-10-06 the app
  builds each night from the strap's own sleep state instead, and its sleeps match an independent
  calculation to the second. Against the Garmin's sleep page the third night agrees closely (time
  asleep within 10 minutes, HRV within 1.3 ms, resting heart rate within a beat). Where the Garmin and
  the strap disagreed, about an evening on the second day, the owner's account sided with the strap.
  The owner's account also changed one rule: the strap's "up" state counts as sleep. See
  [04-sleep-recovery-engine.md](04-sleep-recovery-engine.md).

## How certain each statement is

These notes mark findings with one of three labels:

- **Confirmed:** read directly in the code, measured, or seen on the device.
- **Reported:** found by a code audit during the session and not re-checked line by line.
- **Open:** not known yet.

Line numbers drift as upstream changes, so files and function names are the reference.

## Private notes

Treat the repository as public. Personal numbers (typical HRV, sleep times, the WHOOP history itself)
are in `whoop-data/`, which `.gitignore` excludes. `whoop-data/NOTES.md` holds the personal summary.
**The folder must never be uploaded:** a file upload through a web page does not read `.gitignore`.
