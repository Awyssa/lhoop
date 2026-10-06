# Roadmap

Status on 2026-10-06, after the third night. Tick items as they land.

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
4. **Count the other sleeps.** A sleep in the afternoon earns nothing today, so the sleep need the
   night after is too high. Fit WHOOP's nap credit from the history (22 nights have one), then add it.
5. **The need after a hard day,** and which HRV figure the scores should use (whole night, or the last
   hours).
6. **A second look at the night rule** once there are odd nights to test it on: a split night, a very
   late riser, a change of time zone.

## Then: the app

4. **A first real screen.** Done. Since 2026-10-06 it shows the strap's own nights, with the app's own
   recovery at the top and the core's figures in a card of their own. Next: the owner's reaction to it.
5. **The app's own scoring.** First version done on 2026-10-03: sleep need with carried debt, a sleep
   score from hours against need, efficiency and consistency, and recovery from HRV and resting heart
   rate against the last 8 nights. On the WHOOP history it is within 7.8 points of WHOOP's recovery.
   It now leads the screen, labelled experimental, and runs on the strap's own nights. The first
   recovery needs three earlier nights. Still to add: need after a hard day, and credit for other sleeps.
6. **A night-replay harness** on the Mac. Started: `StrapSleepBackupCheckTest` replays the app's sleep
   finding over a backup. Still to add: the scores, and a reference printed beside them
   ([06-validation-plan.md](06-validation-plan.md)).
7. **Better inputs:** HRV window selection, and whether the resting heart rate should be the mean over
   the sleep instead of the lowest five minutes.
8. **Settings the app needs:** background connection, continuous HRV capture, the body profile. Today
   they are frozen at what the original app saved.
9. **Start by itself after a phone restart.** Upstream needs the app opened once; this one need not.
10. **Thin `analytics/`** to what sleep and recovery use, once the app's own scoring has replaced it.

## Housekeeping

- [ ] Keep the APK of every build that goes on the phone, named by date, outside the repository. The
      tags that marked those builds went with the old history. Whether to tag builds again is the
      owner's call: he makes the commits.
- [ ] Decide where the signing key lives. `android/fork-debug.keystore` is on the owner's Mac only; a
      build without it is signed with another key and cannot be installed over the app.
- [ ] Move the phone to the renamed app. The Import backup button is built and the restore was
      rehearsed in the emulator with a real backup; LHOOP is installed on the phone beside the old app.
      Left to do: the move itself, in [08-runbook.md](08-runbook.md). Until then the old app records.
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
