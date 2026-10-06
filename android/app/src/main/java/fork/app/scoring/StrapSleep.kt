// Fork-owned. Finds sleeps in the strap's own record of whether the wearer was asleep.
//
// Why: the core's sleep detector was wrong on two of the first three nights on the strap (it counted a
// still hour before bed, and it merged a nap with the evening after it), while the strap's own state
// matched what the wearer reported each time. See fork/docs/04-sleep-recovery-engine.md.
//
// The strap reports one of four states every second: 0 awake, 1 still, 2 asleep, 3 up. "Up" begins with
// movement during a sleep and is the strap not yet knowing: it goes back to asleep after about ten
// minutes without movement, or to awake after about ten and a half minutes of sustained movement. The
// wearer has been asleep through two hours of it, and a second device put most of two shorter spells
// as sleep. So "up" counts as sleep, with one exception: when the strap goes on to call the wearer
// awake, the stretch of sustained movement that convinced it ([StrapSleeps.WAKE_CONFIRM_SEC]) does not.
// What this cannot see is someone lying awake and nearly still after waking: that reads as sleep.
//
// Pure Kotlin on purpose: no Android, no database, no clock. The caller passes the minutes in.
package fork.app.scoring

/**
 * One minute of the strap's state: how many of its seconds were awake, still, asleep and up, and the
 * first and last of some of them as unix times. A minute with no data is not given.
 */
data class StateMinute(
    /** Unix time divided by 60. */
    val minute: Long,
    val awakeSec: Int,
    val stillSec: Int,
    val asleepSec: Int,
    val upSec: Int,
    val firstStillTs: Long?,
    val firstAsleepTs: Long?,
    val lastAsleepTs: Long?,
    val lastUpTs: Long?,
)

/**
 * An unbroken run of sleep, from its first asleep second to its end. [asleepSec] is what counts as sleep
 * in it; [restlessSec] is the part of that the strap had as "up".
 */
data class Stretch(val startTs: Long, val endTs: Long, val asleepSec: Long, val restlessSec: Long)

/**
 * One sleep: a stretch, or several with less than [StrapSleeps.GROUP_GAP_SEC] awake between them.
 * Times are unix seconds.
 */
data class StrapSleep(
    /** When the strap went still before the sleep. Equal to [startTs] when it reported no stillness. */
    val bedStartTs: Long,
    /** The first second the strap called asleep. */
    val startTs: Long,
    /** The last second counted as sleep. */
    val endTs: Long,
    /** Seconds counted as sleep between [startTs] and [endTs]: asleep, and up as described above. */
    val asleepSec: Long,
    /** The part of [asleepSec] the strap had as "up": the wearer moved and it never called them awake. */
    val restlessSec: Long,
    /** Seconds of "up" straight after [endTs] that are not counted. */
    val upAfterSec: Long,
    /** Whether the strap called the wearer awake straight after the sleep. False when its data stops there. */
    val wakeConfirmed: Boolean,
    val stretches: List<Stretch>,
) {
    /** From going still to the end of the sleep. */
    val inBedSec: Long get() = endTs - bedStartTs + 1

    /** Everything between [startTs] and [endTs] that is not counted as sleep: awake between stretches, or no data. */
    val awakeSec: Long get() = (endTs - startTs + 1) - asleepSec

    /** Asleep as a share of the time in bed, 0 to 100. */
    val efficiencyPct: Double get() = if (inBedSec > 0) 100.0 * asleepSec / inBedSec else 0.0
}

object StrapSleeps {

    /** A break this long in the strap's asleep-or-up seconds (missing data, in practice) does not end a stretch. */
    const val BRIDGE_MIN = 5L

    /** A stretch with fewer seconds in state 2 than this is ignored. */
    const val MIN_STRETCH_ASLEEP_SEC = 20 * 60L

    /** Stretches with less than this between them are one sleep. */
    const val GROUP_GAP_SEC = 90 * 60L

    /** The time in bed starts at most this long before the first asleep second. */
    const val MAX_STILL_LEAD_MIN = 60L

    /** A minute counts as still when at least this many of its seconds were. */
    const val STILL_MINUTE_SEC = 30

    /**
     * How long the strap takes to call the wearer awake once they are up and moving. Three of the four
     * wakings recorded so far took exactly this; the fourth took three minutes more. Where "up" ends in
     * awake, the wearer is taken to have got up this long before.
     */
    const val WAKE_CONFIRM_SEC = 630L

