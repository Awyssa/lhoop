package fork.app

import com.lhoop.data.WhoopDatabase
import com.lhoop.protocol.RrSourceChannel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The status screen's raw SQL, run against the schema Room itself generates.
 *
 * [SignalCounts] reads four tables and the R-R source split with hand-written SQL instead of a DAO, so
 * nothing checks its table and column names at build time. This creates the real tables from Room's
 * exported schema JSON (the same export `SchemaOracleTest` reads) in an in-memory SQLite and runs the
 * exact strings the app runs. A renamed table or column fails here instead of on the phone.
 */
class SignalCountsTest {

    private val device = "whoop-TEST"
    private val other = "my-whoop"
    private val now = 1_800_000_000L
    private val dayFrom = now - SignalCounts.DAY_SECONDS
    private val weekFrom = now - 7 * SignalCounts.DAY_SECONDS

    /** `CREATE TABLE` statements for the current schema version, straight from Room's export. */
    private fun roomCreateStatements(): Map<String, String> {
        val location = System.getProperty("room.schemaLocation")
        assertNotNull("room.schemaLocation system property not set (see app/build.gradle.kts)", location)
        val file = File(File(location, "com.lhoop.data.WhoopDatabase"), "${WhoopDatabase.SCHEMA_VERSION}.json")
        assertTrue("Room schema export missing at $file", file.isFile)
        val entities = JSONObject(file.readText()).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).associate { i ->
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            table to e.getString("createSql").replace("\${TABLE_NAME}", table)
        }
    }

    private fun database(): Connection {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        val create = roomCreateStatements()
        db.createStatement().use { sql ->
            (SignalCounts.PLAIN_TABLES.map { it.second } + "rrInterval").forEach { table ->
                sql.execute(create.getValue(table))
            }
        }
        return db
    }

    private fun Connection.count(sql: String, deviceId: String, from: Long, to: Long): Long =
        prepareStatement(sql).use { st ->
            st.setString(1, deviceId); st.setLong(2, from); st.setLong(3, to)
            st.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }

    private fun Connection.rrBySource(deviceId: String, from: Long, to: Long): Map<Int?, Long> =
        prepareStatement(SignalCounts.RR_BY_SOURCE_SQL).use { st ->
            st.setString(1, deviceId); st.setLong(2, from); st.setLong(3, to)
            st.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val code = rows.getInt(1)
                        put(if (rows.wasNull()) null else code, rows.getLong(2))
                    }
                }
            }
        }

    @Test
    fun plainCountsSeeOnlyTheDeviceAndTheWindow() {
        database().use { db ->
            db.createStatement().use { sql ->
                // One row in the last day, one more in the last week, one too old, one in the future, and
                // one in the last day that belongs to a different device id.
                for ((id, ts) in listOf(device to now - 60, device to now - 3 * 86_400L,
                    device to now - 8 * 86_400L, device to now + 60, other to now - 60)) {
                    sql.executeUpdate("INSERT INTO skinTempSample(deviceId, ts, raw, synced) VALUES ('$id', $ts, 1, 0)")
                    sql.executeUpdate("INSERT INTO sleepStateSample(deviceId, ts, state) VALUES ('$id', $ts, 2)")
                    sql.executeUpdate("INSERT INTO ppgWaveformSample(deviceId, ts, samples) VALUES ('$id', $ts, x'0000')")
                    sql.executeUpdate("INSERT INTO battery(deviceId, ts, synced) VALUES ('$id', $ts, 0)")
                }
            }
            for ((_, table) in SignalCounts.PLAIN_TABLES) {
                assertEquals("$table, last day", 1L, db.count(SignalCounts.countSql(table), device, dayFrom, now))
                assertEquals("$table, last week", 2L, db.count(SignalCounts.countSql(table), device, weekFrom, now))
            }
        }
    }

    @Test
    fun rrIsSplitByItsStoredSourceChannel() {
        database().use { db ->
            db.createStatement().use { sql ->
                fun rr(id: String, ts: Long, rrMs: Int, src: Int?) = sql.executeUpdate(
                    "INSERT INTO rrInterval(deviceId, ts, rrMs, seq, synced, srcChannel) " +
                        "VALUES ('$id', $ts, $rrMs, 0, 0, ${src ?: "NULL"})",
                )
                rr(device, now - 10, 800, RrSourceChannel.WHOOP5_HISTORICAL.code)
                rr(device, now - 11, 810, RrSourceChannel.WHOOP5_HISTORICAL.code)
                rr(device, now - 12, 820, RrSourceChannel.WHOOP5_STANDARD.code)
                rr(device, now - 2 * 86_400L, 830, RrSourceChannel.WHOOP5_REALTIME.code)
                rr(device, now - 2 * 86_400L, 840, null)
                rr(other, now - 10, 850, RrSourceChannel.WHOOP5_HISTORICAL.code)
            }
            val day = db.rrBySource(device, dayFrom, now)
            val week = db.rrBySource(device, weekFrom, now)
            assertEquals(mapOf<Int?, Long>(5 to 2L, 7 to 1L), day)
            assertEquals(mapOf<Int?, Long>(5 to 2L, 6 to 1L, 7 to 1L, null to 1L), week)
            assertEquals(
                listOf(
                    SignalCount("R-R intervals, WHOOP5_HISTORICAL", 2L, 2L),
                    SignalCount("R-R intervals, WHOOP5_REALTIME", 0L, 1L),
                    SignalCount("R-R intervals, WHOOP5_STANDARD", 1L, 1L),
                    SignalCount("R-R intervals, no source recorded", 0L, 1L),
                ),
                SignalCounts.rrRows(day, week),
            )
        }
    }

    @Test
    fun aStoreWithNoRrStillShowsOneRow() {
        assertEquals(listOf(SignalCount("R-R intervals", 0L, 0L)), SignalCounts.rrRows(emptyMap(), emptyMap()))
    }

    @Test
    fun anUnknownSourceCodeIsNamedNotGuessed() {
        assertEquals("unknown source code 99", SignalCounts.rrSourceLabel(99))
        assertEquals("no source recorded", SignalCounts.rrSourceLabel(null))
        assertEquals("WHOOP5_REALTIME", SignalCounts.rrSourceLabel(RrSourceChannel.WHOOP5_REALTIME.code))
    }
}
