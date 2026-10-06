#!/usr/bin/env python3
"""Analyse WHOOP history saved from WHOOP's web API (`core-details-bff/v0/cycles/details`).

Reads every `*.json` under a data folder (default: `whoop-data/` at the repository root, which is
git-ignored), merges overlapping downloads into one record per WHOOP cycle, pairs each recovery with
the sleep it was computed from, and re-fits the scoring relationships described in
`fork/docs/05-whoop-scoring-model.md`.

    python3 fork/whoop-history/whoop_history.py              # coverage + formula fits
    python3 fork/whoop-history/whoop_history.py --personal   # also print typical values (health data)
    python3 fork/whoop-history/whoop_history.py --nights     # also print the per-night table
    python3 fork/whoop-history/whoop_history.py --export-nights whoop-data/derived/nights.csv
                                                             # per-night inputs and WHOOP's scores, for
                                                             # checking the app's Kotlin scoring (health data)

Pure standard library. The output is health data: keep it out of git.

Two quirks of the API that this script handles, both found the hard way:

  * The first and last cycle of a download can be truncated: a sleep or workout that crosses the
    requested window is dropped. An occurrence of the cycle that is not on a file's edge is preferred.
  * A cycle's `recovery` is not always the main sleep's. After a nap, WHOOP re-scores the cycle's
    recovery from the nap (the nap is only in `v2_activities`, never in `sleeps`). A recovery is paired
    with a sleep only when `recovery.activity_id == sleep.activity_id`.
"""
import argparse
import glob
import json
import math
import re
import statistics as st
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

MS_PER_HOUR = 3_600_000.0
DEFAULT_DATA = Path(__file__).resolve().parents[2] / "whoop-data"


# --- loading -----------------------------------------------------------------------------------

def cycle_day(record):
    """First day of the cycle's `days` range, e.g. "['2025-11-18','2025-11-19')" -> "2025-11-18"."""
    return re.findall(r"'([^']+)'", record["cycle"]["days"])[0]


def merge(folder):
    """One record per cycle id across all files, preferring complete, sleep-paired occurrences."""
    occurrences = {}
    for path in sorted(glob.glob(f"{folder}/**/*.json", recursive=True)):
        records = json.load(open(path))["records"]
        if not records:
            continue
        days = [cycle_day(r) for r in records]
        lo, hi = min(days), max(days)
        for r in records:
            occurrences.setdefault(r["cycle"]["id"], []).append({"edge": cycle_day(r) in (lo, hi), "rec": r})

    def score(occ):
        r = occ["rec"]
        sleep_ids = {s.get("activity_id") for s in r["sleeps"]}
        rec = r["recovery"] if isinstance(r["recovery"], dict) else {}
        return (rec.get("activity_id") in sleep_ids, len(r["sleeps"]), len(r["workouts"]), rec.get("updated_at") or "")

    merged = []
    for versions in occurrences.values():
        best = max([o for o in versions if not o["edge"]] or versions, key=score)["rec"]
        sleep_ids = {s.get("activity_id") for s in best["sleeps"]}
        for o in versions:  # any download that carries the sleep-paired recovery wins
            rc = o["rec"]["recovery"]
            if isinstance(rc, dict) and rc.get("activity_id") in sleep_ids:
                best = dict(best, recovery=rc)
                break
        merged.append(best)
    merged.sort(key=cycle_day)
    return merged


def _range(text):
    a, b = re.findall(r"'([^']+)'", text)
    parse = lambda t: datetime.fromisoformat(t.replace("Z", "+00:00"))
    return parse(a), parse(b)


def _tz(offset):  # "+01:00" or "+0000"
    m = re.match(r"([+-])(\d{2}):?(\d{2})", offset or "+00:00")
    sign = 1 if m.group(1) == "+" else -1
    return timezone(sign * timedelta(hours=int(m.group(2)), minutes=int(m.group(3))))


