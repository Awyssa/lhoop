# The core's sleep and recovery engine

How the Android app scores a night today, where it is weak, and where the improvements are. The code
is under `android/app/src/main/java/com/lhoop/analytics/`.

The core's names: recovery is **Charge**, the sleep score is **Rest**, strain is **Effort**.

## Summary

For a paired WHOOP 5.0 the raw inputs exist. The weak part is the analysis, and the weakest piece is
sleep staging. That is the good kind of problem: it is software in this repository.

## How each number is computed

| Output | Method | Status on a 5.0 |
|---|---|---|
| Sleep detection | Stillness from gravity, confirmed by heart rate. When gravity is sparse, heart rate bridges the gaps. With no gravity at all, sessions are built from heart rate alone (`SleepStager`). | Works, rough. Heart-rate-only bounds are weaker: a quiet evening can read as sleep. |
| Stages | `SleepStagerV2`: per-night scaled heart rate, heart-rate spread and flatness, gravity jerk, breathing regularity from R-R, a sleep-cycle prior, and a 4-state smoother. The coefficients were set by hand, not fitted. | Weakest part. See the accuracy section. |
| Rest (sleep score) | 0.50 × hours ÷ need + 0.20 × efficiency + 0.20 × deep+REM share (target 50%) + 0.10 × consistency (`RestScorer`). | Shown for every night. Probably inflated: it inherits the stage errors and the bug below. |
| Sleep need | 75th percentile of nightly hours once there are 7 nights; never below 8 h, never above 9.5 h. | Only reaches part of the app. See the bug below. |
| Overnight HRV | Mean of 5-minute RMSSD windows over the whole night. Each window needs 20 clean beats. | Works. One good window is enough to set the night's value. |
| Resting heart rate | Lowest 5-minute average in the longest session. | Works. Reads 5–8 bpm below WHOOP's by design. |
| Respiratory rate | Estimated from the breathing rhythm in R-R intervals, clamped to 8–25. | Estimate only; the 5.0 has no respiration channel. |
| Charge (recovery) | Robust z-scores against personal baselines, through a logistic curve (`RecoveryScorer`). Weights: HRV 0.55, resting HR 0.20, Rest 0.15, respiratory rate 0.05, skin temperature 0.05. | Shows "Calibrating" until 4 nights have HRV; trusted at 14. |

Baselines are recency-weighted averages. A baseline with no value for 14 nights goes stale, and Charge
goes blank again.

## Bugs confirmed in the code

1. **The stored Rest ignores your personal sleep need and consistency.** `RestScorer.restFromDaily`
   passes neither, so it falls back to a fixed 8 hours and a neutral 0.5 consistency. It feeds the
   stored `sleep_performance` series and the Rest term inside Charge (`IntelligenceEngine`). The
   personalised need is computed, but only the per-day analysis receives it.
2. **Missing motion counts as lying perfectly still.** In `SleepStagerV2`, an epoch with no gravity
   samples gets zero movement and zero jerk, so `motionQuiescent` is true. A quiescent epoch discards
   heart-rate evidence of being awake. On a 5.0, where most seconds have no gravity, wake is under-called,
   efficiency approaches 100%, and Rest inflates.
3. **The strap's own state is read through a cap that a strap worn round the clock overruns.**
   `IntelligenceEngine` reads `sleepStateSample` for a day from 30 hours before its midnight, oldest
   first, through `WhoopRepository.sleepStateSamples` with its default limit of 100,000 rows. The
   strap writes one row a second, so the read stops 27.8 hours of wear after it starts, which is the
   evening before the night being scored. The state the core then stores on a sleep session
   (`sleepStateJSON`) is missing, or correct up to that point and repeated from there on. Heart rate,
   beats and gravity are read through `StreamReadCap`, which is sized for the window; this one read
   was left on the default. Measured on the phone: see the second and third nights below. The raw
   table is complete, and the app reads that.

## Weaknesses reported by the code audit (not yet re-checked)

- The strap's own awake/still/asleep state is stored and almost unused: only a morning guard reads it,
  and the wake veto built on it is switched off.
- A single 5-minute window can set the night's HRV. There is no minimum number of windows, and no
  preference for the stillest ones.
- A resting-heart-rate measure that matched WHOOP much more closely (the mean over the primary session,
  stored as `rhr_primary_session`) is computed and not used.
- A confidence tier for Rest is computed and never shown. Heart-rate-only nights are never flagged.
- See also the R-R and heart-rate weak points in [03-whoop5-status.md](03-whoop5-status.md).

## How accurate it is today

