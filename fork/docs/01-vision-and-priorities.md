# Vision and priorities

## What we are building

A personal Android app that turns a WHOOP 5.0 strap into a trustworthy **sleep and recovery** tracker,
with no WHOOP membership, no account and no cloud.

The app this began as solved the two hard problems: talking to the strap, and showing that an app can
replace WHOOP's. The rest of it is large and aimed elsewhere. So this app keeps its foundation (connect
to the strap, store what it records, the health maths) and builds everything above it new, around the
things below.

## Why

The WHOOP membership has lapsed. The strap still records and still talks over Bluetooth. What was most
valuable in WHOOP was the morning picture: how well did I sleep, and how recovered am I?

## Priorities, in order

1. **Sleep:** a sleep score, time asleep against need, and the stage breakdown (deep, REM, light,
   awake).
2. **Overnight HRV.**
3. **Recovery:** one overall score that reflects HRV, resting heart rate and sleep.
4. **Supporting signals:** resting heart rate, respiratory rate and skin temperature, mainly as inputs
   to the above.

## What is deliberately not a priority

- **Daytime activity, strain and workouts.** A Garmin watch covers sport and the day. The WHOOP is for
  the night.
- **iPhone, Mac and Apple Watch.** The owner uses Android and will not move this to an iPhone. The
  Apple code has been removed.
- **Other devices** (Oura, Polar straps), **the AI coach, widgets, importers, notifications, alarms.**
  Removed on 2026-10-03.

## How we work

- **Validate before improving.** Several weaknesses were first identified by reading code. Each should
  be checked against real nights before and after it is changed.
- **Unknowns are fine.** Having things to fix is the point. Say plainly what is known, what is
  reported and what is a guess.
- **Small steps.** Find a gap, fix it, measure, repeat.
- **Data stays local.** Nothing leaves the phone or this Mac, and health data never goes into git.
- **Leave the core alone where it works.** The Bluetooth, protocol and storage code cannot be tested
  without the strap, so it is changed as little as possible. Since the rename on 2026-10-06 it no
  longer matches upstream file for file, and upstream's fixes are applied by hand. Everything above the
  core is ours to write.
- **Speed over ceremony.** One branch and sensible defaults, with commits and pushes only when the
  owner asks. The care goes into what is
  installed on the phone, because a bad build costs recorded nights.
- **Aim for scores that read like WHOOP's did,** calibrated on the owner's own WHOOP history, not for
  WHOOP's exact numbers.

## What "great" looks like

- Sleep start, wake time and time asleep are right.
- HRV and resting heart rate agree with an independent reference night after night.
- Stages are consistent and plausible, about as good as a consumer wearable. They will not be lab-grade:
  neither WHOOP nor Garmin is.
- The recovery score moves the way it should after short sleep, illness, alcohol or hard training.
- Every score can explain where it came from.
