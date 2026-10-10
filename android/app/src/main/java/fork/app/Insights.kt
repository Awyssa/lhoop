// Fork-owned. The pure rules behind what the screens say and draw: how a night compares with the
// wearer's usual, the strip of a night's states, its heart-rate curve, the week and the progress card.
//
// Nothing here measures or scores anything. It arranges what the strap's nights (scoring/) and the
// scores (scoring/WhoopStyle.kt) already hold. No Android, so all of it is unit-tested.
package fork.app

import fork.app.scoring.NightInput
import fork.app.scoring.SleepDays
import fork.app.scoring.SleepRecord
import fork.app.scoring.StateMinute
import fork.app.scoring.StrapSleep
import fork.app.scoring.WhoopStyle
import fork.app.scoring.WhoopStyleScore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** The strap's state and the mean heart rate of one sleep, minute by minute (unix time divided by 60). */
internal data class NightDetail(val minutes: List<StateMinute>, val heart: List<Pair<Long, Double>>)

/** Whether the strap is being recorded right now, as the pill at the top of each screen says it. */
internal data class StrapStatus(val text: String, val ok: Boolean)

internal enum class StripState { ASLEEP, RESTLESS, AWAKE }

/** A run of minutes in one state. */
internal data class StripRun(val state: StripState, val minutes: Int)

/**
 * What a night's HRV and resting heart rate are compared with: the earlier nights the score used.
 * [hrvMs] is their geometric mean, because the score compares HRV in logs.
 */
internal data class Usual(
    val nights: Int,
    val hrvMs: Double?,
    val hrvLow: Double?,
    val hrvHigh: Double?,
    val restingHr: Double?,
    val rhrLow: Double?,
    val rhrHigh: Double?,
)

/** One night as the charts need it. Bed and wake are minutes from the day's midnight where it was slept. */
internal data class TrendPoint(
    val day: LocalDate,
    val asleepMin: Double,
    val needMin: Double?,
    val sufficiencyPct: Double?,
    val sleepScore: Double?,
    val recovery: Double?,
    val hrvMs: Double?,
    val restingHr: Double?,
    val bedMinute: Double?,
    val wakeMinute: Double?,
)

internal enum class Direction { UP, STEADY, DOWN }

/** A figure's mean over the recent nights and over the nights before them. */
internal data class Change(val recent: Double, val earlier: Double) {
    val delta: Double get() = recent - earlier
}

/** The last week against the four weeks before it. A figure is null when either side has too few nights. */
internal data class Progress(
    val recentNights: Int,
    val earlierNights: Int,
    val recovery: Change?,
    val sleepScore: Change?,
    val asleepMin: Change?,
    val hrvMs: Change?,
    val restingHr: Change?,
    /** Which way recovery and the sleep score moved together. Null when neither can be compared. */
    val direction: Direction?,
) {
    val ready: Boolean get() = recentNights >= Insights.PROGRESS_MIN_RECENT && earlierNights >= Insights.PROGRESS_MIN_EARLIER
}

internal object Insights {

    // --- The strap ------------------------------------------------------------------------------------

    /** While connected the app syncs about every half hour, so a last sync older than this is worth showing as a fault. */
    const val STALE_SYNC_SEC = 2 * 3600L

    /**
     * What the pill says. It only reports what the app can see: whether the link is up, whether the
     * service that keeps it up in the background is running, whether the strap has reported itself
     * off the wrist and not back on, and when the last sync finished.
     */
    fun strapStatus(
        connected: Boolean,
        serviceRunning: Boolean,
        lastSyncAt: Long?,
        nowSec: Long,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
        offWrist: Boolean = false,
    ): StrapStatus {
        if (!connected) return StrapStatus("Not connected", ok = false)
        if (!serviceRunning) return StrapStatus("Background recording off", ok = false)
        // Not a fault, but said in the same colour: "Synced" would read as recording while nothing is.
        if (offWrist) return StrapStatus("Off the wrist", ok = false)
        if (lastSyncAt == null) return StrapStatus("Connected", ok = true)
        val offset = zone.rules.getOffset(Instant.ofEpochSecond(lastSyncAt))
        val syncDay = Instant.ofEpochSecond(lastSyncAt).atOffset(offset).toLocalDate()
        val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
        val at = when (syncDay) {
            today -> Nights.clock(lastSyncAt, offset, locale)
            today.minusDays(1) -> "yesterday"
            else -> DateTimeFormatter.ofPattern("d MMM", locale).format(syncDay)
        }
        val stale = nowSec - lastSyncAt > STALE_SYNC_SEC
        return StrapStatus(if (stale) "Last synced $at" else "Synced $at", ok = !stale)
    }