All of this is upstream's own evidence, and it is thin.

- **Stages against a sleep lab:** agreement is only fair (kappa 0.37) on a public dataset of 31 people
  wearing an Apple Watch. Deep sleep is the weakest stage, and only about a third of wake is caught.
  That dataset has no R-R intervals, so the breathing term was never tested.
- **On a WHOOP 5.0:** against the strap's own sleep/wake state, agreement inside detected sessions was
  poor (kappa 0.12; one wearer, 21 nights).
- **Charge against WHOOP recovery:** it moved in the same direction (r 0.82) and was about 20 points
  off on average (one wearer, 8 nights).
- **HRV against a Garmin:** mean error about 9 ms, r 0.74.

## Where to improve, in order of value

1. **Wake detection on nights with little motion.** Treat an epoch as still only when motion was
   actually observed. Use the strap's own sleep state as an input. Show the confidence tier.
2. **Rest uses the personal need and consistency.** A small, well-defined fix.
3. **A WHOOP-calibrated scoring mode** for Rest and Charge, from
   [05-whoop-scoring-model.md](05-whoop-scoring-model.md). WHOOP's sleep score does not use stages at
   all, which sidesteps the weakest part of the core.
4. **HRV window quality.** Prefer the stillest windows, take a median, require a minimum count.
5. **Resting heart rate.** Offer the primary-session mean, after checking it on real nights.
6. **A fitted stage model** to replace the hand-set coefficients. The biggest job, and the one that needs
   real reference data.

`analytics/` is kept as the original app wrote it (apart from the rename) and is not edited. The
improvements above are built as the app's own scoring under `fork/app/`, run beside the core's on the same
nights, and take over once real nights show they are better.

## The first night on the strap (2026-10-04)

One night, so these are observations, not conclusions. The numbers themselves are private.

- **Sleep detection found the main sleep.** Its start was within a few minutes of where the strap went
  still, and its end within a minute of where the wearer got up. For the last two hours the strap was
  in its "up" state (see "What the strap's four states mean"); the core counted them as sleep, and the
  owner later confirmed they were.
- **It also counted an hour of stillness before bed as a second sleep.** The strap's own state for that
  hour was awake or still, never asleep, and heart rate was clearly higher than in the main sleep. The
  hour went into the night's time asleep. The wearer confirmed they went to bed afterwards, so it was
  not sleep. This is the "quiet evening reads as sleep" weakness.
- **A stopgap gate left such a session out** from 4 to 6 October: a session counted only if the strap
  called at least half of it asleep. It could only remove whole sessions, and it relied on the state
  the core stores on a session. It is gone; see "How the app builds a night" below.
- **Every night is treated as having sparse motion.** `SleepStager.isGravitySparse` looks for the
  largest gap in motion data across the whole analysis window. A strap worn only at night always has a
  day-long gap, so the stager always falls back to bridging gaps with heart rate, which is the weaker
  path. Upstream assumes the strap is worn around the clock.
- **The stage split was not believable:** far more REM and far less light sleep than this wearer's
  WHOOP history ever showed. Expected; stages are the weakest output.
- **Overnight HRV matches the raw beats.** An independent RMSSD over five-minute windows of the stored
  R-R intervals came within a millisecond of the core's figure. HRV rose about threefold from the first
  hour of sleep to the last, so a whole-night mean reads lower than WHOOP's figure would, which is
  weighted to late deep sleep. The app also shows the mean over the last three hours of sleep as a
  readout. It feeds no score and has no reference yet.
- **Resting heart rate** was the lowest five-minute mean, about ten beats below the mean over the sleep.

## The second night (2026-10-05)

The owner had slept twice: in the late afternoon and evening, then a short night some hours later. The
strap's own state shows exactly those two sleeps.

- **The core merged the first sleep with the still evening after it into one nine-hour session,** and
  made that the day's sleep. The strap called 35% of it asleep.
- **The real night became a second session,** left out of the day's total, and with no strap state
  stored on it. That was bug 3 above: the capped read had stopped before that night began.
- **The gate dropped the whole nine-hour session,** the real afternoon sleep included. It kept or
  dropped a session whole, so it could not rescue the true part of a session that was half right. Its
  note on the screen was misleading, and the stages and the core's sleep score shown beside it described a
  different session from the bed times.
