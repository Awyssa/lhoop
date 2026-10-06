package fork.app.scoring

import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Test helpers: made-up strap state, written as runs of (state, seconds), turned into the minutes the app reads. */
internal object StateScript {

    const val AWAKE = 0
    const val STILL = 1
    const val ASLEEP = 2
    const val UP = 3

    /** A stretch with no rows at all, as when the strap is off the wrist. */
    const val NO_DATA = -1

    const val MIN = 60
    const val HOUR = 3600

    fun utc(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, min, s, 0, ZoneOffset.UTC).toEpochSecond()

    /** The per-minute rows for [runs] played out from [startTs], exactly as the app's SQL would return them. */
    fun minutes(startTs: Long, vararg runs: Pair<Int, Int>): List<StateMinute> {
        class Acc(var awake: Int = 0, var still: Int = 0, var asleep: Int = 0, var up: Int = 0, var firstStill: Long? = null,
                  var firstAsleep: Long? = null, var lastAsleep: Long? = null, var lastUp: Long? = null)
        val byMinute = sortedMapOf<Long, Acc>()
        var ts = startTs
        for ((state, seconds) in runs) {
            repeat(seconds) {
                if (state != NO_DATA) {
                    val a = byMinute.getOrPut(ts / 60) { Acc() }
                    when (state) {
                        AWAKE -> a.awake++
                        STILL -> { a.still++; if (a.firstStill == null) a.firstStill = ts }
                        ASLEEP -> { a.asleep++; if (a.firstAsleep == null) a.firstAsleep = ts; a.lastAsleep = ts }
                        else -> { a.up++; a.lastUp = ts }
                    }
                }
                ts++
            }
        }
        return byMinute.map { (minute, a) ->
            StateMinute(minute, a.awake, a.still, a.asleep, a.up, a.firstStill, a.firstAsleep, a.lastAsleep, a.lastUp)
        }
    }

    /** A sleep with one stretch and nothing else of note, for tests that are about something other than finding it. */
    fun sleep(
        startTs: Long,
        endTs: Long,
        bedStartTs: Long = startTs,
        upAfterSec: Long = 0L,
        wakeConfirmed: Boolean = false,
    ): StrapSleep {
        val asleep = endTs - startTs + 1
        return StrapSleep(
            bedStartTs = bedStartTs, startTs = startTs, endTs = endTs, asleepSec = asleep, restlessSec = 0L,
            upAfterSec = upAfterSec, wakeConfirmed = wakeConfirmed, stretches = listOf(Stretch(startTs, endTs, asleep, 0L)),
        )
    }

    fun record(
        sleep: StrapSleep,
        vitals: SleepVitals? = SleepVitals(hrvMs = 40.0, lateHrvMs = 50.0, hrvWindows = 60, restingHr = 55),
        dataThroughTs: Long = sleep.endTs + 12 * HOUR,
        offsetSec: Int = 0,
        deviceId: String = "strap",
    ) = SleepRecord(deviceId, sleep, offsetSec, vitals, dataThroughTs)
}
