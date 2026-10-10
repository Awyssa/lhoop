// Fork-owned. What the phone sends the backup server: its schema once, then delta files.
//
// A delta file is a small SQLite file. It holds the hours the server asked for, in full, in tables
// named as the app's are; the small tables whole; and three tables of its own that say what it
// carries. The server checks all of it before merging (fork/server/backup/replica.go). The server's
// own tests have a client that does the same in Go (fork/server/backup/client.go).
package fork.app.backup

import fork.app.backup.BackupChecksum.DEVICE
import fork.app.backup.BackupChecksum.HOUR_SEC
import fork.app.backup.BackupChecksum.TS
import fork.app.backup.BackupChecksum.quote

/** The tables of the app's database and their columns. */
internal data class BackupShape(val tables: Map<String, List<BackupColumn>>) {
    val sample: List<String> get() = tables.filterValues { BackupChecksum.isSample(it) }.keys.toList()
    val small: List<String> get() = tables.filterValues { !BackupChecksum.isSample(it) }.keys.toList()
}

/** Which schema this is: the database's version and Room's own mark of its tables. */
internal data class SchemaHead(val userVersion: Long, val identityHash: String)

/** Everything the server needs to make its empty copy. */
internal data class SchemaRequest(val head: SchemaHead, val pageSize: Long, val autoVacuum: Long, val statements: List<String>)

internal object BackupDelta {

    const val FORMAT = 1

    const val META = "_lhoop_delta"
    const val BUCKET = "_lhoop_bucket"
    const val SMALL = "_lhoop_small"

    private const val OBJECTS_SQL = "FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"

    fun shapeOf(db: SqlReader): BackupShape =
        BackupShape(
            db.rows("SELECT name $OBJECTS_SQL AND type = 'table' ORDER BY name")
                .map { it[0] as String }
                .associateWith { BackupChecksum.columns(db, it) },
        )

    fun headOf(db: SqlReader): SchemaHead =
        SchemaHead(
            userVersion = (db.rows("PRAGMA user_version").single()[0] as Number).toLong(),
            identityHash = db.rows("SELECT identity_hash FROM room_master_table").single()[0] as String,
        )

    /** The CREATE TABLE and CREATE INDEX statements of the database, tables first, as the server will run them. */
    fun schemaRequest(db: SqlReader): SchemaRequest =
        SchemaRequest(
            head = headOf(db),
            pageSize = (db.rows("PRAGMA page_size").single()[0] as Number).toLong(),
            autoVacuum = (db.rows("PRAGMA auto_vacuum").single()[0] as Number).toLong(),
            statements = db.rows(
                "SELECT sql $OBJECTS_SQL AND sql IS NOT NULL ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END, rowid",
            ).map { it[0] as String },
        )

    /**
     * Writes into [out] the [hours] of each sample table in full and the [small] tables whole, read
     * from [core]. Returns the checksum of every hour it wrote.
     *
     * The checksums are worked out from the rows in the file, not from the database a second time: the
     * database goes on changing while this runs, and what counts is that the file agrees with itself.
     * An hour that had no rows left by the time it was read is simply not in the result.
     */
    fun build(
        core: SqlReader,
        out: SqlWriter,
        shape: BackupShape,
        hours: Map<String, List<Bucket>>,
        small: List<String>,
        meta: Map<String, String>,
    ): Map<String, Map<Bucket, String>> {
        out.exec("CREATE TABLE $META (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        out.exec(
            "CREATE TABLE $BUCKET (table_name TEXT NOT NULL, device_id TEXT NOT NULL, hour INTEGER NOT NULL, " +
                "print TEXT NOT NULL, PRIMARY KEY (table_name, device_id, hour))",
        )
        out.exec("CREATE TABLE $SMALL (table_name TEXT PRIMARY KEY)")
        out.insertAll("INSERT INTO $META VALUES (?, ?)", meta.map { listOf(it.key, it.value) })

        val sent = LinkedHashMap<String, Map<Bucket, String>>()
        for ((table, wanted) in hours) {
            val columns = shape.tables.getValue(table)
            val insert = createLike(out, table, columns)
            val names = columns.joinToString(", ") { quote(it.name) }
            val oneHour = "SELECT $names FROM ${quote(table)} WHERE ${quote(DEVICE)} = ? " +
                "AND ${quote(TS)} > ? AND ${quote(TS)} < ? AND ${quote(TS)} / $HOUR_SEC = ?"
            for (bucket in wanted) {
                // The range lets SQLite use the key; the division is the rule, and settles the two ends.
                val rows = core.rows(oneHour, listOf(bucket.device, (bucket.hour - 1) * HOUR_SEC, (bucket.hour + 1) * HOUR_SEC, bucket.hour))
                out.insertAll(insert, rows)
            }
            val prints = BackupChecksum.hourPrints(out, table, columns)
            sent[table] = prints
            out.insertAll("INSERT INTO $BUCKET VALUES (?, ?, ?, ?)", prints.map { (bucket, print) -> listOf(table, bucket.device, bucket.hour, print) })
        }
        for (table in small) {
            val columns = shape.tables.getValue(table)
            val insert = createLike(out, table, columns)
            out.insertAll(insert, core.rows("SELECT ${columns.joinToString(", ") { quote(it.name) }} FROM ${quote(table)}"))
            out.insertAll("INSERT INTO $SMALL VALUES (?)", listOf(listOf(table)))
        }
        return sent
    }

    /**
     * Makes [table] in the delta file with the app's column names and no types, so every value is
     * stored exactly as it was read. Returns the INSERT for it.
     */
    private fun createLike(out: SqlWriter, table: String, columns: List<BackupColumn>): String {
        val names = columns.joinToString(", ") { quote(it.name) }
        out.exec("CREATE TABLE ${quote(table)} ($names)")
        return "INSERT INTO ${quote(table)} ($names) VALUES (${columns.joinToString(", ") { "?" }})"
    }
}