    // --- Against the usual ---------------------------------------------------------------------------

    fun usual(baseline: List<NightInput>): Usual {
        val hrv = baseline.mapNotNull { it.hrvMs }.filter { it > 0.0 }
        val rhr = baseline.mapNotNull { it.restingHr }
        return Usual(
            nights = baseline.size,
            hrvMs = hrv.takeIf { it.isNotEmpty() }?.let { v -> exp(v.sumOf { ln(it) } / v.size) },
            hrvLow = hrv.minOrNull(),
            hrvHigh = hrv.maxOrNull(),
            restingHr = rhr.takeIf { it.isNotEmpty() }?.average(),
            rhrLow = rhr.minOrNull(),
            rhrHigh = rhr.maxOrNull(),
        )
    }

    /** "3 under usual", "1 over usual" or "at your usual", in the whole numbers the screen shows. Null without both. */
    fun againstUsual(value: Double?, usual: Double?): String? {
        if (value == null || usual == null) return null
        val difference = value.roundToInt() - usual.roundToInt()
        return when {
            difference > 0 -> "$difference over usual"
            difference < 0 -> "${-difference} under usual"
            else -> "at your usual"
        }
    }

    /** HRV within this share of the usual reads as "about your usual"; beyond [HRV_WELL] it reads as well above or under. */
    const val HRV_ABOUT = 0.05
    const val HRV_WELL = 0.15

    /**
     * One line under the recovery score saying what drove it, or why there is none. It names HRV, the
     * score's largest input, and sleep against need. It states what was measured and nothing about
     * what the wearer should do.
     */
    fun reason(score: WhoopStyleScore?, hrvMs: Double?, restingHr: Int?, usual: Usual): String {
        if (score?.recovery == null) {
            return when {
                hrvMs == null || restingHr == null -> "No score: this night has no HRV or resting heart rate."
                score != null && score.baselineNights < WhoopStyle.MIN_BASELINE_NIGHTS ->
                    "The score starts once there are ${WhoopStyle.MIN_BASELINE_NIGHTS} earlier nights with HRV. " +
                        "There ${if (score.baselineNights == 1) "is" else "are"} ${score.baselineNights}."
                else -> "No score for this night."
            }
        }
        val hrv = usual.hrvMs?.takeIf { it > 0.0 && hrvMs != null }?.let { hrvMs!! / it - 1.0 }?.let { off ->
            when {
                off >= HRV_WELL -> "HRV is well above your usual"
                off >= HRV_ABOUT -> "HRV is a little above your usual"
                off > -HRV_ABOUT -> "HRV is about your usual"
                off > -HRV_WELL -> "HRV is a little under your usual"
                else -> "HRV is well under your usual"
            }
        }
        val sleep = score.sufficiencyPct?.let { pct ->
            when {
                pct >= 99.5 -> "Sleep covered what you needed"
                pct >= 85.0 -> "Sleep was nearly enough"
                pct >= 70.0 -> "Sleep fell short of what you needed"
                else -> "Sleep fell well short of what you needed"
            }
        }
        return listOfNotNull(hrv, sleep).joinToString(". ").let { if (it.isEmpty()) "" else "$it." }
    }

    /**
     * The lowest, highest and mean share of the sleep need that the nights before [day] met: the same
     * nights, by the same window, that HRV and resting heart rate are compared with. Null with none.
     */
    fun sleepUsual(day: LocalDate, points: List<TrendPoint>): Triple<Double, Double, Double>? {
        val earlier = points
            .filter { it.day < day && it.day >= day.minusDays(WhoopStyle.BASELINE_WINDOW_DAYS) }
            .sortedByDescending { it.day }
            .mapNotNull { it.sufficiencyPct }
            .take(WhoopStyle.BASELINE_NIGHTS)
        return if (earlier.isEmpty()) null else Triple(earlier.min(), earlier.max(), earlier.average())
    }

