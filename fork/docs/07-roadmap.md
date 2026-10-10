# Roadmap

Status on 2026-10-09, after the sixth night and three days on the redrawn screens. A third LHOOP build
was made that day and is waiting to go on the phone. Tick items as they land.

## Done

- [x] Understand the original app: what it is, how it works, whether to trust it
      ([09-background-and-risks.md](09-background-and-risks.md)).
- [x] Build from source and install on the Pixel 9a (build 550).
- [x] Pair the WHOOP 5.0 fully ([03-whoop5-status.md](03-whoop5-status.md)).
- [x] Audit the sleep and recovery engine ([04-sleep-recovery-engine.md](04-sleep-recovery-engine.md)).
- [x] Collect and analyse the WHOOP history: 240 nights
      ([05-whoop-scoring-model.md](05-whoop-scoring-model.md)).
- [x] Remove the Apple code.
- [x] Cut the app down to the core, add the stand-ins, the driver, a status screen and the manifest
      overlay ([10-app-structure.md](10-app-structure.md)).
- [x] Install the cut build on the phone and check the connection, bond, sync and export on the strap.
- [x] Record a first full night and check it: 15.2 hours, every second present, the app up throughout.
- [x] A stopgap gate that drops sessions the strap did not call sleep, and a late-sleep HRV readout.
- [x] Tools for the morning routine: `fork/tools/pull_night.sh`, `night_report.py`, `phone_ui.py`.
- [x] Build each night from the strap's own sleep state, with the app's own store, and point the
      screen and the WHOOP-style scores at it. On the phone since 2026-10-06.
      The gate is gone. See [04-sleep-recovery-engine.md](04-sleep-recovery-engine.md).
- [x] Settle the R-R units on this firmware: milliseconds
      ([03-whoop5-status.md](03-whoop5-status.md)).
- [x] Decide what the strap's "up" state is worth: it counts as sleep, bar the strap's wake
      confirmation. On the phone since the second build of 2026-10-06.
- [x] First comparison with the Garmin's sleep page, and the owner's account of the first two nights.
- [x] Count the other sleeps. Fitted from the 22 nights in the history that follow a nap: a nap's time
      asleep comes off the need of the night after it, and the nap counts as time asleep when days are
      compared for consistency. Built on 2026-10-06 and on the phone since that night
      ([05-whoop-scoring-model.md](05-whoop-scoring-model.md)).

## Next: check the new nights against the references

The app's sleeps agree to the second with an independent calculation over the same data, for three
nights. That shows the code does what the rules say. Against the Garmin, the one ordinary night
compared so far agrees closely. On the one odd day, where the Garmin and the strap disagreed, the
owner's account sided with the strap ([04-sleep-recovery-engine.md](04-sleep-recovery-engine.md)).
That is a good start, not a result.

1. **Each morning:** `fork/tools/pull_night.sh` with the phone unlocked, then
   `fork/tools/night_report.py`, then last night's sleep page in Garmin Connect captured with
   `fork/tools/capture_garmin.sh`. Compare the app's screen with the report and the Garmin: start, end,
   time asleep, awake time, HRV, resting heart rate.
2. **Keep checking the "up" state.** It counts as sleep now. Watch for mornings spent awake in bed and
   nearly still, which would read as sleep, and for how long the short spells really are awake (about
   eight minutes each, by the Garmin, on two spells).
3. **Keep asking on odd days.** Where the Garmin and the strap disagree, the owner's account is the
   reference. One such evening so far, and the strap was right.
4. **Watch the first real nap day under the new rule.** It was fitted on WHOOP's naps. The app decides
   for itself what is a nap, and measures a nap's time asleep where WHOOP's export gave only its
   length.
5. **The need after a hard day,** and which HRV figure the scores should use (whole night, or the last
   hours).
6. **A second look at the night rule** once there are odd nights to test it on: a split night, a very
   late riser, a change of time zone.

## Then: the app

4. **The screens.** A first long screen of rows went on the phone on 2026-10-06. The owner found it
   hard to read, chose three drafts the same evening, and they were built: Today, the night and Trends,
   in one dark look ([10-app-structure.md](10-app-structure.md)). On the phone since 23:43 that night.
   Built on 2026-10-09 and **not yet on the phone**: a home-screen widget with the morning's numbers,
   the app's own icon, and the sleep card saying the need once. None of it has been seen on a screen.
   Next: put that build on the phone with the checks in [08-runbook.md](08-runbook.md), and his
   reaction to all of it on real nights.
5. **The app's own scoring.** First version done on 2026-10-03: sleep need with carried debt, a sleep
   score from hours against need, efficiency and consistency, and recovery from HRV and resting heart
   rate against the last 8 nights. On the WHOOP history it is within 7.8 points of WHOOP's recovery.
   It now leads the screen, labelled experimental, and runs on the strap's own nights. The first
   recovery needs three earlier nights. Other sleeps count since 2026-10-06. Still to add: need after
   a hard day.
6. **A night-replay harness** on the Mac. Started: `StrapSleepBackupCheckTest` replays the app's sleep
   finding over a backup and, since 2026-10-06, scores the nights as the screen does. Still to add: a
   reference printed beside them ([06-validation-plan.md](06-validation-plan.md)).
