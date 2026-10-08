// Fork-owned. Works out the wearer's sleeps from the strap's own state, and keeps them.
//
// It reads the core's database and never writes to it. The strap's state comes straight from the
// `sleepStateSample` table through Room's public RoomDatabase.query, one row per minute. It does not use
// the per-session copy the core stores on a sleep session: that copy is read through a 100,000-row cap,
// which a strap worn round the clock overruns, leaving the newest hours missing or repeated
// (fork/docs/04-sleep-recovery-engine.md). Heart rate and beats come through the repository's own reads.
package fork.app

import com.lhoop.data.WhoopDatabase
import com.lhoop.data.WhoopRepository
import fork.app.scoring.SleepLog
import fork.app.scoring.SleepRecord
import fork.app.scoring.SleepStagesCalc
import fork.app.scoring.SleepVitals
import fork.app.scoring.SleepVitalsCalc
import fork.app.scoring.StateMinute
import fork.app.scoring.StrapSleep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

internal class StrapSleepLoader(
    private val db: WhoopDatabase,
    private val repository: WhoopRepository,
    private val store: SleepStore,
) {
    private val mutex = Mutex()
    private var cached: List<SleepRecord>? = null

    /**
     * The sleeps of the last [HISTORY_DAYS] days for [deviceId], oldest first.
     *
     * Stored records are brought up to date by [SleepLog.refresh] and written back when they changed.
     */
    suspend fun load(deviceId: String, nowSec: Long, zone: ZoneId): List<SleepRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = cached ?: store.read().also { cached = it }
                val mine = all.filter { it.deviceId == deviceId }.sortedBy { it.sleep.startTs }
                val floor = nowSec - HISTORY_DAYS * DAY_SEC
                val through = newestStateTs(deviceId, nowSec + FUTURE_MARGIN_SEC)
                    ?: return@withLock mine.filter { it.sleep.endTs >= floor }

                val now = SleepLog.refresh(
                    deviceId = deviceId,
                    stored = mine,
                    floor = floor,
                    through = through,
                    minutesFrom = { from -> minutes(deviceId, from, nowSec + FUTURE_MARGIN_SEC) },
                    vitalsOf = { sleep -> vitals(deviceId, sleep) },
                    offsetAt = { ts -> zone.rules.getOffset(Instant.ofEpochSecond(ts)).totalSeconds },
                )
                if (now != mine) {
                    val next = all.filter { it.deviceId != deviceId } + now
                    runCatching { store.write(next) }
                    cached = next
                }
                now.filter { it.sleep.endTs >= floor }
            }
        }

    private fun newestStateTs(deviceId: String, before: Long): Long? =
        db.query(NEWEST_SQL, arrayOf<Any?>(deviceId, before)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    private fun minutes(deviceId: String, from: Long, before: Long): List<StateMinute> =
        db.query(MINUTES_SQL, arrayOf<Any?>(deviceId, from, before)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        StateMinute(
                            minute = c.getLong(0),
                            awakeSec = c.getInt(1),
                            stillSec = c.getInt(2),
                            asleepSec = c.getInt(3),
                            upSec = c.getInt(4),
                            firstStillTs = if (c.isNull(5)) null else c.getLong(5),
                            firstAsleepTs = if (c.isNull(6)) null else c.getLong(6),
                            lastAsleepTs = if (c.isNull(7)) null else c.getLong(7),
                            lastUpTs = if (c.isNull(8)) null else c.getLong(8),
                        ),
                    )
                }
            }
        }

    /** Null when the rows could not be read, or came back at the cap and so may be cut short. */
    private suspend fun vitals(deviceId: String, sleep: StrapSleep): SleepVitals? = runCatching {
        val hr = repository.hrSamplesForDevice(deviceId, sleep.startTs, sleep.endTs, HR_ROW_CAP)
        val rr = repository.rrIntervalsForDevice(deviceId, sleep.startTs, sleep.endTs, RR_ROW_CAP)
        if (hr.size >= HR_ROW_CAP || rr.size >= RR_ROW_CAP) return@runCatching null
        val stages = stages(deviceId, sleep)
        SleepVitalsCalc.compute(sleep, hr, rr).copy(deepSec = stages?.deepSec, remSec = stages?.remSec)
    }.getOrNull()

    /** Deep and REM for a sleep, or null when its rows could not be read or staged. A failure here never costs the heart figures. */
    private suspend fun stages(deviceId: String, sleep: StrapSleep) = runCatching {
        val from = sleep.startTs - SleepStagesCalc.PAD_BEFORE_SEC
        val to = sleep.endTs + SleepStagesCalc.PAD_AFTER_SEC
        val grav = repository.gravitySamplesForDevice(deviceId, from, to, HR_ROW_CAP)
        val hr = repository.hrSamplesForDevice(deviceId, from, to, HR_ROW_CAP)
        val rr = repository.rrIntervalsForDevice(deviceId, from, to, RR_ROW_CAP)
        if (grav.size >= HR_ROW_CAP || hr.size >= HR_ROW_CAP || rr.size >= RR_ROW_CAP) null
        else SleepStagesCalc.compute(sleep, grav, hr, rr)
    }.getOrNull()

    /**
     * What the night screen draws for one sleep: the strap's state and the mean heart rate, minute by
     * minute, from when the wearer went still to the end of the sleep.
     */
    suspend fun detail(deviceId: String, sleep: StrapSleep): NightDetail = withContext(Dispatchers.IO) {
        val from = sleep.bedStartTs / 60 * 60
        val before = sleep.endTs + 1
        NightDetail(
            minutes = runCatching { minutes(deviceId, from, before) }.getOrDefault(emptyList()),
            heart = runCatching { heartByMinute(deviceId, from, before) }.getOrDefault(emptyList()),
        )
    }

    private fun heartByMinute(deviceId: String, from: Long, before: Long): List<Pair<Long, Double>> =
        db.query(HEART_BY_MINUTE_SQL, arrayOf<Any?>(deviceId, from, before)).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getDouble(1)) }
        }

    companion object {
        const val HISTORY_DAYS = 46L
        const val DAY_SEC = 86_400L

        /** Rows stamped later than this past now are a strap with a wrong clock, and are not read. */
        const val FUTURE_MARGIN_SEC = 300L

        /** Far above what one sleep can hold (a 24-hour sleep is 86,400 heart-rate rows), so a full read is never mistaken for a cut one. */
        const val HR_ROW_CAP = 200_000
        const val RR_ROW_CAP = 400_000

        /** Table and columns from data/Entities.kt (`HrSample`): the minute and its mean heart rate. Arguments: deviceId, from, before. */
        const val HEART_BY_MINUTE_SQL =
            "SELECT ts / 60, AVG(bpm) FROM hrSample WHERE deviceId = ? AND ts >= ? AND ts < ? " +
                "GROUP BY ts / 60 ORDER BY ts / 60"

        /** Table and columns from data/Entities.kt (`SleepStateSampleEntity`). Arguments: deviceId, before. */
        const val NEWEST_SQL = "SELECT MAX(ts) FROM sleepStateSample WHERE deviceId = ? AND ts < ?"

        /**
         * One row per minute that has data: seconds awake (0), still (1), asleep (2) and up (3), then the
         * first still, first asleep, last asleep and last up second. Arguments: deviceId, from, before.
         * Awake-only minutes are kept: one of them straight after a sleep is how a waking is told from a
         * gap in the data.
         */
        const val MINUTES_SQL =
            "SELECT ts / 60, " +
                "SUM(CASE WHEN state = 0 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN state = 1 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN state = 2 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN state = 3 THEN 1 ELSE 0 END), " +
                "MIN(CASE WHEN state = 1 THEN ts END), " +
                "MIN(CASE WHEN state = 2 THEN ts END), " +
                "MAX(CASE WHEN state = 2 THEN ts END), " +
                "MAX(CASE WHEN state = 3 THEN ts END) " +
                "FROM sleepStateSample WHERE deviceId = ? AND ts >= ? AND ts < ? " +
                "GROUP BY ts / 60 ORDER BY ts / 60"
    }
}
