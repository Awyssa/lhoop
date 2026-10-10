#!/usr/bin/env python3
"""What the deep / REM / light split would be if one term of the stager were different.

    fork/tools/stage_whatif.py whoop-data/lhoop-backups/night-2026-10-09
    fork/tools/stage_whatif.py <backup> --garmin whoop-data/garmin

The app's stage split is the core's stager (android/.../analytics/SleepStagerV2.kt) run over each sleep
the strap flagged (fork/app/scoring/SleepStagesCalc.kt), with one constant of the app's own: the rise in
REM with time of night is half the engine's (SleepStagesCalc.REM_RISE), since 2026-10-10. At full
strength its REM read high. This is a research tool for trying a change before it goes anywhere near
the app. It prints, per sleep:

  1. the split as shipped, which must equal what the app shows. `StrapSleepBackupCheckTest` prints the
     app's own figures for the same backup: if the two differ, this port has drifted from the Kotlin and
     nothing below it means anything until it is brought back in line;
  2. the REM share with one term of the recipe changed at a time;
  3. where in the sleep the REM falls, and how much each term carried it;
  4. with --garmin, the Garmin's split for the nights that have a capture
     (fork/tools/capture_garmin.sh), beside the app's.

It is a port, kept to the Kotlin line for line: the same features, emissions, cycle prior, REM-latency
guard and Viterbi pass. It changes nothing in the app. A variant that lands near one reference night
proves nothing: several different changes do. Judge a change on nights it was not chosen on
(fork/docs/06-validation-plan.md).

Pure standard library, Python 3.12 or later. The output is health data: keep it out of git.
"""
import argparse
import glob
import math
import os
import re
import statistics as st
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import night_report as nr  # noqa: E402  (the app's sleep-finding rules, shared with the morning report)

# --- The recipe's constants, from SleepStagerV2.kt -------------------------------------------------------
STAGES = ["deep", "rem", "light", "awake"]          # the order ties resolve in
BASE_PRIOR = {"light": 0.50, "deep": 0.15, "rem": 0.22, "awake": 0.10}
TRANSITION = {
    "deep": {"deep": 0.86, "rem": 0.007, "light": 0.126, "awake": 0.007},
    "rem": {"deep": 0.005, "rem": 0.88, "light": 0.10, "awake": 0.015},
    "light": {"deep": 0.06, "rem": 0.06, "light": 0.85, "awake": 0.03},
    "awake": {"deep": 0.0, "rem": 0.0, "light": 0.10, "awake": 0.90},
}
DEEP_GATE_THRESH, DEEP_GATE_SLOPE = 0.25, 5.0
JERK_MOVE_MULT, JERK_GATE_MULT, MOTION_GATE_BOOST = 38.0, 55.0, 2.0
RESP_WEIGHT = 0.6
REM_LATENCY_PENALTY, REM_LATENCY_MINUTES = 3.0, 60.0
ONSET_SUSTAINED_EPOCHS = 10
PAD_LO, PAD_HI = 360, 420                             # how far outside the sleep the features read

# What the app ships with. Each variant below overrides one or two of these. "ramp" is the app's own
# SleepStagesCalc.REM_RISE, half the engine's 1.0; every other constant is the engine's.
SHIPPED = {"ramp": 0.5, "rem_prior": BASE_PRIOR["rem"], "resp": True, "hr": 0.4, "hr_var": 0.6, "move": 0.6}
VARIANTS = [
    ("as shipped", {}),
    ("the engine's own rise (1.0)", {"ramp": 1.0}),
    ("no rise with time of night", {"ramp": 0.0}),
    ("no breathing-regularity term", {"resp": False}),
    ("no heart-rate-variability term", {"hr_var": 0.0}),
    ("no heart-rate-level term", {"hr": 0.0}),
    ("no movement term", {"move": 0.0}),
    ("REM base rate 0.15", {"rem_prior": 0.15}),
]

_twiddles = {}