    /**
     * How the sleep need was put together, such as "8h 0m usual + 25m debt − 35m earlier sleep". Null
     * when it is the usual need and nothing else: the line above it on the card has just said that figure.
     */
    fun needSum(habitualNeedMin: Int, score: WhoopStyleScore): String? {
        if (score.debtInMin < 1.0 && score.napCreditMin < 1.0) return null
        return buildString {
            append(Nights.duration(habitualNeedMin.toDouble())).append(" usual")
            if (score.debtInMin >= 1.0) append(" + ").append(Nights.duration(score.debtInMin)).append(" debt")
            if (score.napCreditMin >= 1.0) append(" − ").append(Nights.duration(score.napCreditMin)).append(" earlier sleep")
        }
    }

    /**
     * Said under the recovery score while it leans on fewer earlier nights than it can use: with few of
     * them one unusual night moves "your usual" and the score swings. Null without a score, and once
     * the baseline is full. [Usual.nights] is the figure the night screen prints beside "Why".
     */
    fun baselineNote(recovery: Double?, usual: Usual): String? {
        if (recovery == null || usual.nights >= WhoopStyle.BASELINE_NIGHTS) return null
        return "Based on your last ${usual.nights} night${if (usual.nights == 1) "" else "s"}. A full score uses ${WhoopStyle.BASELINE_NIGHTS}."
    }

    // --- Off the wrist -------------------------------------------------------------------------------

    /** The key under the noon-to-noon bar, such as "Off the wrist 3h 10m". */
    fun offWristLegend(seconds: Long): String = "Off the wrist ${Nights.durationSec(seconds)}"

    /** Said on a night whose time in bed holds a stretch off the wrist. No score changes for it. */
    fun offWristInBed(seconds: Long): String =
        "The strap was off the wrist for ${Nights.durationSec(seconds)} of this time in bed. " +
            "It recorded nothing then, so that time shows as awake."

    /** A day's line on Trends, once its stretches add up to an hour. Null below that. */
    fun offWristDay(seconds: Long): String? =
        if (seconds >= OffWrist.MENTION_SEC) "Off the wrist for ${Nights.durationSec(seconds)}, noon to noon." else null

    /** "18:40", or "Thu 18:40" when that is not today where the phone is. */
    fun since(ts: Long, nowSec: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val offset = zone.rules.getOffset(Instant.ofEpochSecond(ts))
        val day = Instant.ofEpochSecond(ts).atOffset(offset).toLocalDate()
        val clock = Nights.clock(ts, offset, locale)
        return if (day == Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()) clock
        else "${DateTimeFormatter.ofPattern("EEE", locale).format(day)} $clock"
    }

    /**
     * The one line under the home screen's header, or null. While the strap is off: since when.
     * Otherwise, when last night has no night and the strap was off for an hour or more of it
     * (21:00 to noon, as far as its events reach): how long.
     */
    fun offWristToday(
        wrist: NightsViewModel.Wrist,
        isLastNight: Boolean,
        logicalToday: LocalDate?,
        nowSec: Long,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
    ): String? {
        wrist.offNow?.let { return "Off the wrist since ${since(it.fromTs, nowSec, zone, locale)}." }
        if (isLastNight || logicalToday == null) return null
        val from = logicalToday.minusDays(1).atTime(LocalTime.of(SleepDays.NIGHT_FROM_HOUR, 0)).atZone(zone).toEpochSecond()
        val to = logicalToday.atTime(LocalTime.of(SleepDays.NIGHT_UNTIL_HOUR, 0)).atZone(zone).toEpochSecond()
        val seconds = OffWrist.secondsWithin(wrist.off, from, to, wrist.throughTs)
        return if (seconds >= OffWrist.MENTION_SEC) "The strap was off the wrist for ${Nights.durationSec(seconds)} of last night." else null
    }

    /** The Strap tab's "Worn" line. */
    fun worn(wrist: NightsViewModel.Wrist, nowSec: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val off = wrist.offNow
        return when {
            off != null -> "off the wrist since ${since(off.fromTs, nowSec, zone, locale)}"
            wrist.eventsSeen -> "on the wrist"
            else -> "no wrist event recorded"
        }
    }

    /** Time off the wrist in the [seconds] up to now, for the Strap tab's table. */
    fun offWristLast(seconds: Long, wrist: NightsViewModel.Wrist, nowSec: Long): String =
        Nights.durationSec(OffWrist.secondsWithin(wrist.off, nowSec - seconds, nowSec + 1, minOf(wrist.throughTs, nowSec)))

