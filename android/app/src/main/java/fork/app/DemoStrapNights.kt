// Fork-owned. Demo flavor only: gives the synthetic dataset the raw strap rows the "Last night" screen is
// built from, so the screen can be looked at in an emulator, where there is no strap.
//
// Upstream's DemoSeeder writes stored days and sleep sessions and no raw rows. This adds, for the last
// few days, the strap's state every second, a heart rate every second and a beat every second. All of it
// is made up. It runs only when BuildConfig.ENABLE_DEMO is set, in a package of its own with its own data.
package fork.app

import android.content.Context
import com.lhoop.data.HrSample
import com.lhoop.data.RrInterval
import com.lhoop.data.SleepStateSampleEntity
import com.lhoop.data.WhoopDatabase
import com.lhoop.protocol.RrSourceChannel
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

internal object DemoStrapNights {

    /** The id upstream's DemoSeeder writes its synthetic strap under (data/DemoSeeder.kt `WHOOP`). */
    private const val DEVICE = "my-whoop"
    private const val NIGHTS = 9
    private const val CHUNK = 5_000

    private const val AWAKE = 0
    private const val STILL = 1
    private const val ASLEEP = 2
    private const val UP = 3

    /** Every made-up sleep ends the way a real one does: the strap's "up", then a while awake. */
    private val GOT_UP = arrayOf(UP to 630, AWAKE to 900)

    suspend fun seedIfEmpty(context: Context, nowSec: Long = System.currentTimeMillis() / 1000L) {
        val dao = WhoopDatabase.get(context).whoopDao()
        if (dao.countSleepState() > 0) return
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val rng = Random(20261006)
        val state = ArrayList<SleepStateSampleEntity>()
        val hr = ArrayList<HrSample>()
        val rr = ArrayList<RrInterval>()

        suspend fun flush(force: Boolean = false) {
            if (!force && state.size < CHUNK) return
            dao.insertSleepState(state); state.clear()
            dao.insertHr(hr); hr.clear()
            dao.insertRr(rr); rr.clear()
        }

        /** Plays [runs] of (state, seconds) out from [from]; stops at now. Returns where it ended. */
        suspend fun play(from: Long, restingBpm: Int, vararg runs: Pair<Int, Int>): Long {
            var ts = from
            for ((s, seconds) in runs) {
                repeat(seconds) { i ->
                    if (ts >= nowSec) return ts
                    val bpm = restingBpm + (if (s == ASLEEP || s == UP) 0 else 14) + ((ts / 900) % 5).toInt()
                    state += SleepStateSampleEntity(DEVICE, ts, s)
                    hr += HrSample(DEVICE, ts, bpm)
                    // One beat a second, alternating around the beat length, so every window has an RMSSD.
                    rr += RrInterval(
                        deviceId = DEVICE, ts = ts, rrMs = 930 + (if (i % 2 == 0) 0 else 24 + (ts / 3600 % 6).toInt() * 4),
                        srcChannel = RrSourceChannel.WHOOP5_HISTORICAL.code,
                    )
                    ts++
                    flush()
                }
            }
            return ts
        }

        for (back in NIGHTS - 1 downTo 0) {
            val day = today.minusDays(back.toLong())
            val bed = day.atStartOfDay(zone).toEpochSecond() - 50 * 60 + rng.nextInt(-40, 40) * 60L
            val resting = 54 + rng.nextInt(0, 6)
            when (back) {
                // A night with a long restless spell at the end, which counts as sleep until the wearer got up.
                3 -> play(bed, resting, STILL to 600, ASLEEP to 5 * 3600, UP to 90 * 60, *GOT_UP)
                // The newest night shows everything at once: an afternoon sleep with a restless spell in it the
                // day before, a short night in two parts with a restless end, and a sleep at lunchtime since.
                0 -> {
                    play(bed - 7 * 3600, resting, STILL to 480, ASLEEP to 3000, UP to 1500, ASLEEP to 5400, *GOT_UP)
                    play(bed + 3 * 3600, resting, STILL to 900, ASLEEP to 2 * 3600, *GOT_UP)
                    play(bed + 6 * 3600, resting, STILL to 600, ASLEEP to 2 * 3600, UP to 45 * 60, *GOT_UP)
                    play(day.atTime(13, 0).atZone(zone).toEpochSecond(), resting, STILL to 600, ASLEEP to 40 * 60, *GOT_UP)
                }
                // An ordinary night: up for a few minutes part-way through.
                else -> play(bed, resting, STILL to 600, ASLEEP to 4 * 3600, UP to 900, ASLEEP to 3 * 3600 + rng.nextInt(0, 3000), *GOT_UP)
            }
        }
        // The strap's "awake" a minute ago, so the data visibly runs past the last sleep.
        if (state.lastOrNull()?.ts?.let { it < nowSec - 60 } != false) state += SleepStateSampleEntity(DEVICE, nowSec - 60, 0)
        flush(force = true)
    }
}