def resp_regularity(beats):
    """`respRegularity`: spectral peakedness of the beat intervals in the breathing band, or None."""
    if len(beats) < 12:
        return None
    t0, tn = beats[0][0], beats[-1][0]
    if tn <= t0:
        return None
    n = math.ceil((tn - t0) / 0.25 - 1e-9)
    if n < 16:
        return None
    y, seg = [0.0] * n, 0
    for i in range(n):
        t = t0 + 0.25 * i
        while seg < len(beats) - 2 and beats[seg + 1][0] < t:
            seg += 1
        (ta, va), (tb, vb) = beats[seg], beats[seg + 1]
        y[i] = va if tb <= ta else va + min(1.0, max(0.0, (t - ta) / (tb - ta))) * (vb - va)
    mean = sum(y) / n
    y = [v - mean for v in y]
    k_lo, k_hi = math.ceil(0.15 * 0.25 * n), math.floor(0.40 * 0.25 * n)
    if k_hi < k_lo or k_lo < 0:
        return None
    if n not in _twiddles:
        _twiddles[n] = [([math.cos(-2 * math.pi * k / n * j) for j in range(n)],
                         [math.sin(-2 * math.pi * k / n * j) for j in range(n)]) for k in range(k_lo, k_hi + 1)]
    peak = total = 0.0
    for cos, sin in _twiddles[n]:
        re, im = math.sumprod(y, cos), math.sumprod(y, sin)
        power = re * re + im * im
        total += power
        peak = max(peak, power)
    return None if total == 0.0 else peak / total


def features(start, end, grav, hr, rr):
    """`features`: one dict per 30-second epoch that has any heart rate or motion. Rows keyed by second."""
    span = float(max(1, end - start))
    if hr:
        lo, hi = min(hr), max(hr)
        n = hi - lo + 1
        total, squares, count = [0.0] * (n + 1), [0.0] * (n + 1), [0] * (n + 1)
        for i in range(n):
            v = hr.get(lo + i)
            total[i + 1] = total[i] + (v or 0.0)
            squares[i + 1] = squares[i] + (v * v if v is not None else 0.0)
            count[i + 1] = count[i] + (1 if v is not None else 0)

        def spread(a, b):
            a, b = min(max(a, lo), lo + n), min(max(b, lo), lo + n)
            if b <= a or count[b - lo] - count[a - lo] < 2:
                return None
            k = count[b - lo] - count[a - lo]
            mean = (total[b - lo] - total[a - lo]) / k
            return math.sqrt(max(0.0, (squares[b - lo] - squares[a - lo]) / k - mean * mean))
    else:
        def spread(a, b):
            return None

    epochs, all_jerks = [], []
    e = (start + 29) // 30 * 30
    while e < end:
        beats_hr = [hr[s] for s in range(e, e + 30) if s in hr]
        g = [grav[s] for s in range(e, e + 30) if s in grav]
        if not beats_hr and not g:
            e += 30
            continue
        jerks = [math.dist(g[i - 1], g[i]) for i in range(1, len(g))]
        all_jerks += jerks
        beats = sorted((float(s), min(2000.0, max(300.0, float(v)))) for s in range(e - 90, e + 120) for v in rr.get(s, ()))
        epochs.append({
            "start": e, "hr": sum(beats_hr) / len(beats_hr) if beats_hr else None,
            "hr_var": spread(e - 150, e + 180), "hr_flat": spread(e - 330, e + 390),
            "jerks": jerks, "gap": max(1, len(g) - 1), "jerk_max": max(jerks) if jerks else 0.0,
            "resp": resp_regularity(beats), "clock": (e + 15 - start) / span, "minutes": (e + 15 - start) / 60.0,
        })
        e += 30
    floor = st.median(all_jerks) if all_jerks else 1e-6
    for ep in epochs:
        ep["move"] = sum(1 for j in ep["jerks"] if j > floor * JERK_MOVE_MULT) / ep["gap"]
        ep["jerk_floor"] = floor
    return epochs


def viterbi(emissions):
    log_t = {p: {s: math.log(max(v, 1e-9)) for s, v in row.items()} for p, row in TRANSITION.items()}
    v, back = dict(emissions[0]), []
    for t in range(1, len(emissions)):
        new, pointer = {}, {}
        for s in STAGES:
            best, value = STAGES[0], v[STAGES[0]] + log_t[STAGES[0]][s]
            for p in STAGES[1:]:
                x = v[p] + log_t[p][s]
                if x > value:
                    best, value = p, x
            new[s], pointer[s] = value + emissions[t][s], best
        v = new
        back.append(pointer)
    last = STAGES[0]
    for s in STAGES[1:]:
        if v[s] > v[last]:
            last = s
    path = [last]
    for pointer in reversed(back):
        last = pointer[last]
        path.append(last)
    return path[::-1]


