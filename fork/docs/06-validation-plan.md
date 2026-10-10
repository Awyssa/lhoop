# Validation plan

The idea: wear the Garmin and the WHOOP together, compare the nights, find the gaps, fix them, repeat.
This page is the agreed shape of that, with the cautions that came out of discussing it.

## What each reference is good for

| Reference | Use it for | Do not use it for |
|---|---|---|
| **Garmin, worn the same night** | When sleep started and ended, time asleep, overnight heart rate, resting heart rate, the HRV trend | Stage-by-stage truth. Garmin's stages are estimates too. |
| **The owner's WHOOP history** | How WHOOP turned HRV, resting heart rate and sleep into its scores; the usual ranges for this wearer | Comparing individual nights: none overlap with this app's. |
| **A chest strap (Polar H10), a few nights** | True HRV and R-R, measured from the heart's electrical signal. | Sleep stages. |
| **A public sleep-lab dataset** | Training and testing a stage model. DREAMT on PhysioNet pairs wrist heart rate, beat intervals, motion and temperature with lab-scored stages; access needs a data-use agreement. | Anything specific to this wearer or this strap. |
| **A 30-second morning log** | Whether the recovery score means anything: how rested (1–5), plus tags such as alcohol, late meal, hard training, sick, stressed | — |

Tuning the app until it matches the Garmin would copy the Garmin's mistakes. It is a second opinion.

Differences are sometimes definitions, not errors. Garmin averages HRV over the whole night. WHOOP
weights it toward the last deep-sleep period. Compare like with like.

## Protocol for a night

1. WHOOP on one wrist, Garmin on the other. Same placement every night.
2. Phone by the bed, on charge, the app left running. It syncs every 15 minutes.
3. In the morning: the log, then `fork/tools/pull_night.sh`, then last night's sleep page in Garmin
   Connect left open on the phone for capture. See [08-runbook.md](08-runbook.md).

## Getting the data to the Mac

- **The app:** a backup (the Export backup button on the Strap tab) is an unencrypted zip of the whole
  database. `fork/tools/pull_night.sh` saves one to Downloads and pulls it over USB.
- **Garmin:** one of three routes, still to be chosen.
  - A script on the Mac that reads Garmin Connect: the richest (stage timeline, 5-minute HRV readings,
    sleep heart rate, breathing). The owner logs in. It uses an unofficial API and can break.
  - Health Connect: Garmin Connect on the phone can write sleep, heart rate and resting heart rate
    there (it was switched off on 2026-10-03). No HRV, and it needs tooling to reach the Mac.
  - Typing the Garmin app's numbers in each morning. Enough for a first week.

## Method

- **Replay, do not re-wear.** The app keeps the raw signals (heart rate, R-R, motion, temperature)
  indefinitely, so every recorded night is a permanent test case. Each change is re-run over all of
  them.
- **Batches, not single nights.** Tune on some nights and check on others.
- **Look for nights that differ.** Agreement on a calm night proves little. Short sleep, a late night,
  alcohol and hard training are where a method shows whether it tracks.
- **Fix plain bugs first.** The three confirmed in
  [04-sleep-recovery-engine.md](04-sleep-recovery-engine.md) need no data.
- **Measure:** the mean difference and its spread against the reference, the correlation across nights,
  and for stages a confusion table, read as agreement rather than accuracy.

## The tools

- **`fork/tools/pull_night.sh`** exports a backup from the app on the phone and pulls it to the Mac.
- **`fork/tools/night_report.py`** reads a backup and prints the recording's completeness, the sleeps
  in the strap's own state with heart rate and HRV worked out independently, the core's sessions
  beside them, and what the core stored.
- **`StrapSleepBackupCheckTest`** (a unit test, skipped where there is no backup) replays the app's own
  SQL and Kotlin over the newest backup on the Mac and prints each night, then the scores the screen
  would show for them, with the other sleeps counted and with them left out. It is the start of the
  replay harness: it prints no reference beside them.

  Since 2026-10-09 it also prints each night's deep, REM and light beside the Garmin's, for every
  night that has a capture of the Garmin's Stages view (`GarminCapture`, test-side).
- **`fork/tools/capture_garmin.sh`** saves the Garmin Connect sleep page off the phone's screen, as
  screenshots and text.
- **`fork/tools/stage_whatif.py`** is a port of the core's stager for trying changes to it. Over a
  backup it prints the split as shipped (which must equal the app's), the REM share with one term of
  the recipe changed at a time, where the REM falls and which terms carried it, and with `--garmin`
  the Garmin's split beside the app's.

Still missing: the Garmin's sleep times in a form a tool can read (they are only in a picture), and a
reference beside the scores in the replay.

## What a stage fix needs

The stager's REM reads high and the reason is known
([04-sleep-recovery-engine.md](04-sleep-recovery-engine.md), "Why the REM reads high"). What is
missing is the means to tell a good fix from a lucky one. On 2026-10-09 there was one comparable
reference night. On 2026-10-10 there were two. The second repeats the first: where the two devices
measure, they agree (the end of the sleep to the minute, time asleep within ten minutes, resting
heart rate the same, HRV within a millisecond), and the app's REM is again about one and a half times
the Garmin's, its deep about a third more and its light far less. Two nights still cannot choose
between the candidate changes, which land within a few points of each other on both.

**The level was fixed that day all the same**, against a better target: the wearer's long-run REM
share over 240 nights of WHOOP history ([04-sleep-recovery-engine.md](04-sleep-recovery-engine.md),
"The fix of 2026-10-10"). What follows is still what it takes to show the app follows a night, and to
judge any further change. Every Garmin capture from 11 October on is a night the fix was not set on.

- **More nights with the Garmin's Stages view captured:** about fourteen, short and long ones among
  them, and not all from the same week. Each morning, with last night's sleep page open in Garmin
  Connect, `fork/tools/capture_garmin.sh` saves it; the Stages view is the part that counts. A night
  missed can still be captured later: step back to it on the page and run the script with a name.
- **Split them before looking.** Choose the change on half of the nights and judge it on the other
  half, which must not have been looked at while choosing. The odd-numbered dates and the even ones
  will do.
- **Judge by more than the total.** REM minutes against the Garmin's on the held-out nights, the
  spread of that difference, and whether nights with more REM by the Garmin get more from the app:
  a change that only shrinks every night by the same share has fixed the average and nothing else.
- **Remember what the reference is.** The Garmin's stages are estimates. Agreeing with it is not being
  right; disagreeing by a factor of two on every night is still worth fixing. A lab-scored dataset
  (the table above) is what could say more, for a wrist in general and not for this one.
- **Deep needs the same.** It comes out near the same share of every sleep by the recipe's
  construction, so its agreement so far proves little.

## Expectations

- Heart rate, HRV, resting heart rate, sleep timing and duration can be made very good. They are
  measurements.
- Stages will reach consumer-wearable quality at best.
- Recovery needs about two weeks of nights before it can be judged: the baselines need them.