    private class Found(val stretch: Stretch, val upAfterSec: Long, val wakeConfirmed: Boolean)

    /** Every sleep in [minutes], oldest first. The minutes may arrive in any order. */
    fun find(minutes: List<StateMinute>): List<StrapSleep> {
        val sorted = minutes.sortedBy { it.minute }
        val byMinute = sorted.associateBy { it.minute }
        val found = runs(sorted).mapNotNull { stretch(it, byMinute) }

        val sleeps = ArrayList<StrapSleep>()
        var group = ArrayList<Found>()
        fun close() {
            if (group.isEmpty()) return
            val first = group.first().stretch
            val last = group.last()
            sleeps += StrapSleep(
                bedStartTs = bedStart(first.startTs, byMinute),
                startTs = first.startTs,
                endTs = last.stretch.endTs,
                asleepSec = group.sumOf { it.stretch.asleepSec },
                restlessSec = group.sumOf { it.stretch.restlessSec },
                upAfterSec = last.upAfterSec,
                wakeConfirmed = last.wakeConfirmed,
                stretches = group.map { it.stretch },
            )
            group = ArrayList()
        }
        for (item in found) {
            if (group.isNotEmpty() && item.stretch.startTs - group.last().stretch.endTs >= GROUP_GAP_SEC) close()
            group += item
        }
        close()
        return sleeps
    }

    /** Runs of minutes that hold asleep or up seconds, with breaks of up to [BRIDGE_MIN] minutes bridged. */
    private fun runs(sorted: List<StateMinute>): List<List<StateMinute>> {
        val out = ArrayList<List<StateMinute>>()
        var current = ArrayList<StateMinute>()
        for (m in sorted) {
            if (m.asleepSec + m.upSec <= 0) continue
            if (current.isNotEmpty() && m.minute - current.last().minute - 1 > BRIDGE_MIN) {
                out += current
                current = ArrayList()
            }
            current += m
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun stretch(run: List<StateMinute>, byMinute: Map<Long, StateMinute>): Found? {
        val start = run.firstNotNullOfOrNull { it.firstAsleepTs } ?: return null
        val lastAsleep = run.mapNotNull { it.lastAsleepTs }.maxOrNull() ?: return null
        val inState2 = run.sumOf { it.asleepSec.toLong() }
        if (inState2 < MIN_STRETCH_ASLEEP_SEC) return null

        // The "up" seconds after the last asleep second, to the end of the run.
        val lastAsleepMinute = lastAsleep / 60
        val tailUp = minOf(
            run.firstOrNull { it.minute == lastAsleepMinute }?.upSec?.toLong() ?: 0L,
            59 - lastAsleep % 60,
        ) + run.filter { it.minute > lastAsleepMinute }.sumOf { it.upSec.toLong() }
        val runEnd = maxOf(lastAsleep, run.mapNotNull { it.lastUpTs }.maxOrNull() ?: lastAsleep)

        // Awake seconds in the run's last minute come after its asleep and up seconds; failing that, the next minute.
        val lastMinute = run.last()
        val wakeConfirmed = lastMinute.awakeSec > 0 || (byMinute[lastMinute.minute + 1]?.awakeSec ?: 0) > 0

        val end = if (wakeConfirmed && tailUp > 0) maxOf(lastAsleep, runEnd - WAKE_CONFIRM_SEC) else lastAsleep
        val countedTail = minOf(tailUp, end - lastAsleep)
        val restless = run.sumOf { it.upSec.toLong() } - tailUp + countedTail
        return Found(
            stretch = Stretch(start, end, asleepSec = inState2 + restless, restlessSec = restless),
            upAfterSec = tailUp - countedTail,
            wakeConfirmed = wakeConfirmed,
        )
    }

    /**
     * The start of the unbroken run of still minutes that leads into [startTs], reaching back at most
     * [MAX_STILL_LEAD_MIN] minutes. [startTs] itself when the strap was not still just before it.
     */
    private fun bedStart(startTs: Long, byMinute: Map<Long, StateMinute>): Long {
        val startMinute = startTs / 60
        var bed = byMinute[startMinute]?.firstStillTs?.takeIf { it < startTs } ?: startTs
        var k = startMinute - 1
        while (startMinute - k <= MAX_STILL_LEAD_MIN) {
            val m = byMinute[k] ?: break
            if (m.stillSec < STILL_MINUTE_SEC) break
            bed = m.firstStillTs ?: (k * 60)
            k--
        }
        return minOf(bed, startTs)
    }
}