7. **Better inputs:** HRV window selection, and whether the resting heart rate should be the mean over
   the sleep instead of the lowest five minutes.
   **Make REM believable.** The owner wants deep and REM each morning. The split shown is the core's
   stager over the strap's night, and its REM reads about double. Why is known since 2026-10-09: most
   of the REM comes from a term that rises with time of night, not from evidence, and deep is pinned
   near the same share of every sleep ([04-sleep-recovery-engine.md](04-sleep-recovery-engine.md),
   "Why the REM reads high"). No fix was made: one comparable reference night cannot tell a good
   change from a lucky one. Next: capture the Garmin's Stages view each morning until there are about
   fourteen nights, then choose a change on half and judge it on the rest
   ([06-validation-plan.md](06-validation-plan.md), "What a stage fix needs"). The tools are there:
   `fork/tools/stage_whatif.py` tries a change, and the replay test prints the app's split beside the
   Garmin's.
   **Progress over longer spans.** Trends compares the last week with the four before it by plain
   means. It says nothing until there are four nights in the week and seven before it; months of
   nights would allow more.
8. **Settings the app needs:** background connection, continuous HRV capture, the body profile. Today
   they are frozen at what the original app saved.
9. **Start by itself after a phone restart, and bring the strap service back whenever the app is
   opened.** Built on 2026-10-09 (`fork/app/StrapStartup.kt`, `BootReceiver.kt`), not yet on the phone
   and not yet tried on the strap: the checks are in [08-runbook.md](08-runbook.md). Still open after
   that: nothing runs after an app update until the app is opened, by decision
   ([02-decisions.md](02-decisions.md)), and a restart that nobody unlocks records nothing until
   someone does.
10. **Thin `analytics/`** to what sleep and recovery use, once the app's own scoring has replaced it.
11. **Name the hours off the wrist.** Done on 2026-10-09. The strap logs when it comes off and goes
    back on, and stores nothing in between ([03-whoop5-status.md](03-whoop5-status.md)).
    `night_report.py` lists those hours as that and keeps them out of its completeness figure. The app
    draws them on the night's 24-hour bar and says them in the pill, on the home screen, on Trends and
    on the Strap tab (`fork/app/OffWrist.kt`); that part is in the build not yet on the phone.
12. **Say how young the baseline is.** Done on 2026-10-09, in the same build: under the recovery line
    the home screen says how many nights the score is based on until there are eight.
13. **Lighter backups in the morning routine.** The tools could read a `.lhoopbak` without unpacking
    it, which would save four fifths of what the routine keeps on the Mac
    ([11-old-raw-data.md](11-old-raw-data.md), option B). Not built.

## Housekeeping

- [ ] Keep the APK of every build that goes on the phone, named by date, outside the repository. The
      tags that marked those builds went with the old history. Whether to tag builds again is the
      owner's call: he makes the commits.
- [ ] Decide where the signing key lives. `android/fork-debug.keystore` is on the owner's Mac only; a
      build without it is signed with another key and cannot be installed over the app.
- [x] Move the phone to the renamed app. Done on 2026-10-06 through a backup: the old app exported,
      LHOOP imported, then connected to the strap. Nothing was lost. See [08-runbook.md](08-runbook.md).
- [x] Remove the old app. The owner uninstalled it on 2026-10-06.
- [x] LHOOP's battery usage set to Unrestricted, by the owner on 2026-10-06.
- [x] After the move: a first full night recorded under LHOOP. Three by 2026-10-09, each whole.
- [ ] **Decide what happens to old one-second rows.** Written up with measurements, six options and
      a recommendation in [11-old-raw-data.md](11-old-raw-data.md): six tables have no cap and grow by
      28 MB for every day worn, about 10 GB a year, and each export grows by 7 MB a day. The phone has
      room for years; the export is what gets unwieldy first. Recommended: nothing deleted now, and a
      by-hand trim of rows older than 90 days, only after an export, once an export passes 500 MB
      (the second week of December at today's rate). Trimming writes to the core's database, so it is
      the owner's call.
- [ ] Clear out old exports. Every export stays in the phone's Downloads, and every pull is kept on the
      Mac both zipped and unpacked. The owner deletes them; keep the newest and the one from before
      the last install.
- [ ] Watch upstream for Bluetooth fixes worth applying by hand. They can no longer be merged.
- [ ] Choose the Garmin data route ([06-validation-plan.md](06-validation-plan.md)). For now:
      `fork/tools/capture_garmin.sh`, which reads the sleep page off the phone's screen.
- [ ] Remove non-code clutter: demo videos, upstream release notes, design notes, maintainer scripts,
      and upstream docs for removed features.
- [ ] Remove dependencies nothing uses now (OkHttp, Glance, the Health Connect client).
- [ ] Find out why two `RecoveryDriversTest` cases fail on this Mac.
- [ ] Seed more shapes of night in the demo flavor as the screen grows (`fork/app/DemoStrapNights.kt`).
- [x] Give the app its own name and package: LHOOP, `com.lhoop`, on 2026-10-06.
- [ ] Give it its own icon. The launcher icon is still the original app's picture.
