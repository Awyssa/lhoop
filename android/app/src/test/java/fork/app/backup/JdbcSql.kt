package fork.app.backup

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** A real SQLite file on the JVM, behind the interfaces the backup's rules are written against. */
internal class JdbcSql(private val conn: Connection) : DeltaSink {

    constructor(file: File) : this(DriverManager.getConnection("jdbc:sqlite:${file.path}"))

    override fun rows(sql: String, args: List<Any?>): List<List<Any?>> =
        conn.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeQuery().use { result ->
                val width = result.metaData.columnCount
                buildList {
                    while (result.next()) {
                        add(
                            List(width) { i ->
                                when (val value = result.getObject(i + 1)) {
                                    is Int -> value.toLong()
                                    is Float -> value.toDouble()
                                    else -> value
                                }
                            },
                        )
                    }
                }
            }
        }

    override fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    override fun insertAll(sql: String, rows: List<List<Any?>>) {
        if (rows.isEmpty()) return
        conn.autoCommit = false
        try {
            conn.prepareStatement(sql).use { statement ->
                for (row in rows) {
                    row.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            conn.commit()
        } finally {
            conn.autoCommit = true
        }
    }

    override fun close() = conn.close()
}

/**
 * A made-up phone database for the tests, shaped like the app's: whole numbers, flags that may be
 * null, decimals, text and blobs. Every value follows from the second it is stamped with.
 */
internal object MadeUpPhone {
    const val STRAP = "TESTSTRP"
    const val T0 = 1_900_000_800L   // an arbitrary hour boundary in 2030
    const val HOUR = 3600L
    const val IDENTITY = "made-up-identity-hash"

    private val statements = listOf(
        "CREATE TABLE `hrSample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `bpm` INTEGER NOT NULL, `synced` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
        "CREATE TABLE `rrInterval` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `rrMs` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `synced` INTEGER NOT NULL, `srcChannel` INTEGER, `ord` INTEGER, `tsSuspect` INTEGER, PRIMARY KEY(`deviceId`, `ts`, `rrMs`, `seq`))",
        "CREATE TABLE `gravitySample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `x` REAL NOT NULL, `y` REAL NOT NULL, `z` REAL NOT NULL, `synced` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
        "CREATE TABLE `event` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `kind` TEXT NOT NULL, `payloadJSON` TEXT NOT NULL, `synced` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`, `kind`))",
        "CREATE TABLE `v18AuxSample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `payload` BLOB NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
        "CREATE TABLE `dailyMetric` (`deviceId` TEXT NOT NULL, `day` TEXT NOT NULL, `restingHr` INTEGER, `hrv` REAL, PRIMARY KEY(`deviceId`, `day`))",
        "CREATE INDEX `index_event_kind` ON `event` (`kind`)",
        "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "CREATE TABLE android_metadata (locale TEXT)",
    )

    fun create(file: File): JdbcSql {
        val db = JdbcSql(file)
        db.exec("PRAGMA auto_vacuum = 1")
        statements.forEach(db::exec)
        db.exec("PRAGMA user_version = 41")
        db.exec("INSERT INTO room_master_table VALUES (42, '$IDENTITY')")
        db.exec("INSERT INTO android_metadata VALUES ('en_US')")
        db.exec("INSERT INTO dailyMetric VALUES ('$STRAP', '2030-03-01', 50, 61.5)")
        return db
    }

    /** One row a second in the one-second tables, a beat on most seconds, an event every ten minutes. */
    fun wear(db: JdbcSql, start: Long, seconds: Long, device: String = STRAP) {
        val range = start until start + seconds
        db.insertAll("INSERT INTO hrSample VALUES (?, ?, ?, 1)", range.map { listOf(device, it, 50 + it % 23) })
        db.insertAll("INSERT INTO gravitySample VALUES (?, ?, ?, ?, ?, 1)", range.map { listOf(device, it, (it % 7) / 7.0, 0.1, -0.25) })
        db.insertAll("INSERT INTO v18AuxSample VALUES (?, ?, ?)", range.map { listOf(device, it, byteArrayOf((it % 120).toByte(), 3, 9)) })
        db.insertAll(
            "INSERT INTO rrInterval VALUES (?, ?, ?, 0, 1, ?, NULL, NULL)",
            range.filter { it % 5 != 0L }.map { listOf(device, it, 700 + it % 300, if (it % 2 == 1L) 7L else null) },
        )
        db.insertAll("INSERT INTO event VALUES (?, ?, 'DOUBLE_TAP(14)', '{}', 1)", range.filter { it % 600 == 0L }.map { listOf(device, it) })
    }

    /** Every row of [table] in key order, with blobs made comparable. */
    fun rowsOf(db: SqlReader, table: String): List<List<Any?>> {
        val keys = BackupChecksum.columns(db, table).filter { it.key }.joinToString(", ") { BackupChecksum.quote(it.name) }
        return db.rows("SELECT * FROM ${BackupChecksum.quote(table)} ORDER BY ${keys.ifEmpty { "rowid" }}")
            .map { row -> row.map { if (it is ByteArray) it.toList() else it } }
    }
}
