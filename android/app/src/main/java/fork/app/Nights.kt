// Fork-owned. What the "Last night" screen shows, and the pure rules that pick and format it.
//
// A night is a sleep the strap itself flagged (scoring/StrapSleep.kt), with heart figures worked out over
// that same sleep. The core's own figures for the day ride along as [CoreDay] and are shown apart,
// because the core detects sleep its own way and can describe a different stretch.
package fork.app

import com.lhoop.analytics.RecoveryScorer
import com.lhoop.data.DailyMetric
import com.lhoop.data.SleepSession
import fork.app.scoring.NightInput
import fork.app.scoring.SleepRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** What the core stored for a day: its own sleep detection and its own scores. Nothing here is recomputed. */
internal data class CoreDay(
    val recoveryPct: Double?,
    val sleepScore: Double?,
    val asleepMin: Double?,
    val efficiencyPct: Double?,
    val hrvMs: Double?,
    val restingHr: Int?,
    val respRateBpm: Double?,
    val skinTempDevC: Double?,
    val deepMin: Double?,
    val remMin: Double?,
    val lightMin: Double?,
    /** Start and end of the longest session the core detected ending that day. */
    val bedStartTs: Long?,
    val bedEndTs: Long?,
    /** The core staged this night from heart rate alone, so it withheld HRV and resting heart rate. */
    val heartRateOnly: Boolean,
)

/** One night as the screen shows it. [day] is the calendar day the night belongs to. */
internal data class Night(
    val day: String,
    val record: SleepRecord,
    /** Other sleeps between the night before and this one. */
    val naps: List<SleepRecord>,
    val core: CoreDay?,
) {
    val asleepMin: Double get() = record.sleep.asleepSec / 60.0
    val hrvMs: Double? get() = record.vitals?.hrvMs
    val restingHr: Int? get() = record.vitals?.restingHr
}

internal enum class RecoveryBand { RED, YELLOW, GREEN }

internal object Nights {

    /** The stored key of the core's sleep score ("Rest"), 0 to 100, in the metricSeries table. */
    const val SLEEP_SCORE_KEY = "sleep_performance"

    /** How many nights the history list shows. */
    const val HISTORY_NIGHTS = 7

    /** A run of "up" after the sleep that is not counted is mentioned once it is this long. Shorter is the strap confirming the wearer got up. */
    const val RESTLESS_AFTER_NOTE_MIN = 15.0

    /** Stored efficiency is a fraction for nights the core scored and a percentage for imported ones. */
    fun efficiencyPct(raw: Double?): Double? = raw?.let { if (it <= 1.0) it * 100.0 else it }

    /** The core's own red / yellow / green thresholds for a recovery score. */
    fun band(recoveryPct: Double?): RecoveryBand? = recoveryPct?.let {
        when (RecoveryScorer.band(it)) {
            "red" -> RecoveryBand.RED
            "yellow" -> RecoveryBand.YELLOW
            else -> RecoveryBand.GREEN
        }
    }

    /** Whether a stored day holds anything from a night. */
    fun hasNight(d: DailyMetric): Boolean =
        d.totalSleepMin != null || d.avgHrv != null || d.restingHr != null || d.recovery != null

    /** The sessions that ended on [day], local time. */
    fun sessionsOn(day: String, sessions: List<SleepSession>, zone: ZoneId): List<SleepSession> =
        sessions.filter { Instant.ofEpochSecond(it.endTs).atZone(zone).toLocalDate().toString() == day }

    /** [daySessions] are the core's sessions that ended on the day (see [sessionsOn]). */
    fun core(d: DailyMetric, sleepScore: Double?, daySessions: List<SleepSession>): CoreDay {
        val longest = daySessions.maxByOrNull { it.endTs - it.effectiveStartTs }
        return CoreDay(
            recoveryPct = d.recovery,
            sleepScore = sleepScore,
            asleepMin = d.totalSleepMin,
            efficiencyPct = efficiencyPct(d.efficiency),
            hrvMs = d.avgHrv,
            restingHr = d.restingHr,
            respRateBpm = d.respRateBpm,
            skinTempDevC = d.skinTempDevC,
            deepMin = d.deepMin,
            remMin = d.remMin,
            lightMin = d.lightMin,
            bedStartTs = longest?.effectiveStartTs,
            bedEndTs = longest?.endTs,
            heartRateOnly = d.sleepHrOnly == true,
        )
    }

    /**
     * Whether the newest night is last night. [logicalToday] is the core's key for today, which rolls
     * over at 04:00, so at 01:00 the night that ended the morning before still counts.
     */
    fun isLastNight(nightDay: String, logicalToday: String): Boolean = nightDay >= logicalToday

    /**
     * A night as an input to the WHOOP-style scores, or null when its key is not a date. Bed and wake
     * become minutes from that day's midnight where the night was slept, so an evening bedtime is negative.
     */
    fun input(night: Night): NightInput? {
        val day = date(night.day) ?: return null
        val midnight = day.atStartOfDay().toEpochSecond(night.record.offset)
        val sleep = night.record.sleep
        return NightInput(
            day = day,
            asleepMin = night.asleepMin,
            efficiencyPct = sleep.efficiencyPct,
            bedMinute = (sleep.bedStartTs - midnight) / 60.0,
            wakeMinute = (sleep.endTs - midnight) / 60.0,
            hrvMs = night.hrvMs?.takeIf { it > 0.0 },
            restingHr = night.restingHr?.toDouble(),
        )
    }

    /** "7h 12m", "45m", or a dash when there is no value. */
    fun duration(min: Double?): String {
        if (min == null) return DASH
        val total = min.roundToInt().coerceAtLeast(0)
        val h = total / 60
        val m = total % 60
        return if (h == 0) "${m}m" else "${h}h ${m}m"
    }

    fun durationSec(sec: Long): String = duration(sec / 60.0)

    /** A whole number with a unit, or a dash. */
    fun whole(value: Double?, unit: String = ""): String =
        if (value == null) DASH else "${value.roundToInt()}$unit"

    /** A signed one-decimal number such as "+0.3" or "-0.4", or a dash. */
    fun signed1(value: Double?, unit: String = ""): String =
        if (value == null) DASH else "%+.1f".format(value) + unit

    /** Whether [day] parses as a calendar date. Stored keys are ISO dates; anything else is not shown as one. */
    fun date(day: String): LocalDate? = runCatching { LocalDate.parse(day) }.getOrNull()

    /** A clock time in the phone's own style, read at [offset]. */
    fun clock(ts: Long, offset: ZoneOffset, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochSecond(ts).atOffset(offset))

    /** "22:10 to 06:40". */
    fun span(fromTs: Long, toTs: Long, offset: ZoneOffset, locale: Locale = Locale.getDefault()): String =
        "${clock(fromTs, offset, locale)} to ${clock(toTs, offset, locale)}"

    /** A nap's label: its weekday and times, such as "Sun 14:15 to 15:40". */
    fun napLabel(nap: SleepRecord, locale: Locale = Locale.getDefault()): String {
        val day = DateTimeFormatter.ofPattern("EEE", locale).format(Instant.ofEpochSecond(nap.sleep.startTs).atOffset(nap.offset))
        return "$day ${span(nap.sleep.startTs, nap.sleep.endTs, nap.offset, locale)}"
    }

    const val DASH = "—"
}
