package fork.app.backup

import fork.app.backup.MadeUpPhone.HOUR
import fork.app.backup.MadeUpPhone.STRAP
import fork.app.backup.MadeUpPhone.T0
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** What goes into a delta file. Every value is made up. */
class BackupDeltaTest {

    private lateinit var dir: File
    private lateinit var phone: JdbcSql
    private lateinit var delta: JdbcSql
    private val meta = mapOf("format" to "1", "user_version" to "41", "identity_hash" to MadeUpPhone.IDENTITY)
    private val hour0 = T0 / HOUR

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("lhoop-delta-test").toFile()
        phone = MadeUpPhone.create(File(dir, "phone.sqlite"))
        delta = JdbcSql(File(dir, "delta.sqlite"))
    }

    @After
    fun tearDown() {
        phone.close()
        delta.close()
        dir.deleteRecursively()
    }

    @Test
    fun theSchemaIsReadAsTheServerWillRunIt() {
        val request = BackupDelta.schemaRequest(phone)
        assertEquals(SchemaHead(41, MadeUpPhone.IDENTITY), request.head)
        assertEquals(1L, request.autoVacuum)
        assertEquals(9, request.statements.size)
        assertTrue(request.statements.dropLast(1).all { it.startsWith("CREATE TABLE") })
        assertTrue("indexes come after every table", request.statements.last().startsWith("CREATE INDEX"))
        val shape = BackupDelta.shapeOf(phone)
        assertEquals(setOf("hrSample", "rrInterval", "gravitySample", "event", "v18AuxSample"), shape.sample.toSet())
        assertEquals(setOf("dailyMetric", "room_master_table", "android_metadata"), shape.small.toSet())
    }

    @Test
    fun aDeltaHoldsTheHoursAskedForInFullAndNothingElse() {
        MadeUpPhone.wear(phone, T0, 3 * HOUR)
        val shape = BackupDelta.shapeOf(phone)
        val wanted = mapOf("hrSample" to listOf(Bucket(STRAP, hour0 + 1)), "rrInterval" to listOf(Bucket(STRAP, hour0), Bucket(STRAP, hour0 + 2)))
        val sent = BackupDelta.build(phone, delta, shape, wanted, emptyList(), meta)

        assertEquals(wanted.mapValues { it.value.toSet() }, sent.mapValues { it.value.keys })
        assertEquals(
            phone.rows("SELECT * FROM hrSample WHERE ts >= ? AND ts < ? ORDER BY ts", listOf(T0 + HOUR, T0 + 2 * HOUR)),
            delta.rows("SELECT deviceId, ts, bpm, synced FROM hrSample ORDER BY ts"),
        )
        assertEquals(listOf(listOf<Any?>(hour0), listOf<Any?>(hour0 + 2)), delta.rows("SELECT DISTINCT ts / 3600 FROM rrInterval ORDER BY 1"))
        val tables = delta.rows("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").map { it[0] }
        assertEquals(listOf("_lhoop_bucket", "_lhoop_delta", "_lhoop_small", "hrSample", "rrInterval"), tables)
        assertEquals(meta, delta.rows("SELECT key, value FROM _lhoop_delta").associate { it[0] as String to it[1] as String })
    }

    @Test
    fun theChecksumsListedAreThoseOfTheRowsInTheFile() {
        MadeUpPhone.wear(phone, T0, HOUR + 100)
        val shape = BackupDelta.shapeOf(phone)
        val all = shape.sample.associateWith { BackupChecksum.hourPrints(phone, it, shape.tables.getValue(it)) }
        val sent = BackupDelta.build(phone, delta, shape, all.mapValues { it.value.keys.toList() }, emptyList(), meta)
        assertEquals(all, sent)
        val listed = delta.rows("SELECT table_name, device_id, hour, print FROM _lhoop_bucket")
            .groupBy({ it[0] as String }, { Bucket(it[1] as String, it[2] as Long) to it[3] as String })
            .mapValues { it.value.toMap() }
        assertEquals(all, listed)
    }

    @Test
    fun everyValueArrivesAsItWasStored() {
        MadeUpPhone.wear(phone, T0, 700)
        phone.exec("UPDATE rrInterval SET tsSuspect = 1 WHERE ts = ${T0 + 7}")
        val shape = BackupDelta.shapeOf(phone)
        val all = shape.sample.associateWith { BackupChecksum.hourPrints(phone, it, shape.tables.getValue(it)).keys.toList() }
        BackupDelta.build(phone, delta, shape, all, shape.small, meta)
        for (table in shape.tables.keys) {
            val columns = shape.tables.getValue(table)
            val names = columns.joinToString(", ") { BackupChecksum.quote(it.name) }
            val kinds = columns.joinToString(", ") { "typeof(${BackupChecksum.quote(it.name)})" }
            // Each row with the kind SQLite stored each value as: a whole number must not come back a decimal.
            fun all(db: SqlReader) = db.rows("SELECT $names, $kinds FROM ${BackupChecksum.quote(table)} ORDER BY $names")
                .map { row -> row.map { if (it is ByteArray) it.toList() else it } }
            assertEquals(table, all(phone), all(delta))
        }
    }

    @Test
    fun theSmallTablesGoWholeAndAreListed() {
        MadeUpPhone.wear(phone, T0, 60)
        val shape = BackupDelta.shapeOf(phone)
        BackupDelta.build(phone, delta, shape, emptyMap(), shape.small, meta)
        assertEquals(shape.small.toSet(), delta.rows("SELECT table_name FROM _lhoop_small").map { it[0] }.toSet())
        assertEquals(listOf(listOf<Any?>(42L, MadeUpPhone.IDENTITY)), delta.rows("SELECT id, identity_hash FROM room_master_table"))
        assertEquals(listOf(listOf<Any?>("en_US")), delta.rows("SELECT locale FROM android_metadata"))
        assertEquals(listOf(listOf<Any?>(STRAP, "2030-03-01", 50L, 61.5)), delta.rows("SELECT deviceId, day, restingHr, hrv FROM dailyMetric"))
        assertTrue(delta.rows("SELECT * FROM _lhoop_bucket").isEmpty())
    }

    @Test
    fun anHourThatEmptiedBeforeItWasReadIsLeftOut() {
        MadeUpPhone.wear(phone, T0, 2 * HOUR)
        val shape = BackupDelta.shapeOf(phone)
        phone.exec("DELETE FROM v18AuxSample WHERE ts < ${T0 + HOUR}")
        val sent = BackupDelta.build(phone, delta, shape, mapOf("v18AuxSample" to listOf(Bucket(STRAP, hour0), Bucket(STRAP, hour0 + 1))), emptyList(), meta)
        assertEquals(setOf(Bucket(STRAP, hour0 + 1)), sent.getValue("v18AuxSample").keys)
        assertFalse(delta.rows("SELECT hour FROM _lhoop_bucket").any { it[0] == hour0 })
    }

    @Test
    fun twoStrapsInTheSameHourAreKeptApart() {
        MadeUpPhone.wear(phone, T0, 300, device = "STRAPONE")
        MadeUpPhone.wear(phone, T0, 300, device = "STRAPTWO")
        val shape = BackupDelta.shapeOf(phone)
        val sent = BackupDelta.build(phone, delta, shape, mapOf("hrSample" to listOf(Bucket("STRAPTWO", hour0))), emptyList(), meta)
        assertEquals(setOf(Bucket("STRAPTWO", hour0)), sent.getValue("hrSample").keys)
        assertEquals(listOf(listOf<Any?>("STRAPTWO", 300L)), delta.rows("SELECT deviceId, count(*) FROM hrSample GROUP BY 1"))
    }
}