def z_score(values):
    present = [v for v in values if v is not None]
    if not present:
        return lambda v: 0.0
    mean = sum(present) / len(present)
    sd = math.sqrt(sum((v - mean) ** 2 for v in present) / len(present)) or 1.0
    return lambda v: 0.0 if v is None else (v - mean) / sd


def stage(epochs, **changed):
    """`stageEpochs`: a label per epoch. Returns (labels, the terms of each epoch's REM-against-light log-odds)."""
    w = dict(SHIPPED, **changed)
    z_hr, z_var = z_score([e["hr"] for e in epochs]), z_score([e["hr_var"] for e in epochs])
    z_move, z_resp = z_score([e["move"] for e in epochs]), z_score([e["resp"] for e in epochs])
    flat = sorted(e["hr_flat"] for e in epochs if e["hr_flat"] is not None)

    def flat_rank(value):
        if value is None or not flat:
            return 0.5
        lo, hi = 0, len(flat)
        while lo < hi:
            mid = (lo + hi) // 2
            if flat[mid] <= value:
                lo = mid + 1
            else:
                hi = mid
        return lo / len(flat)

    emissions, terms = [], []
    for e in epochs:
        a, b, c = z_hr(e["hr"]), z_var(e["hr_var"]), z_move(e["move"])
        gate = DEEP_GATE_SLOPE * max(0.0, flat_rank(e["hr_flat"]) - DEEP_GATE_THRESH)
        cardiac = 0.8 * b + 0.4 * a
        still = e["move"] <= 0.0 and e["jerk_max"] <= e["jerk_floor"] * JERK_GATE_MULT
        em = {
            "deep": -1.1 * b - 0.5 * c - gate + math.log(BASE_PRIOR["deep"]) + 1.2 * max(0.0, 1.0 - e["clock"] / 0.55),
            "rem": w["hr_var"] * b - w["move"] * c + w["hr"] * a + math.log(w["rem_prior"]) + w["ramp"] * e["clock"],
            "light": math.log(BASE_PRIOR["light"]),
            "awake": c + (min(0.0, cardiac) if still else cardiac) + math.log(BASE_PRIOR["awake"]),
        }
        if e["jerk_max"] > e["jerk_floor"] * JERK_GATE_MULT:
            em["awake"] += MOTION_GATE_BOOST
        r = 0.0
        if w["resp"] and e["resp"] is not None:
            r = z_resp(e["resp"])
            em["deep"] += RESP_WEIGHT * r
            em["rem"] -= RESP_WEIGHT * r
        emissions.append(em)
        terms.append({"base rate": math.log(w["rem_prior"] / BASE_PRIOR["light"]), "time of night": w["ramp"] * e["clock"],
                      "HR variability": w["hr_var"] * b, "HR level": w["hr"] * a, "stillness": -w["move"] * c,
                      "breathing": -RESP_WEIGHT * r})
    # Pass 1 finds sleep onset with the REM-latency guard off; pass 2 applies the guard from that onset.
    origin, run = 0.0, 0
    for i, label in enumerate(viterbi(emissions)):
        if label == "awake":
            run = 0
            continue
        run += 1
        if run >= ONSET_SUSTAINED_EPOCHS:
            origin = epochs[i - ONSET_SUSTAINED_EPOCHS + 1]["minutes"]
            break
    for em, e in zip(emissions, epochs):
        em["rem"] -= REM_LATENCY_PENALTY * min(1.0, max(0.0, 1.0 - (e["minutes"] - origin) / REM_LATENCY_MINUTES))
    return viterbi(emissions), terms


