// Fork-owned. Read-only row counts for the status screen: how many samples each signal table holds for
// one device id in the last 24 hours and the last 7 days.
//
// The windows are on `ts`, the sample's own wall-clock second (data/Entities.kt: "`ts` columns are
// wall-clock unix SECONDS"). No table records when a row was inserted, so "stored in the last 24 hours"
// can only mean "timestamped in the last 24 hours".
//
// No DAO is added and the database class is untouched. Heart rate and gravity use DAO queries that already
// exist; the rest is read-only SQL through Room's public RoomDatabase.query on the existing instance.
// Every table and column name below is taken from data/Entities.kt.
package fork.app

import com.lhoop.data.WhoopDatabase
import com.lhoop.protocol.RrSourceChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SignalCount(val label: String, val last24h: Long, val last7d: Long)

internal object SignalCounts {

    const val DAY_SECONDS = 86_400L

    /** Tables counted with [countSql]: label to `@Entity(tableName = ...)` in data/Entities.kt. */
    val PLAIN_TABLES: List<Pair<String, String>> = listOf(
        "Skin temperature" to "skinTempSample",
        "Sleep state" to "sleepStateSample",
        "PPG waveform" to "ppgWaveformSample",
        "Battery" to "battery",
    )

    /** Every counted table keys its rows on (deviceId, ts). Arguments: deviceId, from, to. */
    fun countSql(table: String): String =
        "SELECT COUNT(*) FROM $table WHERE deviceId = ? AND ts >= ? AND ts <= ?"

    /** `rrInterval.srcChannel` holds [RrSourceChannel.code], or NULL. Arguments: deviceId, from, to. */
    const val RR_BY_SOURCE_SQL: String =
        "SELECT srcChannel, COUNT(*) FROM rrInterval WHERE deviceId = ? AND ts >= ? AND ts <= ? " +
            "GROUP BY srcChannel"

    suspend fun load(
        db: WhoopDatabase,
        deviceId: String,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): List<SignalCount> = withContext(Dispatchers.IO) {
        val dayFrom = nowSec - DAY_SECONDS
        val weekFrom = nowSec - 7 * DAY_SECONDS
        val dao = db.whoopDao()
        buildList {
            add(
                SignalCount(
                    "Heart rate",
                    dao.countHrInWindow(deviceId, dayFrom, nowSec).toLong(),
                    dao.countHrInWindow(deviceId, weekFrom, nowSec).toLong(),
                ),
            )
            addAll(
                rrRows(
                    day = rrBySource(db, deviceId, dayFrom, nowSec),
                    week = rrBySource(db, deviceId, weekFrom, nowSec),
                ),
            )
            add(
                SignalCount(
                    "Gravity",
                    dao.gravityWitnessInWindow(deviceId, dayFrom, nowSec).c.toLong(),
                    dao.gravityWitnessInWindow(deviceId, weekFrom, nowSec).c.toLong(),
                ),
            )
            for ((label, table) in PLAIN_TABLES) {
                add(
                    SignalCount(
                        label,
                        count(db, table, deviceId, dayFrom, nowSec),
                        count(db, table, deviceId, weekFrom, nowSec),
                    ),
                )
            }
        }
    }

    private fun count(db: WhoopDatabase, table: String, deviceId: String, from: Long, to: Long): Long =
        db.query(countSql(table), arrayOf<Any?>(deviceId, from, to)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private fun rrBySource(db: WhoopDatabase, deviceId: String, from: Long, to: Long): Map<Int?, Long> =
        db.query(RR_BY_SOURCE_SQL, arrayOf<Any?>(deviceId, from, to)).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(if (cursor.isNull(0)) null else cursor.getInt(0), cursor.getLong(1))
                }
            }
        }

    /**
     * One row per R-R source seen in either window, in source-code order with unlabelled rows last. A
     * store with no R-R at all still gets one row, so the signal never disappears from the screen.
     */
    fun rrRows(day: Map<Int?, Long>, week: Map<Int?, Long>): List<SignalCount> {
        val sources = (day.keys + week.keys).sortedWith(compareBy({ it == null }, { it ?: 0 }))
        if (sources.isEmpty()) return listOf(SignalCount("R-R intervals", 0L, 0L))
        return sources.map { code ->
            SignalCount("R-R intervals, ${rrSourceLabel(code)}", day[code] ?: 0L, week[code] ?: 0L)
        }
    }

    /** The source's own enum name; a NULL or unknown code is named as such rather than guessed at. */
    fun rrSourceLabel(code: Int?): String =
        RrSourceChannel.fromCode(code)?.name
            ?: if (code == null) "no source recorded" else "unknown source code $code"
}
