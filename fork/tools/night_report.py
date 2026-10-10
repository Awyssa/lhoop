#!/usr/bin/env python3
"""Summarise what the app recorded, from a backup pulled off the phone.

    fork/tools/night_report.py whoop-data/lhoop-backups/night-2026-10-06             # an unpacked backup
    fork/tools/night_report.py whoop-data/lhoop-backups/night-2026-10-06.lhoopbak     # or the file itself
    fork/tools/night_report.py <backup> --hours 48 --timeline

It prints, for the last --hours of data (36 by default):
  1. how complete the recording is, with the hours the strap reported itself off the wrist named as that;
  2. the sleeps in the STRAP'S OWN state, found by the same rules as the app
     (android/app/src/main/java/fork/app/scoring/StrapSleep.kt and SleepDays.kt), with heart rate and
     HRV worked out here from the raw rows, independently of the app and of its core;
  3. the sleep sessions the core's detector made of the same hours, and how much of each the strap agreed with;
  4. what the core stored for each day;
  5. with --timeline, half-hour blocks of strap state, heart rate and movement.

The strap reports 0 awake, 1 still, 2 asleep, 3 up. Asleep and up both count as sleep, except the last
ten and a half minutes of "up" before the strap calls the wearer awake. Read 2 as the reference and 3 as
the thing being checked: the core's detector was wrong on two of the first three nights.

Pure standard library. The output is health data: keep it out of git.
"""
import argparse
import collections
import glob
import json
import math
import os
import sqlite3
import statistics as st
import tempfile
import time
import zipfile

ASLEEP, UP = 2, 3        # the strap's states: 0 awake, 1 still, 2 asleep, 3 up (moved, not yet settled or awake)
WAKE_CONFIRM_S = 630     # how long the strap takes to call the wearer awake once they are up and moving
BRIDGE_MIN = 5           # a break this long in asleep-or-up minutes does not end a stretch
MIN_STRETCH_ASLEEP_S = 20 * 60
GROUP_GAP_S = 90 * 60    # stretches closer than this are one sleep
MAX_STILL_LEAD_MIN = 60
NIGHT_FROM_HOUR, NIGHT_UNTIL_HOUR = 21, 12   # a day's night window: 21:00 the evening before to noon
OFF_WRIST_SLACK_S = 120  # the samples stop and start within this of the strap's off and on events
OFF_WRIST_GRACE_S = 60   # a sample this soon after the off event is still the wrist (OffWrist.SAMPLE_GRACE_SEC)
OFF_WRIST_MIN_S = 600    # shorter stretches are not mentioned (OffWrist.MIN_SPAN_SEC)

# The same per-minute read the app makes (StrapSleepLoader.MINUTES_SQL).
MINUTES_SQL = """
SELECT ts / 60 AS minute,
       SUM(CASE WHEN state = 0 THEN 1 ELSE 0 END) AS awake, SUM(CASE WHEN state = 1 THEN 1 ELSE 0 END) AS still,
       SUM(CASE WHEN state = 2 THEN 1 ELSE 0 END) AS asleep, SUM(CASE WHEN state = 3 THEN 1 ELSE 0 END) AS up,
       MIN(CASE WHEN state = 1 THEN ts END) AS first_still, MIN(CASE WHEN state = 2 THEN ts END) AS first_asleep,
       MAX(CASE WHEN state = 2 THEN ts END) AS last_asleep, MAX(CASE WHEN state = 3 THEN ts END) AS last_up
FROM sleepStateSample WHERE deviceId = ? AND ts >= ? AND ts < ?
GROUP BY ts / 60 ORDER BY ts / 60"""


def open_db(path):
    if os.path.isdir(path):
        path = glob.glob(os.path.join(path, "*.sqlite"))[0]
    elif zipfile.is_zipfile(path):
        folder = tempfile.mkdtemp(prefix="night-report-")
        archive = zipfile.ZipFile(path)
        member = next(n for n in archive.namelist() if n.endswith(".sqlite"))   # whatever the app was called
        archive.extract(member, folder)
        path = os.path.join(folder, member)
    db = sqlite3.connect(path)
    db.row_factory = sqlite3.Row
    return db