def load(folder):
    """Per-night rows: the cycle's main sleep, plus its recovery when the recovery belongs to that sleep."""
    rows = []
    for r in merge(folder):
        main = [s for s in r["sleeps"] if not s.get("is_nap")]
        if not main:
            continue
        s = max(main, key=lambda x: x.get("quality_duration") or 0)
        start, end = _range(s["during"])
        tz = _tz(s.get("timezone_offset"))
        light, deep, rem = s["light_sleep_duration"], s["slow_wave_sleep_duration"], s["rem_sleep_duration"]
        asleep = light + deep + rem
        row = {
            "date": end.astimezone(tz).date(),  # the morning the night ends on
            "bed": start.astimezone(tz), "wake": end.astimezone(tz),
            "in_bed_h": s["time_in_bed"] / MS_PER_HOUR, "asleep_h": asleep / MS_PER_HOUR,
            "light_pct": 100 * light / asleep, "deep_pct": 100 * deep / asleep, "rem_pct": 100 * rem / asleep,
            "eff": 100 * s["in_sleep_efficiency"], "sleep_score": s["score"], "consistency": s["sleep_consistency"],
            "resp": s["respiratory_rate"], "source": s.get("source"), "sleep_algo": s["algo_version"],
            "need_h": s["sleep_need"] / MS_PER_HOUR, "habitual_h": s["habitual_sleep_need"] / MS_PER_HOUR,
            "debt_pre_h": s["debt_pre"] / MS_PER_HOUR, "strain_need_h": s["need_from_strain"] / MS_PER_HOUR,
            "nap_credit_h": s["credit_from_naps"] / MS_PER_HOUR, "strain": r["cycle"]["scaled_strain"],
            "debt_post_h": (s.get("debt_post") or 0) / MS_PER_HOUR,
        }
        rec = r["recovery"]
        if isinstance(rec, dict) and rec.get("recovery_score") is not None and rec.get("activity_id") == s.get("activity_id"):
            row.update({
                "recovery": rec["recovery_score"], "rhr": rec["resting_heart_rate"], "hrv": 1000 * rec["hrv_rmssd"],
                "hr_baseline": rec["hr_baseline"], "skin_temp": rec["skin_temp_celsius"], "spo2": rec["spo2"],
                "hrv_comp": rec["hrv_component"], "rhr_comp": rec["rhr_component"],
                "history": rec["history_size"], "rec_algo": rec["algo_version"],
            })
        rows.append(row)
    rows.sort(key=lambda x: x["date"])
    return rows


# --- statistics (small enough to do without numpy) -------------------------------------------------

def pearson(xs, ys):
    pairs = [(x, y) for x, y in zip(xs, ys) if x is not None and y is not None]
    xs, ys = zip(*pairs)
    mx, my = st.mean(xs), st.mean(ys)
    sxy = sum((x - mx) * (y - my) for x, y in pairs)
    return sxy / math.sqrt(sum((x - mx) ** 2 for x in xs) * sum((y - my) ** 2 for y in ys)), len(pairs)


def _solve(a, b):  # Gaussian elimination with partial pivoting
    n = len(a)
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(m[r][c]))
        m[c], m[p] = m[p], m[c]
        for r in range(n):
            if r != c:
                f = m[r][c] / m[c][c]
                m[r] = [x - f * y for x, y in zip(m[r], m[c])]
    return [m[i][n] / m[i][i] for i in range(n)]


def ols(x, y):
    x1 = [[1.0] + list(r) for r in x]
    k = len(x1[0])
    a = [[sum(r[i] * r[j] for r in x1) for j in range(k)] for i in range(k)]
    b = [sum(r[i] * yy for r, yy in zip(x1, y)) for i in range(k)]
    return _solve(a, b)


def predict(beta, row):
    return beta[0] + sum(b * v for b, v in zip(beta[1:], row))


def fit(x, y):
    """Ordinary least squares with R-squared, mean absolute error and leave-one-out error."""
    beta = ols(x, y)
    pred = [predict(beta, r) for r in x]
    my = st.mean(y)
    r2 = 1 - sum((a - p) ** 2 for a, p in zip(y, pred)) / sum((a - my) ** 2 for a in y)
    loo = [abs(y[i] - predict(ols(x[:i] + x[i + 1:], y[:i] + y[i + 1:]), x[i])) for i in range(len(y))]
    return {"beta": beta, "r2": r2, "mae": st.mean(abs(a - p) for a, p in zip(y, pred)), "loo_mae": st.mean(loo), "n": len(y)}


def phi(z):
    return 0.5 * (1 + math.erf(z / math.sqrt(2)))


# --- report ----------------------------------------------------------------------------------------