- **Resting heart rate agreed with the Garmin** within one beat (lowest five minutes on the strap
  against Garmin's daily figure).
- **The Garmin made the same merge as the core.** Its sleep page for the day, captured on 6 October,
  shows one sleep from the afternoon to past midnight with two hours awake in it, and not the real
  night at all. Garmin and the core both read the evening as light sleep. The strap's own state has
  the wearer still on and off through that evening and never asleep, and the wrist moved about five
  times as much as in any sleep recorded so far. **The owner confirmed they were awake.** The strap's
  state was right, and the two detectors that work from stillness and heart rate were both wrong in
  the same way.
- **Over the Garmin's own window the two devices measure the same heart:** mean heart rate within
  about a beat, HRV within half a millisecond.

## The third night (2026-10-06)

The strap was worn round the clock for the first time. One long sleep.

- **The core found it well:** one session, starting when the strap went still and ending four minutes
  before the strap's last asleep second. Its time asleep was within five minutes of the strap's.
- **Its HRV and resting heart rate match the raw data.** The core's own arithmetic run over the
  strap's bounds gives the same resting heart rate and an HRV within half a millisecond. An
  independent calculation in `fork/tools/night_report.py` agrees within a millisecond and a beat.
- **R-R intervals are in milliseconds,** settled with the three nights together: see
  [03-whoop5-status.md](03-whoop5-status.md). The beats stored cover 83 to 98% of the expected count
  in each sleeping hour.
- **The stage split was again not believable:** REM came out at about twice the share this wearer's
  WHOOP history shows.
- **The state stored on the session was wrong from early in the night** (bug 3): real up to where the
  capped read stopped, then "asleep" repeated to the end. A spell the strap marked as "up" later in the
  night is absent from it.
- **The sparse-motion path was still taken** (`stagingSparse`), although the strap had been on all
  day. Within the analysis window there was still a gap of an hour in the afternoon.
- **The WHOOP-style sleep need was too high.** It carried debt from the night before, which the app
  had counted as the short night alone. The afternoon sleep before it earned no credit. Other sleeps
  count since later that day: see rule 7 below.

**Against the Garmin's sleep page for this night** (captured the same afternoon), the app's night
from the strap's state, as first built (only state 2 counted as asleep):

| | Difference from the Garmin |
|---|---|
| Sleep start | The strap went still 7 minutes before the Garmin's start and called it sleep 5 minutes after |
| Sleep end | 1 minute |
| Time asleep | 9 minutes less |
| Awake during the sleep | 4 minutes more; both put the one long spell in the same place |
| HRV | 1.3 ms lower |
| Resting heart rate | 1 beat lower |
| Mean heart rate asleep | The same |

Under the present rule, which counts the strap's "up" state as sleep, the one spell in the night moves
from awake to asleep: time asleep is then ten minutes more than the Garmin's instead of nine less.

The Garmin's stage split is far from the core's: three fifths of the core's REM and nearly twice its
light sleep. One night, one wearer, and the Garmin is a second opinion, not the truth.

## What the strap's four states mean

Upstream labels them 0 wake, 1 still, 2 sleep, 3 up, and says the meanings of the non-zero codes are
inferred, not validated. On this strap, over three nights and one afternoon sleep:

- **1, still,** comes first. It lasted 10 to 20 minutes before each sleep. It also appears on quiet
  evenings that never become sleep, and the owner confirmed one such evening was spent awake.
- **2, asleep,** follows a run of 1 and nothing else.
- **3, up,** starts with a burst of movement during a sleep, and is the strap not yet knowing. Six
  runs so far. Three went back to 2 after 10 to 12 minutes with no movement at all. Three ended in 0
  after about 10.5 minutes of sustained movement with a clearly raised heart rate: three of the four
  wakings took exactly that long, and one three minutes more.
- One run of 3, at the end of the first night, went on far longer: a sleeping heart rate, occasional
  movement, and no settling either way until the wearer got up. **The owner confirmed they were asleep
  through it.**

What the references say about state 3:

| Run | Reference | What it was |
|---|---|---|
| The long run at the end of the first night | The owner | Asleep until about ten minutes before the strap said awake |
| Two runs in the middle of a sleep | The Garmin | Awake for about the first eight minutes, light sleep for the rest |
| Three runs that ended in awake | The Garmin for two, heart rate and movement for all | Awake |

So state 3 is mostly sleep, and where it ends in awake, its last ten and a half minutes are the wearer
getting up. The history of this rule is short and worth keeping: the stopgap gate counted all of state
3 as sleep, which swallowed the getting-up; the first version of the new rules counted none of it,
which lost two real hours on the first night. The rule now is the one in the next section.

What it still gets wrong, by the same evidence: a brief waking that starts a run of state 3 is counted
as sleep (about eight minutes each, by the Garmin). And nothing here can see someone lying awake and
nearly still after waking: that reads as sleep until they move.

## How the app builds a night (since 2026-10-06)

The app no longer takes sleep from the core. It reads the strap's state itself. The code is under
`android/app/src/main/java/fork/app/`: `scoring/StrapSleep.kt`, `scoring/SleepDays.kt`,
`scoring/SleepVitalsCalc.kt`, `scoring/SleepLog.kt`, `StrapSleepLoader.kt` and `SleepStore.kt`.

1. **The state is read from the raw `sleepStateSample` table,** one row per minute through an SQL
   aggregate: seconds awake, still, asleep and up, and the first and last of some of them. The copy
   the core stores on a session is not used (bug 3).
2. **A stretch** is an unbroken run of asleep-or-up seconds, starting at its first asleep second. A
   break of up to 5 minutes (missing data) does not end it. A stretch with under 20 minutes in state 2
   is ignored.
3. **Where a stretch ends.** If it finishes in state 2, at the last asleep second. If it finishes in a
   run of "up" that the strap then turned into awake, 10.5 minutes before the end of that run: the
   time the strap takes to confirm a waking. If the data stops while the strap still has the wearer
   "up" (the strap came off, or the night is not over), at the last asleep second, and the screen says
   the night may not be complete.
4. **Asleep** is every second of state 2 or 3 from the start of a stretch to its end. The state 3 part
   is shown as "restless". Efficiency is asleep over time in bed.
5. **A sleep** is a stretch, or several with less than 90 minutes between them. The time between them
   is awake.
6. **Time in bed** starts where the unbroken run of still minutes leading into the sleep began, at
   most an hour earlier, and ends where the sleep does.
7. **The night for a day** is the sleep whose time in bed overlaps that day's night window most. The
   window runs from 21:00 the evening before to noon. A sleep wholly between noon and 21:00 is never a
   night. **Every other sleep is listed** with the next night that follows it within a day, and
   counts for that night: its time asleep comes off what the night needed, and it is time asleep when
   the day is compared with earlier ones for consistency. Both come from how WHOOP treated naps
   ([05-whoop-scoring-model.md](05-whoop-scoring-model.md)).
8. **HRV** is the mean of five-minute RMSSD windows over the stretches, and **resting heart rate** the
   lowest five-minute mean, both by the core's own functions (`SleepStager.sessionHrvWindows`,
   `sessionHrvOverCounted`, `sessionRestingHR`) called on the strap's bounds. HRV needs six usable
   windows. The mean over the last three hours is kept beside it.