    /**
     * A night's noon-to-noon window, the one [lastDay] draws, as unix seconds. Null when the night's key
     * is not a date.
     */
    fun lastDayWindow(night: Night): Pair<Long, Long>? =
        Nights.date(night.day)?.let { OffWrist.dayWindow(it, night.record.offset) }

    /** The stretches off the wrist on a night's noon-to-noon bar, each as (from, to) on a scale of 0 to 1. */
    fun offWristOnLastDay(night: Night, wrist: NightsViewModel.Wrist): List<Pair<Float, Float>> {
        val (from, to) = lastDayWindow(night) ?: return emptyList()
        return OffWrist.within(wrist.off, from, to, wrist.throughTs).map { (a, b) ->
            ((a - from) / DAY_SEC).toFloat() to ((b - from) / DAY_SEC).toFloat()
        }
    }

    /** Seconds off the wrist inside a night's noon-to-noon window. */
    fun offWristSecondsOnLastDay(night: Night, wrist: NightsViewModel.Wrist): Long {
        val (from, to) = lastDayWindow(night) ?: return 0L
        return OffWrist.secondsWithin(wrist.off, from, to, wrist.throughTs)
    }

    /**
     * Seconds off the wrist on [day], noon to noon: read in the offset its [night] was slept in when it
     * has one, so the figure is the bar's, and in the phone's own otherwise.
     */
    fun offWristSecondsOn(day: LocalDate, night: Night?, wrist: NightsViewModel.Wrist, zone: ZoneId): Long {
        val offset = night?.record?.offset ?: zone.rules.getOffset(day.atStartOfDay(zone).toInstant())
        return OffWrist.secondsOn(day, offset, wrist.off, wrist.throughTs)
    }

    /** Seconds off the wrist between a night's going still and its end. */
    fun offWristSecondsInBed(night: Night, wrist: NightsViewModel.Wrist): Long =
        OffWrist.secondsWithin(wrist.off, night.record.sleep.bedStartTs, night.record.sleep.endTs + 1, wrist.throughTs)

    // --- The night, drawn ----------------------------------------------------------------------------

    /** A minute counts as sleep on the strip when at least this many of its seconds were asleep or up. */
    const val STRIP_SLEEP_SEC = 30

    /**
     * The time in bed as runs of asleep, restless and awake, minute by minute, from going still to the
     * end of the sleep. Before the first asleep second it is awake. A minute the strap gave no state for
     * is awake too: on the strip a gap in the data and a waking look the same.
     */
    fun strip(sleep: StrapSleep, minutes: List<StateMinute>): List<StripRun> {
        val byMinute = minutes.associateBy { it.minute }
        val firstAsleep = sleep.startTs / 60
        val runs = ArrayList<StripRun>()
        for (minute in sleep.bedStartTs / 60..sleep.endTs / 60) {
            val m = byMinute[minute]
            val state = when {
                m == null || minute < firstAsleep -> StripState.AWAKE
                m.asleepSec + m.upSec < STRIP_SLEEP_SEC -> StripState.AWAKE
                m.upSec > m.asleepSec -> StripState.RESTLESS
                else -> StripState.ASLEEP
            }
            val last = runs.lastOrNull()
            if (last != null && last.state == state) runs[runs.size - 1] = last.copy(minutes = last.minutes + 1)
            else runs += StripRun(state, 1)
        }
        return runs
    }

    /**
     * The heart rate through the time in bed as (how far through it, from 0 to 1; beats a minute), each
     * point the mean of [bucketMin] minutes. Minutes with no heart rate leave a gap in the points.
     */
    fun heartCurve(sleep: StrapSleep, heart: List<Pair<Long, Double>>, bucketMin: Int = 3): List<Pair<Float, Double>> {
        val first = sleep.bedStartTs / 60
        val span = (sleep.endTs / 60 - first + 1).coerceAtLeast(1)
        return heart
            .filter { it.first in first..sleep.endTs / 60 }
            .groupBy { (it.first - first) / bucketMin }
            .toSortedMap()
            .map { (bucket, values) ->
                val middle = bucket * bucketMin + bucketMin / 2.0
                (middle / span).toFloat().coerceIn(0f, 1f) to values.map { it.second }.average()
            }
    }