def when(ts):
    return time.strftime("%a %H:%M", time.localtime(ts))


def rmssd(values):
    """RMSSD of one window, dropping jumps of more than 20% (missed or extra beats)."""
    diffs = [b - a for a, b in zip(values, values[1:]) if abs(b - a) < 0.2 * a]
    return math.sqrt(st.mean(d * d for d in diffs)) if len(diffs) >= 20 else None


def vitals(db, device, stretches):
    """Heart rate and HRV over the asleep stretches from the raw rows, independent of the app and of its core."""
    hr_all, five, hrv = [], [], []
    for start, end in stretches:
        hr = [r[0] for r in db.execute("SELECT bpm FROM hrSample WHERE deviceId=? AND ts BETWEEN ? AND ? ORDER BY ts",
                                       (device, start, end))]
        hr_all += hr
        five += [st.mean(hr[i:i + 300]) for i in range(0, max(1, len(hr) - 299), 60)] if len(hr) >= 300 else []
        windows = collections.defaultdict(list)
        for r in db.execute("SELECT ts, rrMs FROM rrInterval WHERE deviceId=? AND ts BETWEEN ? AND ? "
                            "AND (tsSuspect IS NULL OR tsSuspect <> 1) ORDER BY ts, ord", (device, start, end)):
            if 300 <= r["rrMs"] <= 2000:
                windows[(r["ts"] - start) // 300].append(r["rrMs"])
        hrv += [(start + 300 * k, v) for k, v in ((k, rmssd(v)) for k, v in sorted(windows.items())) if v is not None]
    late_from = stretches[-1][1] - 3 * 3600
    late = [v for t, v in hrv if t >= late_from]
    return {
        "mean_hr": st.mean(hr_all) if hr_all else None,
        "lowest_5min_hr": min(five) if five else None,
        "hrv": st.mean(v for _, v in hrv) if len(hrv) >= 6 else None,
        "hrv_last_3h": st.mean(late) if len(late) >= 6 else None,
    }


def strap_sleeps(db, device, since, before):
    """The app's rules (scoring/StrapSleep.kt): stretches of asleep-or-up, grouped into sleeps."""
    rows = db.execute(MINUTES_SQL, (device, since, before)).fetchall()
    by_minute = {r["minute"]: r for r in rows}
    runs, current = [], []
    for r in rows:
        if r["asleep"] + r["up"] <= 0:
            continue
        if current and r["minute"] - current[-1]["minute"] - 1 > BRIDGE_MIN:
            runs.append(current)
            current = []
        current.append(r)
    if current:
        runs.append(current)

    found = []
    for run in runs:
        firsts = [r["first_asleep"] for r in run if r["first_asleep"] is not None]
        in_state_2 = sum(r["asleep"] for r in run)
        if not firsts or in_state_2 < MIN_STRETCH_ASLEEP_S:
            continue
        start = firsts[0]
        last_asleep = max(r["last_asleep"] for r in run if r["last_asleep"] is not None)
        tail_up = (min(next((r["up"] for r in run if r["minute"] == last_asleep // 60), 0), 59 - last_asleep % 60)
                   + sum(r["up"] for r in run if r["minute"] > last_asleep // 60))
        run_end = max([last_asleep] + [r["last_up"] for r in run if r["last_up"] is not None])
        after = by_minute.get(run[-1]["minute"] + 1)
        wake_confirmed = run[-1]["awake"] > 0 or (after is not None and after["awake"] > 0)
        end = max(last_asleep, run_end - WAKE_CONFIRM_S) if wake_confirmed and tail_up > 0 else last_asleep
        counted_tail = min(tail_up, end - last_asleep)
        restless = sum(r["up"] for r in run) - tail_up + counted_tail
        found.append({"start": start, "end": end, "asleep": in_state_2 + restless, "restless": restless,
                      "up_after": tail_up - counted_tail, "wake_confirmed": wake_confirmed})

    sleeps, group = [], []

    def close():
        if not group:
            return
        start = group[0]["start"]
        start_minute = start // 60
        first_still = by_minute[start_minute]["first_still"] if start_minute in by_minute else None
        bed = first_still if first_still is not None and first_still < start else start
        k = start_minute - 1
        while start_minute - k <= MAX_STILL_LEAD_MIN and k in by_minute and by_minute[k]["still"] >= 30:
            bed = by_minute[k]["first_still"] if by_minute[k]["first_still"] is not None else k * 60
            k -= 1
        sleeps.append({"bed": min(bed, start), "start": start, "end": group[-1]["end"],
                       "asleep": sum(g["asleep"] for g in group), "restless": sum(g["restless"] for g in group),
                       "up_after": group[-1]["up_after"], "wake_confirmed": group[-1]["wake_confirmed"],
                       "stretches": [(g["start"], g["end"]) for g in group]})

    for item in found:
        if group and item["start"] - group[-1]["end"] >= GROUP_GAP_S:
            close()
            group = []
        group.append(item)
    close()
    return sleeps


def off_wrist(db, device, since, until):
    """The spans the strap reported itself off the wrist, clipped to [since, until].

    The strap writes a WRIST_OFF event when it comes off and WRIST_ON when it goes back on, and stores no
    samples in between (fork/docs/03-whoop5-status.md). The rule is the app's (fork/app/OffWrist.kt): an
    off runs to the next on, or to the first strap sample stamped more than a minute after it if that
    comes sooner (samples mean it was worn, so a lost on event cannot weld two stretches together); an
    off inside a stretch already open changes nothing; an on with no off before it is ignored; an off
    with neither after it runs to [until]. Stretches under ten minutes are the strap being adjusted and
    are left out, as in the app.
    """
    events = db.execute("SELECT ts, kind FROM event WHERE deviceId=? AND ts<=? AND (kind LIKE 'WRIST_OFF%' OR kind LIKE 'WRIST_ON%') "
                        "ORDER BY ts", (device, until)).fetchall()
    spans, covered_to = [], None
    for i, r in enumerate(events):
        if not r["kind"].startswith("WRIST_OFF") or (covered_to is not None and r["ts"] < covered_to):
            continue
        next_on = next((e["ts"] for e in events[i + 1:] if e["kind"].startswith("WRIST_ON")), None)
        worn = db.execute("SELECT MIN(ts) FROM sleepStateSample WHERE deviceId=? AND ts>? AND ts<=?",
                          (device, r["ts"] + OFF_WRIST_GRACE_S, until)).fetchone()[0]
        ends = [t for t in (next_on, worn) if t is not None]
        covered_to = min(ends) if ends else until
        spans.append((r["ts"], covered_to))
    return [(max(a, since), min(b, until)) for a, b in spans if b - a >= OFF_WRIST_MIN_S and b > since and a < until]


def covered(gap, spans, slack=OFF_WRIST_SLACK_S):
    """Whether [spans] account for a gap in the samples, give or take [slack] seconds at its edges."""
    a, b = gap
    return sum(max(0, min(b, y) - max(a, x)) for x, y in spans) >= (b - a) - slack


def night_day(sleep):
    """The day whose night window (21:00 the evening before to noon) the time in bed overlaps most, or None."""
    end_day = time.localtime(sleep["end"])
    best = None
    for add in (0, 1):
        midnight = time.mktime((end_day.tm_year, end_day.tm_mon, end_day.tm_mday + add, 0, 0, 0, 0, 0, -1))
        lo = midnight - (24 - NIGHT_FROM_HOUR) * 3600
        hi = midnight + NIGHT_UNTIL_HOUR * 3600
        overlap = min(sleep["end"] + 1, hi) - max(sleep["bed"], lo)
        if overlap > 0 and (best is None or overlap > best[1]):
            best = (time.strftime("%Y-%m-%d", time.localtime(midnight)), overlap)
    return best


def hm(seconds):
    return f"{int(seconds) // 3600}h {int(seconds) % 3600 // 60:02d}m"


def num(value, fmt="{:.0f}"):
    return "-" if value is None else fmt.format(value)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("backup", help="an unpacked backup folder, a .lhoopbak file, or the sqlite file")
    ap.add_argument("--hours", type=float, default=36, help="how far back from the newest sample to look")
    ap.add_argument("--timeline", action="store_true", help="also print half-hour blocks")
    args = ap.parse_args()
    db = open_db(args.backup)

    print("integrity:", db.execute("PRAGMA integrity_check").fetchone()[0],
          "| schema", db.execute("PRAGMA user_version").fetchone()[0])
    newest = db.execute("SELECT deviceId, MAX(ts) AS ts FROM hrSample").fetchone()
    device, last = newest["deviceId"], newest["ts"]
    since = last - int(args.hours * 3600)
    print(f"window: {when(since)} -> {when(last)}")

    print("\n1. Recording")
    for table in ("hrSample", "rrInterval", "gravitySample", "skinTempSample", "sleepStateSample"):
        r = db.execute(f"SELECT COUNT(*) n, MIN(ts) lo, MAX(ts) hi FROM {table} WHERE deviceId=? AND ts>=?",
                       (device, since)).fetchone()
        print(f"   {table:17} {r['n']:7}  {when(r['lo']) if r['lo'] else '-'} -> {when(r['hi']) if r['hi'] else '-'}")
    ts = [r[0] for r in db.execute("SELECT ts FROM hrSample WHERE deviceId=? AND ts>=? ORDER BY ts", (device, since))]
    gaps = [(a, b) for a, b in zip(ts, ts[1:]) if b - a > 120]
    off = off_wrist(db, device, since, last)
    print("   off the wrist, by the strap's own events:",
          ", ".join(f"{when(a)}-{when(b)[4:]} ({hm(b - a)})" for a, b in off) or "never")
    print("   heart-rate gaps over 2 minutes with the strap on:",
          ", ".join(f"{when(a)}-{when(b)[4:]} ({(b - a) // 60} min)" for a, b in gaps if not covered((a, b), off)) or "none")
    worn = (last - since) - sum(b - a for a, b in off)
    if worn > 0:
        print(f"   heart rate recorded for {min(100.0, 100 * len(ts) / worn):.1f}% of the time on the wrist")

    print("\n2. Sleeps in the strap's own state, by the app's rules (\"up\" counts, bar the strap's wake confirmation)")
    sleeps = strap_sleeps(db, device, since, last + 1)
    claims = {}
    for sl in sleeps:
        day = night_day(sl)
        if day and (day[0] not in claims or day[1] > claims[day[0]][0]):
            claims[day[0]] = (day[1], sl["start"])
    nights = {start: day for day, (_, start) in claims.items()}
    for sl in sleeps:
        v = vitals(db, device, sl["stretches"])
        in_bed = sl["end"] - sl["bed"] + 1
        awake = sl["end"] - sl["start"] + 1 - sl["asleep"]
        label = f"night of {nights[sl['start']]}" if sl["start"] in nights else "other sleep"
        print(f"   {label}: in bed {when(sl['bed'])} -> {when(sl['end'])}, asleep from {when(sl['start'])[4:]}\n"
              f"      asleep {hm(sl['asleep'])} of which restless {hm(sl['restless'])}, awake in between {hm(awake)}, "
              f"up after {hm(sl['up_after'])}, efficiency {100 * sl['asleep'] / in_bed:.0f}%"
              f"{'' if sl['wake_confirmed'] else ', no waking seen'}\n"
              f"      mean HR {num(v['mean_hr'])}, lowest 5-min HR {num(v['lowest_5min_hr'])} | "
              f"HRV {num(v['hrv'], '{:.1f}')}, last 3 h {num(v['hrv_last_3h'], '{:.1f}')}")
    if not sleeps:
        print("   none")

    print("\n3. The core's sleep sessions in the same window")
    for r in db.execute("SELECT * FROM sleepSession WHERE endTs>=? ORDER BY startTs", (since,)):
        raw = collections.Counter(x[0] for x in db.execute(
            "SELECT state FROM sleepStateSample WHERE deviceId=? AND ts>=? AND ts<?", (device, r["startTs"], r["endTs"])))
        share = (raw[ASLEEP] + raw[UP]) / sum(raw.values()) if raw else None
        # The core's own copy of the state on the session is read through a 100,000-row cap and can be cut short.
        stored = json.loads(r["sleepStateJSON"]) if r["sleepStateJSON"] else None
        stored_share = sum(1 for x in stored if x in (ASLEEP, UP)) / len(stored) if stored else None
        cut = "" if share is None or (stored_share is not None and abs(stored_share - share) < 0.02) else (
            " (the core's stored copy of the state: " + ("none" if stored_share is None else f"{100 * stored_share:.0f}%") + ")")
        in_bed = (r["endTs"] - r["startTs"]) / 60
        eff = r["efficiency"]
        asleep = in_bed * (eff if eff is not None and eff <= 1 else (eff or 0) / 100)
        print(f"   {when(r['startTs'])} -> {when(r['endTs'])}  in bed {in_bed / 60:4.1f} h, asleep {asleep / 60:4.1f} h | "
              f"RHR {num(r['restingHr'])}, HRV {num(r['avgHrv'], '{:.1f}')} | strap had asleep or up "
              f"{'no state' if share is None else f'{100 * share:.0f}%'}{cut}")

    print("\n4. What the core stored per day")
    days = [time.strftime("%Y-%m-%d", time.localtime(last - k * 86400)) for k in (2, 1, 0)]
    for r in db.execute(f"SELECT * FROM dailyMetric WHERE day IN ({','.join('?' * len(days))}) ORDER BY day", days):
        keep = ("totalSleepMin", "efficiency", "deepMin", "remMin", "lightMin", "restingHr", "avgHrv", "recovery",
                "respRateBpm")
        shown = {k: (round(r[k], 1) if isinstance(r[k], float) else r[k]) for k in keep if r[k] is not None}
        score = db.execute("SELECT value FROM metricSeries WHERE day=? AND key='sleep_performance' ORDER BY value DESC",
                           (r["day"],)).fetchone()
        print(f"   {r['day']}: {shown}" + (f" | sleep score {score[0]:.0f}" if score else ""))

    if args.timeline:
        print("\n5. Half-hour blocks: strap state shares (0 wake, 1 still, 2 asleep, 3) | mean HR | movement")
        hr = dict(db.execute("SELECT ts, bpm FROM hrSample WHERE deviceId=? AND ts>=?", (device, since)).fetchall())
        blocks = collections.defaultdict(lambda: {"s": collections.Counter(), "hr": [], "mov": []})
        previous = None
        for r in db.execute("SELECT s.ts, s.state, g.x, g.y, g.z FROM sleepStateSample s LEFT JOIN gravitySample g "
                            "ON g.deviceId=s.deviceId AND g.ts=s.ts WHERE s.deviceId=? AND s.ts>=? ORDER BY s.ts",
                            (device, since)):
            b = blocks[r["ts"] // 1800 * 1800]
            b["s"][r["state"]] += 1
            if r["ts"] in hr:
                b["hr"].append(hr[r["ts"]])
            if r["x"] is not None:
                g = (r["x"], r["y"], r["z"])
                if previous:
                    b["mov"].append(math.dist(g, previous))
                previous = g
        for start in sorted(blocks):
            b = blocks[start]
            n = sum(b["s"].values())
            print(f"   {when(start)}  " + " ".join(f"{100 * b['s'][s] / n:3.0f}%" for s in (0, 1, 2, 3))
                  + f"  {num(st.mean(b['hr']) if b['hr'] else None):>4}  {st.mean(b['mov']) if b['mov'] else 0:.3f}")


if __name__ == "__main__":
    main()