def report_coverage(rows):
    paired = [r for r in rows if "recovery" in r]
    print(f"Nights with a main sleep: {len(rows)}   with a recovery paired to that sleep: {len(paired)}")
    print(f"Range: {rows[0]['date']} -> {rows[-1]['date']}")
    have = {r["date"] for r in rows}
    gaps, run, d = [], [], rows[0]["date"]
    while d <= rows[-1]["date"]:
        if d not in have:
            run.append(d)
        else:
            if len(run) >= 2:
                gaps.append((run[0], run[-1], len(run)))
            run = []
        d += timedelta(days=1)
    print("Gaps of 2+ nights:", ", ".join(f"{a}..{b} ({n})" for a, b, n in gaps) or "none")
    print("Algorithm versions (sleep):", dict(Counter(r["sleep_algo"] for r in rows)),
          "(recovery):", dict(Counter(r["rec_algo"] for r in paired)))


def report_fits(rows):
    paired = [r for r in rows if "recovery" in r]
    err = [abs(r["need_h"] - (r["habitual_h"] + r["debt_pre_h"] + r["strain_need_h"] - r["nap_credit_h"])) for r in rows]
    print(f"\nSleep need = habitual + debt + strain need - nap credit: max error {max(err) * 60:.2f} min")

    sufficiency = lambda r: min(1.0, r["asleep_h"] / r["need_h"]) * 100
    s_rows = [r for r in rows if r.get("consistency") is not None]
    f = fit([[sufficiency(r), r["eff"], r["consistency"]] for r in s_rows], [r["sleep_score"] for r in s_rows])
    g = fit([[sufficiency(r), r["eff"], r["consistency"], r["deep_pct"] + r["rem_pct"]] for r in s_rows],
            [r["sleep_score"] for r in s_rows])
    print(f"Sleep score ~ sufficiency + efficiency + consistency: n {f['n']}  R2 {f['r2']:.3f}  "
          f"leave-one-out MAE {f['loo_mae']:.1f}  coefs {[round(b, 2) for b in f['beta']]}")
    print(f"  adding deep+REM share: leave-one-out MAE {g['loo_mae']:.1f} (coef {g['beta'][-1]:+.2f})")

    c_rows = [r for r in paired if r.get("hrv_comp") is not None]
    f = fit([[r["hrv_comp"], r["rhr_comp"], r["sleep_score"]] for r in c_rows], [r["recovery"] for r in c_rows])
    print(f"Recovery ~ hrv_component + rhr_component + sleep score: n {f['n']}  R2 {f['r2']:.3f}  "
          f"leave-one-out MAE {f['loo_mae']:.1f}  coefs {[round(b, 2) for b in f['beta']]}")
    f = fit([[r["hrv_comp"], r["rhr_comp"], r["sleep_score"], r["resp"], r["skin_temp"]] for r in c_rows],
            [r["recovery"] for r in c_rows])
    print(f"  adding respiratory rate + skin temp: R2 {f['r2']:.3f}  leave-one-out MAE {f['loo_mae']:.1f}")

    by_date = {r["date"]: r for r in paired}
    dates = sorted(by_date)
    zs = []
    for r in c_rows:
        i = dates.index(r["date"])
        window = dates[max(0, i - 8):i]
        if len(window) == 8 and (r["date"] - window[0]).days <= 10:  # 8 contiguous prior nights
            logs = [math.log(by_date[d]["hrv"]) for d in window]
            zs.append(((math.log(r["hrv"]) - st.mean(logs)) / st.pstdev(logs), r["hrv_comp"]))
    if zs:
        corr, n = pearson([z for z, _ in zs], [c for _, c in zs])
        print(f"hrv_component vs z(ln HRV against the previous 8 nights): r {corr:+.2f} (n {n})")
    sigma, mae = min(((s, st.mean(abs(phi(-(r["rhr"] - r["hr_baseline"]) / s) - r["rhr_comp"]) for r in c_rows))
                      for s in [x / 2 for x in range(2, 21)]), key=lambda t: t[1])
    print(f"rhr_component ~ Phi(-(RHR - hr_baseline) / {sigma} bpm): mean abs error {mae:.3f}")
    print("history_size values:", dict(sorted(Counter(r.get("history") for r in paired).items())))


