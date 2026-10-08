// Fork-owned. One sleep as the app keeps it, and the rule that says which sleep is a day's night.
//
// Pure Kotlin: no Android, no database, no clock.
package fork.app.scoring

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Heart figures for one sleep, worked out from the stored beats and heart rate. Null where there were too few. */
data class SleepVitals(
    /** Mean RMSSD of the five-minute windows across the sleep, in milliseconds. */
    val hrvMs: Double?,
    /** The same over the last [SleepVitalsCalc.LATE_HOURS] hours of the sleep. */
    val lateHrvMs: Double?,
    /** How many five-minute windows [hrvMs] is the mean of. */
    val hrvWindows: Int,
    /** The lowest five-minute mean heart rate in the sleep. */
    val restingHr: Int?,
    /** Seconds of deep sleep, by the original engine's stager run over this sleep. Null when it could not stage it. */
    val deepSec: Long? = null,
    /** Seconds of REM, likewise. Light sleep is the rest of the time asleep. */
    val remSec: Long? = null,
)

/** A sleep found in the strap's state, with its heart figures, as the app stores it. */
data class SleepRecord(
    val deviceId: String,
    val sleep: StrapSleep,
    /** Seconds east of UTC where the phone was when this was worked out. Its clock times and its day are read in it. */
    val offsetSec: Int,
    /** Null when the heart rows could not be read. Such a record is never [settled], so it is tried again. */
    val vitals: SleepVitals?,
    /** The newest second of strap state there was when this was worked out. */
    val dataThroughTs: Long,
) {
    val offset: ZoneOffset get() = ZoneOffset.ofTotalSeconds(offsetSec)

    /**
     * Whether the strap's data ran far enough past this sleep for nothing about it to change. The clock
     * starts where the run of "up" after the sleep ended: while the strap is still calling the wearer up,
     * it can go back to asleep and the sleep carries on. Three hours after that, any later sleep close
     * enough to be joined to this one is already in.
     */
    val settled: Boolean
        get() = vitals != null && dataThroughTs >= sleep.endTs + sleep.upAfterSec + SETTLED_AFTER_SEC

    /** Whether the data stops where the sleep does, before the strap had called the wearer awake: it may not be over. */
    val ongoing: Boolean
        get() = !sleep.wakeConfirmed && dataThroughTs - (sleep.endTs + sleep.upAfterSec) < ONGOING_WITHIN_SEC

    companion object {
        const val SETTLED_AFTER_SEC = 3 * 3600L
        const val ONGOING_WITHIN_SEC = 120L
    }
}

/** A day's night, and the other sleeps between the night before and it. */
data class SleepDay(val day: LocalDate, val night: SleepRecord, val naps: List<SleepRecord>)

/** Every night by day, oldest first, and the sleeps since the newest night. */
data class AssignedSleeps(val days: List<SleepDay>, val napsSince: List<SleepRecord>)

object SleepDays {

    /** A day's night window opens at this hour the evening before. */
    const val NIGHT_FROM_HOUR = 21

    /** And closes at this hour on the day. */
    const val NIGHT_UNTIL_HOUR = 12

    /** A sleep that ended longer than this before a night began is not listed with that night. */
    const val NAP_LOOKBACK_SEC = 24 * 3600L

    /**
     * The day whose night window the time in bed overlaps most, and the overlap in seconds. Null when
     * it overlaps none: a sleep wholly between noon and nine in the evening is never a night.
     */
    fun nightDay(bedStartTs: Long, endTs: Long, offset: ZoneOffset): Pair<LocalDate, Long>? {
        val endDay = Instant.ofEpochSecond(endTs).atOffset(offset).toLocalDate()
        return listOf(endDay, endDay.plusDays(1))
            .map { day ->
                val from = day.minusDays(1).atTime(LocalTime.of(NIGHT_FROM_HOUR, 0)).toEpochSecond(offset)
                val until = day.atTime(LocalTime.of(NIGHT_UNTIL_HOUR, 0)).toEpochSecond(offset)
                day to (minOf(endTs + 1, until) - maxOf(bedStartTs, from))
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
    }

    /**
     * Picks each day's night: of the sleeps that overlap the day's window, the one that overlaps it most
     * (the longer asleep on a tie). Every other sleep is a nap, listed with the first night that follows it.
     */
    fun assign(records: List<SleepRecord>): AssignedSleeps {
        val claims = records.mapNotNull { r ->
            nightDay(r.sleep.bedStartTs, r.sleep.endTs, r.offset)?.let { (day, overlap) -> Triple(day, overlap, r) }
        }
        val nights = claims.groupBy { it.first }
            .map { (day, list) -> day to list.maxWith(compareBy({ it.second }, { it.third.sleep.asleepSec })).third }
            .sortedBy { it.second.sleep.startTs }
        val chosen = nights.map { it.second.sleep.startTs }.toSet()
        val others = records.filter { it.sleep.startTs !in chosen }.sortedBy { it.sleep.startTs }

        val days = nights.mapIndexed { i, (day, night) ->
            val after = maxOf(
                nights.getOrNull(i - 1)?.second?.sleep?.endTs ?: Long.MIN_VALUE,
                night.sleep.startTs - NAP_LOOKBACK_SEC,
            )
            SleepDay(day, night, others.filter { it.sleep.endTs > after && it.sleep.endTs < night.sleep.startTs })
        }
        val lastEnd = nights.lastOrNull()?.second?.sleep?.endTs
        return AssignedSleeps(days, if (lastEnd == null) others else others.filter { it.sleep.startTs > lastEnd })
    }
}