    /**
     * Where a night and the sleeps before it fall between noon the day before and noon of its day, each
     * as (from, to) on a scale of 0 to 1. Anything outside those 24 hours is cut off at the edge.
     */
    fun lastDay(night: Night): List<Pair<Float, Float>> {
        val from = lastDayWindow(night)?.first ?: return emptyList()
        fun place(r: SleepRecord): Pair<Float, Float>? {
            val a = ((r.sleep.bedStartTs - from) / DAY_SEC).coerceIn(0.0, 1.0)
            val b = ((r.sleep.endTs + 1 - from) / DAY_SEC).coerceIn(0.0, 1.0)
            return if (b > a) a.toFloat() to b.toFloat() else null
        }
        return (night.naps + night.record).mapNotNull(::place)
    }

    // --- Over time -----------------------------------------------------------------------------------

    /** Every night as a chart point, oldest first. A night whose key is not a date is left out. */
    fun trend(nights: List<Night>, scores: Map<String, WhoopStyleScore>): List<TrendPoint> =
        nights.mapNotNull { night ->
            val input = Nights.input(night) ?: return@mapNotNull null
            val score = scores[night.day]
            TrendPoint(
                day = input.day,
                asleepMin = night.asleepMin,
                needMin = score?.needMin,
                sufficiencyPct = score?.sufficiencyPct,
                sleepScore = score?.sleepScore,
                recovery = score?.recovery,
                hrvMs = night.hrvMs,
                restingHr = night.restingHr?.toDouble(),
                bedMinute = input.bedMinute,
                wakeMinute = input.wakeMinute,
            )
        }.sortedBy { it.day }

    /** The seven days ending on [last], oldest first, each with its night or null. */
    fun week(points: List<TrendPoint>, last: LocalDate): List<Pair<LocalDate, TrendPoint?>> {
        val byDay = points.associateBy { it.day }
        return (6L downTo 0L).map { back -> last.minusDays(back).let { it to byDay[it] } }
    }

    /** The recent nights are the last this many days; the earlier ones the [PROGRESS_EARLIER_DAYS] before them. */
    const val PROGRESS_RECENT_DAYS = 7L
    const val PROGRESS_EARLIER_DAYS = 28L

    /** Fewer nights than these on either side and nothing is compared. */
    const val PROGRESS_MIN_RECENT = 4
    const val PROGRESS_MIN_EARLIER = 7

    /** Recovery and the sleep score moving by less than this many points together reads as steady. */
    const val PROGRESS_STEADY_POINTS = 3.0

    /**
     * The last week against the four weeks before it. This is the screen's own rule of thumb, not a
     * fitted score: plain means of each figure over the two spans, and a direction from recovery and
     * the sleep score taken together.
     */
    fun progress(points: List<TrendPoint>, today: LocalDate): Progress {
        val recent = points.filter { it.day > today.minusDays(PROGRESS_RECENT_DAYS) && it.day <= today }
        val earlier = points.filter {
            it.day > today.minusDays(PROGRESS_RECENT_DAYS + PROGRESS_EARLIER_DAYS) && it.day <= today.minusDays(PROGRESS_RECENT_DAYS)
        }
        val enough = recent.size >= PROGRESS_MIN_RECENT && earlier.size >= PROGRESS_MIN_EARLIER
        fun change(of: (TrendPoint) -> Double?): Change? {
            if (!enough) return null
            val a = recent.mapNotNull(of)
            val b = earlier.mapNotNull(of)
            return if (a.size >= PROGRESS_MIN_RECENT && b.size >= PROGRESS_MIN_EARLIER) Change(a.average(), b.average()) else null
        }
        val recovery = change { it.recovery }
        val sleepScore = change { it.sleepScore }
        val moved = listOfNotNull(recovery?.delta, sleepScore?.delta)
        return Progress(
            recentNights = recent.size,
            earlierNights = earlier.size,
            recovery = recovery,
            sleepScore = sleepScore,
            asleepMin = change { it.asleepMin },
            hrvMs = change { it.hrvMs },
            restingHr = change { it.restingHr },
            direction = moved.takeIf { it.isNotEmpty() }?.average()?.let {
                when {
                    it >= PROGRESS_STEADY_POINTS -> Direction.UP
                    it <= -PROGRESS_STEADY_POINTS -> Direction.DOWN
                    else -> Direction.STEADY
                }
            },
        )
    }

    private const val DAY_SEC = 86_400.0
}