# --- the app's model ---------------------------------------------------------------------------------
#
# A reference implementation of android/app/src/main/java/fork/app/scoring/WhoopStyle.kt. Keep the two
# in step: the Kotlin tests pin values produced by this code.

DEBT_CAP_H = 2.13
CONSISTENCY_NIGHTS = 3
BASELINE_NIGHTS, BASELINE_WINDOW_DAYS, MIN_BASELINE_NIGHTS = 8, 14, 3
MIN_LN_HRV_SPREAD, RHR_SPREAD_BPM, NEUTRAL_CONSISTENCY = 0.05, 6.0, 50.0


def debt_after(shortfall_h):
    """Debt left after a night that fell short of its need by `shortfall_h` hours."""
    if shortfall_h <= 0:
        return 0.0
    return max(0.0, min(DEBT_CAP_H, 0.70 * shortfall_h - 0.053 * shortfall_h * shortfall_h))


def clock_interval(row):
    """Bed and wake as minutes from the wake day's local midnight (bed is negative for an evening start)."""
    midnight = row["wake"].replace(hour=0, minute=0, second=0, microsecond=0)
    return (row["bed"] - midnight).total_seconds() / 60.0, (row["wake"] - midnight).total_seconds() / 60.0


def same_state_share(a, b):
    """Share of the 24 hours in which two nights agree on asleep or awake."""
    overlap = max(0.0, min(a[1], b[1]) - max(a[0], b[0]))
    either = (a[1] - a[0]) + (b[1] - b[0]) - overlap
    return min(1.0, max(0.0, (overlap + (1440.0 - either)) / 1440.0))


def model_scores(nights, habitual_h):
    """Score nights with the app's model.

    `nights` is a list of dicts with `date`, `asleep_h`, `eff`, `interval` (see `clock_interval`), `hrv` and
    `rhr`; any of the last five may be None. Returns one dict per night, oldest first.
    """
    by_day = {}
    for n in sorted(nights, key=lambda x: x["date"]):
        by_day.setdefault(n["date"], n)
    out, debt, previous = [], 0.0, None
    for day, n in by_day.items():
        if previous != day - timedelta(days=1):
            debt = 0.0
        need = habitual_h + debt
        suff = None if n.get("asleep_h") is None else max(0.0, min(1.0, n["asleep_h"] / need) * 100)
        shares = [same_state_share(n["interval"], by_day[day - timedelta(days=k)]["interval"])
                  for k in range(1, CONSISTENCY_NIGHTS + 1)
                  if n.get("interval") and by_day.get(day - timedelta(days=k), {}).get("interval")]
        cons = min(100.0, max(0.0, -76.6 + 160.8 * st.mean(shares))) if shares else None
        sleep = None
        if suff is not None and n.get("eff") is not None:
            sleep = min(100.0, max(0.0, -21.8 + 0.66 * suff + 0.25 * n["eff"]
                                   + 0.35 * (cons if cons is not None else NEUTRAL_CONSISTENCY)))
        earlier = []
        for k in range(1, BASELINE_WINDOW_DAYS + 1):
            p = by_day.get(day - timedelta(days=k))
            if p and p.get("hrv") and p.get("rhr") is not None:
                earlier.append(p)
            if len(earlier) == BASELINE_NIGHTS:
                break
        hrv_c = rhr_c = rec = None
        if len(earlier) >= MIN_BASELINE_NIGHTS and n.get("hrv") and n.get("rhr") is not None:
            logs = [math.log(p["hrv"]) for p in earlier]
            hrv_c = phi((math.log(n["hrv"]) - st.mean(logs)) / max(st.stdev(logs), MIN_LN_HRV_SPREAD))
            rhr_c = phi(-(n["rhr"] - st.mean(p["rhr"] for p in earlier)) / RHR_SPREAD_BPM)
            if sleep is not None:
                rec = min(99.0, max(1.0, -18.4 + 46.3 * hrv_c + 18.9 * rhr_c + 0.52 * sleep))
        out.append({"date": day, "need_h": need, "debt_in_h": debt, "sufficiency": suff, "consistency": cons,
                    "sleep_score": sleep, "hrv_comp": hrv_c, "rhr_comp": rhr_c, "baseline_nights": len(earlier),
                    "recovery": rec})
        if n.get("asleep_h") is not None:
            debt = debt_after(need - n["asleep_h"])
        previous = day
    return out


