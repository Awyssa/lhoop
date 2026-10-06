# How WHOOP scored sleep and recovery

Reverse-engineered from the owner's own WHOOP history: 240 nights between August 2025 and June 2026,
227 of them with a recovery. This is the calibration target for making the app's scores read the way
WHOOP's did.

The data and all personal values stay in `whoop-data/` (not in git). To reproduce everything here:

```bash
python3 fork/whoop-history/whoop_history.py
```

## The data

It comes from the endpoint WHOOP's own website calls, saved by the owner from a logged-in browser:

```
https://api.prod.whoop.com/core-details-bff/v0/cycles/details?apiVersion=7&id=<user id>&startTime=<ISO>&endTime=<ISO>&limit=<n>
```

The response is `{"records": [...]}`. Each record is one WHOOP "cycle" (roughly a day) with `cycle`,
`sleeps`, `recovery`, `workouts` and `v2_activities`. Durations are milliseconds, and `hrv_rmssd` is in
seconds. Unlike the official CSV export, a recovery includes WHOOP's internal sub-scores: `hrv_component`,
`rhr_component`, `hr_baseline` and `history_size`.

Three things to know before trusting a download:

1. **The first and last day of a download can be incomplete.** A sleep or workout that crosses the
   requested window is dropped. Overlapping downloads fix this.
2. **A day's recovery is not always from that night's sleep.** After a nap, WHOOP re-scores the day's
   recovery from the nap. The nap appears only in `v2_activities` (`type: "nap"`), never in `sleeps`. Pair
   a recovery with a sleep only when their `activity_id` values match. In this history 15 days were
   affected. WHOOP can also file a full night at an unusual hour as a nap. A nap is listed with when it
   began and ended and nothing else: not its time asleep.
3. **Scripts are blocked.** Cloudflare rejects non-browser requests (error 1010). The owner fetches from
   the browser; this is not to be worked around.

The script applies rules 1 and 2 when merging.

## Findings

### Sleep need: exact

```
sleep need = habitual need + sleep debt + need from strain − credit from naps
```

It matches every night to the minute. The habitual need is a per-person constant over the whole
period. On the first night after a gap in wear it reads as a higher default, then returns.

Each part can be rebuilt from plain inputs:

- **Debt carries from one night to the next.** The debt going into a night equals the debt left by the
  night before (equal within a minute on 176 of 215 consecutive nights).
- **Debt left after a night** that fell `s` hours short of its need is about `0.70 s − 0.053 s²`, never
  more than 2.13 hours, and zero when the need was met. That reproduces WHOOP's figure to 4 minutes on
  average. Roughly two thirds of a small shortfall is carried, and a smaller share of a large one.
- **Need from strain** follows the *previous* day's strain (r 0.95): about `0.0004 × strain^2.7` hours on
  WHOOP's 0 to 21 scale, which fits to 1.1 minutes. That is 1 minute after an easy day, about half an
  hour at strain 13, and over an hour at 19.
- **Nap credit goes to the night after the nap.** It appeared on 22 of the 240 nights: each of them
  followed a cycle with a nap in it, and no night that followed such a cycle went without.
- **The credit is a little less than the nap.** It was 72% to 100% of the nap's length, 93% in the
  middle, and never more than the nap. That is what the nap's time asleep would look like. The export
  does not give a nap's time asleep, so it cannot be confirmed from this data. `0.93 × length` gives the
  credit to 9 minutes on average, and still 9 when the share is taken from the other 21 nights each
  time.
- **Nothing limits it.** After a long nap the need fell to less than half the usual one, and the sum
  above still held.
- **Debt after such a night follows the reduced need,** to a minute on average.
- **The sleep score agrees.** The sleep-score formula below was fitted again on the 197 nights without
  a nap and then run on the 21 with one, which played no part in the fit. With the credit taken off
  the need it is off by 7.3 points on them. With the credit put back it is off by 11.8, and reads low.

### Sleep score: hours against need, plus efficiency and consistency

```
sleep score ≈ −21.8 + 0.66 × sufficiency + 0.25 × efficiency + 0.35 × consistency
```

- Sufficiency is time asleep ÷ sleep need, capped at 100. All three inputs are on a 0–100 scale.
- Fit over 218 nights: R² 0.78, and nights left out of the fit are predicted to within 6.0 points on
  average.
- **Stage composition does not matter.** Adding the deep+REM share changes nothing (its coefficient is
  −0.06 per point).

### Recovery: HRV first, then resting heart rate, then sleep

```
recovery ≈ −18.4 + 46.3 × hrv_component + 18.9 × rhr_component + 0.52 × sleep score
```

- Fit over the 122 nights that carry the sub-scores: R² 0.86, left-out nights predicted to within 8.4
  points.
- Adding respiratory rate and skin temperature brings that to 7.6 points (R² 0.89).

### The sub-scores are percentiles against the last 8 nights

- `hrv_component ≈ Φ(z)`, where z is tonight's ln(HRV) against the mean and spread of the previous 8
  nights, and Φ is the normal curve's cumulative percentile. Correlation 0.89 over 99 nights with 8
  contiguous prior nights. WHOOP's low end is a little less harsh than Φ.
- `rhr_component ≈ Φ(−(RHR − hr_baseline) ÷ 6 bpm)`. Mean absolute error 0.11. `hr_baseline` tracks the
  mean resting heart rate of the previous 8 nights, within about 2 bpm.
- `history_size` counts up from 1 after a gap in wear and stops at 8.

An 8-night baseline is short. It is why WHOOP's recovery reacts within days. The core's baselines look
back two to three weeks.