9. **Deep and REM** are the core's default stager (`SleepStagerV2.stageSession`) run once over the
   sleep, read only inside the stretches the strap counted as sleep. Where the stager says "wake"
   there, the time is light sleep: asleep or awake stays the strap's call. The shares found are
   applied to the time asleep, so deep, REM and light add up to it. **These are estimates**, shown as
   such and used in no score. On the first three nights and one afternoon sleep, deep came to a share
   of sleep close to the wearer's WHOOP history's, and within about half an hour of a second device
   on the one night compared. REM came to about twice the history's share, and about an hour and a
   half more than the second device. Making REM believable is on the roadmap.
10. **Each sleep is stored** in a JSON file of the app's own, never in the core's database, with the
   UTC offset it was slept in. A sleep is worked out again on every load until the strap's data runs
   three hours past it and any "up" after it; then the stored record stands. A rules version in the
   file forces everything to be worked out again when the rules change (it is 3).
11. **The screens and the WHOOP-style scores read only these nights.** The core's figures for the same
    day are shown apart, folded away on the night's screen, with the stretch the core detected.

**Checked so far.** The same SQL and Kotlin replayed over the newest backup on the Mac
(`StrapSleepBackupCheckTest`, skipped where no backup exists) give the same sleeps, to the second, as
the independent Python in `fork/tools/night_report.py` for all three nights and the afternoon sleep.
Refreshing step by step, as the phone does, ends with the same sleeps as one read of everything.
Against what the owner reported: the first night's bedtime and waking, the second day's afternoon
sleep, its evening awake, and its night's bedtime and waking all match. Against the Garmin: the third
night agrees closely (see that night above; under the present rule the app's time asleep is ten
minutes more than the Garmin's, where under the first rule it was nine minutes less).

**Not checked.** Any night beyond these three. One wearer, three nights and one disputed evening is
not validation, however well it has gone.

## Tests

The Android unit tests run about 4,300 cases since the cut. Two `RecoveryDriversTest` cases fail on
untouched upstream on this Mac. They round exact half values in recovery driver points, and probably
depend on the Java version. Look into them before changing recovery code.