def rows(db, device, sleep):
    """The rows the app hands the stager for a sleep (StrapSleepLoader.stages), keyed by second."""
    start, end = sleep["start"], sleep["end"] + 1
    lo, hi = start - PAD_LO, end + PAD_HI
    hr = dict(db.execute("SELECT ts, bpm FROM hrSample WHERE deviceId=? AND ts>=? AND ts<?", (device, lo, hi)).fetchall())
    grav = {r[0]: (r[1], r[2], r[3]) for r in db.execute(
        "SELECT ts, x, y, z FROM gravitySample WHERE deviceId=? AND ts>=? AND ts<?", (device, lo, hi))}
    # As WHOOP5_RR_INTERVALS_SQL: one source for the window, the lowest of 5 and 7 present, suspect rows left out.
    window = (device, lo, sleep["end"] + PAD_HI)
    source = db.execute("SELECT MIN(srcChannel) FROM rrInterval WHERE deviceId=? AND ts>=? AND ts<=? AND srcChannel IN (5, 7) "
                        "AND (tsSuspect IS NULL OR tsSuspect <> 1)", window).fetchone()[0]
    rr = {}
    for ts, ms in db.execute("SELECT ts, rrMs FROM rrInterval WHERE deviceId=? AND ts>=? AND ts<=? AND srcChannel=? "
                             "AND (tsSuspect IS NULL OR tsSuspect <> 1)", window + (source,)):
        if ts < hi:
            rr.setdefault(ts, []).append(ms)
    return start, end, grav, hr, rr


def shares(sleep, epochs, labels):
    """`SleepStagesCalc.fromSegments`: the labels inside the sleep's stretches, as shares. Stager "wake" there is light."""
    seconds = dict.fromkeys(STAGES, 0)
    for i, e in enumerate(epochs):
        seg_start = sleep["start"] if i == 0 else e["start"]
        seg_end = sleep["end"] + 1 if i == len(epochs) - 1 else epochs[i + 1]["start"]
        for a, b in sleep["stretches"]:
            seconds[labels[i]] += max(0, min(seg_end, b + 1) - max(seg_start, a))
    total = sum(seconds.values()) or 1
    return {"deep": seconds["deep"] / total, "rem": seconds["rem"] / total,
            "light": (seconds["light"] + seconds["awake"]) / total}


# --- The Garmin's split, from the text capture_garmin.sh saves ------------------------------------------

DURATION = r"(?:(\d+) hours?)? ?(?:(\d+) minutes?)?"
STAGE_LINE = re.compile(r"'(Deep|Light|REM|Awake) " + DURATION + r"'")
DAY_LINE = re.compile(r"'(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday) (\d{1,2}) ([A-Z][a-z]+)'")


def garmin_stages(text, captured_on):
    """{'day': 'YYYY-MM-DD', 'deep': minutes, 'light': ..., 'rem': ..., 'awake': ...} from one page's text, or None.

    The page names its night as 'Today', or as a weekday and date with no year: the year is the capture's.
    """
    found = {m.group(1).lower(): int(m.group(2) or 0) * 60 + int(m.group(3) or 0) for m in STAGE_LINE.finditer(text)}
    if not {"deep", "light", "rem"} <= found.keys():
        return None
    named = DAY_LINE.search(text)
    if named:
        day = time.strptime(f"{named.group(1)} {named.group(2)} {captured_on[:4]}", "%d %B %Y")
        found["day"] = time.strftime("%Y-%m-%d", day)
    elif "'Today'" in text:
        found["day"] = captured_on
    else:
        return None
    return found


def garmin_nights(folder):
    """Every night with a stage capture under [folder]/<date>/, by day. A later capture of a night wins."""
    nights = {}
    for path in sorted(glob.glob(os.path.join(folder, "*", "*.txt"))):
        night = garmin_stages(open(path, encoding="utf-8", errors="replace").read(), os.path.basename(os.path.dirname(path)))
        if night:
            nights[night["day"]] = night
    return nights


