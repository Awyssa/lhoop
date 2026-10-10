package fork.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * The hour checksum, against the cases the server's tests read too. The file is the one statement of
 * the rule: if this test and fork/server/backup/checksum_test.go both pass, the app and the server agree.
 */
class BackupChecksumTest {

    private val cases = File("../../fork/server/testdata/checksum_cases.json")

    private fun memory() = JdbcSql(DriverManager.getConnection("jdbc:sqlite::memory:"))

    private fun value(json: Any?): Any? = when (json) {
        null, JSONObject.NULL -> null
        is JSONObject -> json.getString("hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        is Int -> json.toLong()
        is Number -> if (json is Double || json is java.math.BigDecimal) json.toDouble() else json.toLong()
        else -> json
    }

    @Test
    fun theSharedCasesGiveTheChecksumsWrittenBesideThem() {
        assertTrue("run from the app module directory", cases.isFile)
        val all = JSONObject(cases.readText()).getJSONArray("cases")
        assertTrue(all.length() >= 4)
        for (i in 0 until all.length()) {
            val case = all.getJSONObject(i)
            val table = case.getString("table")
            memory().use { db ->
                db.exec(case.getString("create"))
                val rows = case.getJSONArray("rows")
                val width = rows.getJSONArray(0).length()
                db.insertAll(
                    "INSERT INTO `$table` VALUES (${List(width) { "?" }.joinToString(", ")})",
                    List(rows.length()) { r -> rows.getJSONArray(r).let { row: JSONArray -> List(width) { c -> value(row.opt(c)) } } },
                )
                val expected = case.getJSONObject("prints").let { prints -> prints.keys().asSequence().associateWith { prints.getString(it) } }
                val found = BackupChecksum.hourPrints(db, table, BackupChecksum.columns(db, table))
                    .mapKeys { (bucket, _) -> "${bucket.device}|${bucket.hour}" }
                assertEquals(case.getString("name"), expected, found)
            }
        }
    }

    @Test
    fun onlyHoursFromTheFloorOnAreLookedAt() {
        memory().use { db ->
            db.exec("CREATE TABLE t (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (deviceId, ts))")
            db.insertAll("INSERT INTO t VALUES ('A', ?, 1)", listOf(listOf(3599L), listOf(3600L), listOf(7300L)))
            val columns = BackupChecksum.columns(db, "t")
            assertEquals(setOf(Bucket("A", 0), Bucket("A", 1), Bucket("A", 2)), BackupChecksum.hourPrints(db, "t", columns).keys)
            assertEquals(setOf(Bucket("A", 1), Bucket("A", 2)), BackupChecksum.hourPrints(db, "t", columns, fromHour = 1).keys)
        }
    }

    @Test
    fun aTableIsASampleTableOnlyWithTheStrapAndTheSecondInItsKey() {
        memory().use { db ->
            db.exec("CREATE TABLE s (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, kind TEXT NOT NULL, PRIMARY KEY (deviceId, ts, kind))")
            db.exec("CREATE TABLE day (deviceId TEXT NOT NULL, day TEXT NOT NULL, ts INTEGER, PRIMARY KEY (deviceId, day))")
            db.exec("CREATE TABLE session (deviceId TEXT NOT NULL, startTs INTEGER NOT NULL, PRIMARY KEY (deviceId, startTs))")
            assertTrue(BackupChecksum.isSample(BackupChecksum.columns(db, "s")))
            assertFalse(BackupChecksum.isSample(BackupChecksum.columns(db, "day")))
            assertFalse(BackupChecksum.isSample(BackupChecksum.columns(db, "session")))
        }
    }

    @Test
    fun aColumnMayBeNullOnlyWhenItIsNeitherDeclaredNotNullNorPartOfTheKey() {
        memory().use { db ->
            db.exec("CREATE TABLE t (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, a INTEGER, b INTEGER NOT NULL, id INTEGER, PRIMARY KEY (deviceId, ts, id))")
            assertEquals(
                mapOf("deviceId" to false, "ts" to false, "a" to true, "b" to false, "id" to false),
                BackupChecksum.columns(db, "t").associate { it.name to it.nullable },
            )
        }
    }
}