### Consistency: how much of the day matches the last three nights

```
consistency ≈ −76.5 + 160.8 × (share of the 24 hours in the same state as each of the previous 3 nights)
```

Take bed-to-wake as asleep, and any nap since the night before, and the rest of the day as awake. For
tonight and one earlier night, the share is the part of the 24 hours, by the clock, where both agree.
Average it over the three nights before. Two identical nights score 1.

**Naps count as time asleep.** Over the 178 nights that have all three earlier nights:

| What counts as asleep | Correlation | Mean error | On the 14 nights after a nap |
|---|---|---|---|
| The night alone | 0.83 | 6.5 points | 17.6, too high on 11 of them |
| The night and the naps before it | 0.91 | 4.9 points | 4.6 |

The fitted line hardly moves between the two (−75.6 + 161.9 with naps), so the one above is kept.
Other definitions (overlap only, spread of bed and wake times, longer or shorter windows) fitted
worse.

### Versions

The recovery algorithm was `mav.8.3.0` for the whole period, so one fit covers it. ("mav" is WHOOP's
5.0-generation hardware.) Sleep was scored by `9.1.7.2`, `9.1.8.2` and, briefly in June 2026, `9.1.9.2`.

## The app's model

`android/app/src/main/java/fork/app/scoring/WhoopStyle.kt` puts the pieces together. Its inputs are
what the app has for each night: time asleep, efficiency, bed and wake times, HRV and resting heart
rate, and the other sleeps since the night before (when each began and ended, and its time asleep),
plus one setting, the wearer's usual sleep need.

1. Sleep need is the usual need plus the debt carried in, less the time asleep in the other sleeps
   since the night before. Debt restarts from zero after a day with no night. The need is never taken
   below an hour: a guard against a day of long naps, which the history neither asks for nor rules
   out.
2. The sleep score comes from hours against that need, efficiency and consistency. Consistency counts
   the other sleeps as time asleep. The score uses no sleep stages, which keeps the core's weakest
   output out of it.
3. The HRV and resting-heart-rate components compare tonight with up to 8 earlier nights from the last
   14 days. With fewer than 3 there is no recovery score.
4. Recovery comes from the two components and the sleep score.

Run over the history, using only those inputs. The export has no time asleep for a nap, so 0.93 of
its length stands in; the app measures a nap's time asleep itself and needs no such figure.

| | Mean error | Correlation | Nights |
|---|---|---|---|
| Sleep score | 5.8 points | 0.88 | 240 |
| Recovery | 7.8 points | 0.91 | 215 |

- Recovery lands in the same colour band as WHOOP's on 80% of nights and is never two bands away.
- For scale, always guessing the average recovery is wrong by 21.2 points.
- On the last 40% of nights alone, the recovery error is 7.1 points and 84% share a band. The formulas
  were fitted on all nights, so this is a check that it does not get worse over time, not a clean
  hold-out.
- Refitting the weights on the app's own version of the inputs changed the result by less than a point,
  so the weights from the sections above are kept. That was checked before naps were counted.

**What counting naps changed** (added 2026-10-06). The same model with the naps left out:

| Nights | Sleep need off by | Sleep score off by | Recovery off by |
|---|---|---|---|
| The 22 after a nap | 39 min, was 132 | 8.0 points, was 11.3 | 6.2, was 7.1 |
| The 15 after those | 18 min, was 30 | 5.9, was 7.2 | 9.6, was 9.2 |
| The other 203 | 38 min, was 37 | 5.6, was 5.7 | 7.8, was 7.7 |
| All 240 | 37 min, was 46 | 5.8, was 6.3 | 7.8, as before |

- **Both halves are needed.** With the credit alone, the nights after a nap score 8.7 points too high
  on average (error 10.8): the need drops, and consistency is still read as if the nap had not
  happened. With naps counted in consistency alone they score 5.0 too low (error 13.1). Before, the
  two mistakes cancelled on average and missed night by night.
- With both, those nights still read 4.1 points high on average.
- The night after a nap night gains because its debt is right. Its recovery did not improve; that is
  15 nights at most, too few to read anything into.

The Kotlin code and the Python reference (`model_scores` in the script) are pinned to each other by a
unit test on made-up nights, with naps and without. A second test runs the Kotlin code over the real
history when the private export is present and gets the same figures as the tables.

Left out, because the app does not measure it yet: extra need after a hard day. With that missing,
and debt carried by the model's own arithmetic, the sleep need is off by 37 minutes on average.

The app shows these scores at the top of the screen and labels them experimental.

## Limits

- One person. The coefficients describe how WHOOP scored this wearer, not WHOOP's code.
- The fits are linear. WHOOP's real model has more inputs and probably curves; the remaining 8 points
  of error is where they live.
- **The model has only been run on WHOOP's own nightly numbers.** In the app the inputs are the core's
  measurements of the same things, which differ (the core's resting heart rate reads lower, its HRV is
  averaged differently, its sleep detection is rougher). The components compare each night with the
  wearer's own recent nights, so a constant offset should cancel, but that is an expectation, not a
  result. It needs real nights from the app.
- WHOOP's consistency is matched only approximately (4.9 points).
- **A nap in the app is not a nap in WHOOP.** The app calls a sleep a night when it overlaps 21:00 to
  noon most, and everything else a nap. WHOOP decides its own way, and on travel days filed short
  nights as naps and a full night as one. The rule for what a nap does was fitted on WHOOP's naps and
  runs on the app's.
- Before August 2025, and 2–3 June 2026, are not covered.