def pct(share):
    return f"{100 * share:3.0f}%"


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("backup", help="an unpacked backup folder, a .lhoopbak file, or the sqlite file")
    ap.add_argument("--garmin", help="the folder of Garmin captures, to print its split beside the app's")
    args = ap.parse_args()
    db = nr.open_db(args.backup)
    newest = db.execute("SELECT deviceId, MAX(ts) AS ts FROM hrSample").fetchone()
    device, last = newest["deviceId"], newest["ts"]
    sleeps = nr.strap_sleeps(db, device, 0, last + 1)
    if not sleeps:
        print("no sleeps in this backup")
        return
    staged = []
    for sleep in sleeps:
        start, end, grav, hr, rr = rows(db, device, sleep)
        staged.append((sleep, features(start, end, grav, hr, rr)))
    staged = [(sleep, epochs) for sleep, epochs in staged if len(epochs) >= 2]

    def name(sleep):
        return f"{nr.when(sleep['start'])} -> {nr.when(sleep['end'])[4:]}"

    print("1. The split as shipped (this must equal the app's: see StrapSleepBackupCheckTest)")
    for sleep, epochs in staged:
        s = shares(sleep, epochs, stage(epochs)[0])
        print(f"   {name(sleep)}  asleep {nr.hm(sleep['asleep'])} | deep {nr.hm(sleep['asleep'] * s['deep'])} ({pct(s['deep']).strip()}), "
              f"REM {nr.hm(sleep['asleep'] * s['rem'])} ({pct(s['rem']).strip()}), light {nr.hm(sleep['asleep'] * s['light'])}")

    print("\n2. REM as a share of time asleep, with one term changed (one column per sleep, in the order above)")
    for label, changed in VARIANTS:
        values = [shares(sleep, epochs, stage(epochs, **changed)[0])["rem"] for sleep, epochs in staged]
        print(f"   {label:32}" + " ".join(pct(v) for v in values) + f"   mean {pct(st.mean(values)).strip()}")
    values = [shares(sleep, epochs, stage(epochs)[0])["deep"] for sleep, epochs in staged]
    print(f"   {'deep, as shipped':32}" + " ".join(pct(v) for v in values) + f"   mean {pct(st.mean(values)).strip()}")

    print("\n3. Where the REM falls (by third of the sleep), and the mean of each term in the epochs called REM")
    print("   (log-odds of REM against light; the base rate is what the others have to overcome)")
    for sleep, epochs in staged:
        labels, terms = stage(epochs)
        rem = [i for i, label in enumerate(labels) if label == "rem"]
        if not rem:
            print(f"   {name(sleep)}  no REM")
            continue
        thirds = [sum(1 for i in rem if i * 3 // len(epochs) == k) / len(rem) for k in range(3)]
        carried = ", ".join(f"{k} {st.mean(terms[i][k] for i in rem):+.2f}" for k in terms[0])
        print(f"   {name(sleep)}  {'/'.join(pct(t).strip() for t in thirds)}, first after {rem[0] // 2} min | {carried}")

    if args.garmin:
        print("\n4. Against the Garmin, for nights with a capture (the Garmin's stages are an estimate too)")
        garmin = garmin_nights(args.garmin)
        matched = 0
        for sleep, epochs in staged:
            day = nr.night_day(sleep)
            other = garmin.get(day[0]) if day else None
            if not other:
                continue
            matched += 1
            s = shares(sleep, epochs, stage(epochs)[0])
            asleep_min = sleep["asleep"] / 60
            theirs = other["deep"] + other["light"] + other["rem"]
            print(f"   {day[0]}  app    asleep {nr.hm(sleep['asleep'])} | deep {asleep_min * s['deep']:4.0f} min ({pct(s['deep']).strip()}), "
                  f"REM {asleep_min * s['rem']:4.0f} min ({pct(s['rem']).strip()}), light {asleep_min * s['light']:4.0f} min")
            print(f"   {'':10}  Garmin asleep {nr.hm(theirs * 60)} | deep {other['deep']:4.0f} min ({pct(other['deep'] / theirs).strip()}), "
                  f"REM {other['rem']:4.0f} min ({pct(other['rem'] / theirs).strip()}), light {other['light']:4.0f} min"
                  f"{', awake ' + str(other['awake']) + ' min' if 'awake' in other else ''}")
            if abs(theirs - asleep_min) > 45:
                print(f"   {'':10}  the two disagree on the sleep itself by {abs(theirs - asleep_min):.0f} min, so the stages are not comparable")
        print(f"   {matched} of {len(staged)} sleeps have a capture. {len(garmin)} captured night(s) under {args.garmin}.")


if __name__ == "__main__":
    main()
