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
| [11-old-raw-data.md](11-old-raw-data.md) | How fast does the stored data grow, and what are the options? |
| [12-server-backup.md](12-server-backup.md) | How will the data be backed up to the owner's server? The plan, and how far it is built. |

## State on 2026-10-10

- **The phone is unchanged:** it runs the build of 2026-10-09 and has no network code.
- **A backup to the owner's own server is built and not deployed.** The server is `fork/server/`, in
  Go at the owner's wish; the app's side is `fork/app/backup/`; the design and every test result are
  in [12-server-backup.md](12-server-backup.md). On the Mac a real backup went up and came back
  identical, also with the server in its container. In an emulator the demo app backed up to the
  server, restored from it, and matched it table for table.
- **The working tree is ahead of the phone in one way that matters:** the app now has the `INTERNET`
  permission. It is used for that upload only, which is off until the owner sets it up, and a test
  holds the rest of the code to that. `AGENTS.md` says so.
- **What it waits for:** a domain; one more site in the Caddy the owner's server already runs; then
  his word to install on the phone ([08-runbook.md](08-runbook.md)).
- **Import can now restore a database over 2 GiB**, proven in an emulator. The phone needs that
  before mid-December.

## State on 2026-10-09

- **The phone** runs the build of 2026-10-09 since 14:27 that day. That morning the build of
  2026-10-06 was looked at after three nights: the same process since its install, connected, every
  night whole, and the only hole in the recording was the strap off the wrist, which is not a fault
  ([03-whoop5-status.md](03-whoop5-status.md)).
- **What the new build adds:** the strap service comes back when the app is opened and after a phone
  restart; hours off the wrist are shown; the home screen says how young the recovery baseline is;
  there is a home-screen widget and an icon of the app's own. It was rehearsed in an emulator, then
  installed over the app after a checked backup. On the phone it connected, bonded, synced, exported,
  kept a Disconnect down and came back on Connect, with no hole in the data across the install. The
  owner then placed the widget and restarted the phone: the link came back by itself and nothing was
  lost. Still to come: a first night on it ([08-runbook.md](08-runbook.md)).
- **Why REM reads high is known, and no fix was made**, because one reference night cannot judge one
  ([04-sleep-recovery-engine.md](04-sleep-recovery-engine.md)). What is needed is the Garmin's Stages
  view captured each morning ([06-validation-plan.md](06-validation-plan.md)).
- **Old one-second rows** grow by 28 MB a day worn. The options and a recommendation are in
  [11-old-raw-data.md](11-old-raw-data.md), for the owner to decide.
- **Backups are to go to the owner's own server.** He decided it that evening, which gives the app one
  use of the network. The plan is in [12-server-backup.md](12-server-backup.md). Reading the code for
  it found that the Import button could not restore a database over 2 GiB, which the phone's reaches
  around mid-December. Both were built the next day: see above.
- **Who decided what that day:** the owner asked for the work in one run without being consulted, with
  open choices put to a second assistant. Those are marked in [02-decisions.md](02-decisions.md).

## State on 2026-10-06

- **Direction:** this is the owner's own app, LHOOP, built on the Bluetooth, protocol, storage and
  analytics core of the app it began as. Everything else in that app was cut. See [02-decisions.md](02-decisions.md).
- **Renamed on 2026-10-06, in a new repository.** The app is LHOOP in every package, file and
  identifier. The owner deleted the old GitHub repository that day and started this one, with a fresh
  history on branch `master`. Commit ids and tags from before then no longer exist. The owner makes
  every commit and push himself.
- **Code:** the cut, the stand-ins, the driver, the manifest overlay, nights built from the strap's own
  sleep state, the app's own WHOOP-style scores, and three tabs redrawn on 2026-10-06 (today, trends,
  strap). It builds an APK and passes the unit tests. The redrawn screens, the nap rule and the stage
  split went on the phone late on 2026-10-06. See [10-app-structure.md](10-app-structure.md).
- **Phone:** a Pixel 9a (Android 17), with the WHOOP 5.0 fully paired, runs LHOOP since the evening of
  2026-10-06. To Android the renamed build is a different app, so the data came across through a
  backup: every row, and no gap across the move. Connecting, bonding, syncing, reconnecting after a
  restart and exporting were checked on the strap that evening. LHOOP has not recorded a night of its
  own yet. The app it replaced was uninstalled that evening. See [08-runbook.md](08-runbook.md).
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