def as_model_night(row):
    return {"date": row["date"], "asleep_h": row["asleep_h"], "eff": row["eff"], "interval": clock_interval(row),
            "hrv": row.get("hrv"), "rhr": row.get("rhr")}


def _mae(a, b):
    return st.mean(abs(x - y) for x, y in zip(a, b))


def report_model(rows):
    """How WHOOP built need, debt and consistency, and how the app's model does end to end."""
    by = {r["date"]: r for r in rows}
    prev = lambda r: by.get(r["date"] - timedelta(days=1))

    print("\nSleep debt")
    pairs = [(prev(r)["debt_post_h"], r["debt_pre_h"]) for r in rows if prev(r)]
    print(f"  debt carried in = debt left by the night before: equal within a minute on "
          f"{sum(abs(a - b) < 0.02 for a, b in pairs)} of {len(pairs)} consecutive nights")
    short = [r["need_h"] - r["asleep_h"] for r in rows]
    print(f"  debt left = min({DEBT_CAP_H}, 0.70 s - 0.053 s^2) for a shortfall of s hours: "
          f"mean error {60 * _mae([debt_after(x) for x in short], [r['debt_post_h'] for r in rows]):.0f} min; "
          f"largest debt seen {max(r['debt_post_h'] for r in rows):.2f} h")

    print("Need from strain")
    pts = [(prev(r)["strain"], r["strain_need_h"]) for r in rows if prev(r) and prev(r).get("strain") is not None]
    corr, n = pearson([a for a, _ in pts], [b for _, b in pts])
    curve = [0.0004 * a ** 2.7 for a, _ in pts]
    print(f"  follows the PREVIOUS day's strain (r {corr:+.2f}, n {n}); 0.0004 x strain^2.7 hours fits to "
          f"{60 * _mae(curve, [b for _, b in pts]):.1f} min")
    naps = [r["nap_credit_h"] for r in rows if r["nap_credit_h"] > 0.01]
    print(f"  nap credit on {len(naps)} of {len(rows)} nights")

    print("Consistency")
    have = [r for r in rows if r.get("consistency") is not None]
    xs, ys = [], []
    for r in have:
        ps = [by.get(r["date"] - timedelta(days=k)) for k in range(1, CONSISTENCY_NIGHTS + 1)]
        if all(ps):
            xs.append(st.mean(same_state_share(clock_interval(r), clock_interval(p)) for p in ps))
            ys.append(r["consistency"])
    f = fit([[x] for x in xs], ys)
    corr, n = pearson(xs, ys)
    print(f"  ~ {f['beta'][0]:.1f} + {f['beta'][1]:.1f} x (share of the day in the same state as each of the "
          f"previous {CONSISTENCY_NIGHTS} nights): r {corr:+.2f}, mean error {f['mae']:.1f} points (n {n})")

    print("The app's model, end to end (inputs: time asleep, efficiency, bed and wake times, HRV, resting HR)")
    habitual = st.median(r["habitual_h"] for r in rows)
    scored = {m["date"]: m for m in model_scores([as_model_night(r) for r in rows], habitual)}
    need_err = _mae([scored[r["date"]]["need_h"] for r in rows], [r["need_h"] for r in rows])
    print(f"  sleep need (habitual + debt only): mean error {60 * need_err:.0f} min")
    ss = [(scored[r["date"]]["sleep_score"], r["sleep_score"]) for r in rows if scored[r["date"]]["sleep_score"] is not None]
    corr, n = pearson([a for a, _ in ss], [b for _, b in ss])
    print(f"  sleep score: mean error {_mae([a for a, _ in ss], [b for _, b in ss]):.1f} points, r {corr:+.2f} (n {n})")
    rc = [(scored[r["date"]]["recovery"], r["recovery"]) for r in rows
          if r.get("recovery") is not None and scored[r["date"]]["recovery"] is not None]
    band = lambda v: 2 if v >= 67 else 1 if v >= 34 else 0
    corr, n = pearson([a for a, _ in rc], [b for _, b in rc])
    mean_rec = st.mean(b for _, b in rc)
    print(f"  recovery: mean error {_mae([a for a, _ in rc], [b for _, b in rc]):.1f} points, r {corr:+.2f}, "
          f"same colour band {100 * st.mean(band(a) == band(b) for a, b in rc):.0f}%, "
          f"two bands apart {100 * st.mean(abs(band(a) - band(b)) == 2 for a, b in rc):.0f}% (n {n})")
    print(f"  for scale, always guessing the average recovery: mean error "
          f"{_mae([mean_rec] * len(rc), [b for _, b in rc]):.1f} points")
    late = rc[int(len(rc) * 0.6):]
    print(f"  on the last 40% of nights only: mean error {_mae([a for a, _ in late], [b for _, b in late]):.1f} "
          f"points, same band {100 * st.mean(band(a) == band(b) for a, b in late):.0f}% (n {len(late)})")


def export_nights(rows, path):
    """Per-night inputs and WHOOP's scores as CSV, for checking the Kotlin scoring. Health data."""
    import csv
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["date", "asleep_min", "efficiency_pct", "bed_minute", "wake_minute", "hrv_ms", "resting_hr",
                    "habitual_need_min", "whoop_sleep_score", "whoop_recovery"])
        for r in rows:
            bed, wake = clock_interval(r)
            g = lambda k: "" if r.get(k) is None else r[k]
            w.writerow([r["date"], round(r["asleep_h"] * 60, 3), round(r["eff"], 3), round(bed, 3), round(wake, 3),
                        g("hrv"), g("rhr"), round(r["habitual_h"] * 60, 3), g("sleep_score"), g("recovery")])
    print(f"Wrote {len(rows)} nights to {path} (health data: keep it out of git)")


def report_personal(rows):
    print("\nTypical values (health data, keep private):")
    for key, label in [("recovery", "Recovery %"), ("hrv", "HRV ms"), ("rhr", "Resting HR"), ("resp", "Resp rate"),
                       ("skin_temp", "Skin temp C"), ("spo2", "SpO2 %"), ("sleep_score", "Sleep score"),
                       ("asleep_h", "Asleep h"), ("eff", "Efficiency %"), ("deep_pct", "Deep %"),
                       ("rem_pct", "REM %"), ("need_h", "Sleep need h"), ("habitual_h", "Habitual need h")]:
        vals = [r[key] for r in rows if r.get(key) is not None]
        print(f"  {label:16} mean {st.mean(vals):6.1f}  median {st.median(vals):6.1f}  "
              f"range {min(vals):.1f}-{max(vals):.1f}  (n {len(vals)})")
    zones = Counter("green" if r["recovery"] >= 67 else "yellow" if r["recovery"] >= 34 else "red"
                    for r in rows if "recovery" in r)
    print("  recovery zones:", dict(zones), f"| nights under 6 h asleep: {sum(r['asleep_h'] < 6 for r in rows)}/{len(rows)}")


def report_nights(rows):
    print("\n      date   bed  wake asleep  L%  D%  R% eff slp rec   hrv rhr")
    for r in rows:
        g = lambda k, f="{:.0f}": f.format(r[k]) if r.get(k) is not None else "-"
        print(f"{r['date']} {r['bed']:%H:%M} {r['wake']:%H:%M} {r['asleep_h']:6.2f} {r['light_pct']:3.0f} "
              f"{r['deep_pct']:3.0f} {r['rem_pct']:3.0f} {r['eff']:3.0f} {g('sleep_score'):>3} {g('recovery'):>3} "
              f"{g('hrv', '{:.1f}'):>5} {g('rhr'):>3}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("folder", nargs="?", default=str(DEFAULT_DATA), help="folder of WHOOP API JSON files")
    ap.add_argument("--personal", action="store_true", help="also print typical values (health data)")
    ap.add_argument("--nights", action="store_true", help="also print the per-night table (health data)")
    ap.add_argument("--export-nights", metavar="CSV", help="write per-night inputs and WHOOP's scores (health data)")
    args = ap.parse_args()
    rows = load(args.folder)
    if not rows:
        raise SystemExit(f"No nights found under {args.folder}")
    report_coverage(rows)
    report_fits(rows)
    report_model(rows)
    if args.export_nights:
        export_nights(rows, args.export_nights)
    if args.personal:
        report_personal(rows)
    if args.nights:
        report_nights(rows)


if __name__ == "__main__":
    main()
