// Fork-owned. Hour checksums: how the phone and the server tell that they hold the same rows.
//
// Rows of a sample table are grouped by strap and by hour (ts / 3600). An hour's checksum is a list of
// whole numbers joined by commas: the row count, then for each column in table order, leaving out
// `deviceId`: the sum of an INTEGER column, or the summed length of a TEXT or BLOB column, and after
// that the count of its non-null values when the column may be null. REAL columns add no sum, because
// a sum of decimals depends on the order the rows are read in.
//
// The server has the same rule in Go (fork/server/backup/checksum.go). Both are tested against
// fork/server/testdata/checksum_cases.json, so a change here must be made there too.
package fork.app.backup

import java.util.Locale

/** One column of a table as the database declares it. */
internal data class BackupColumn(val name: String, val type: String, val nullable: Boolean, val key: Boolean)

/** One strap's one hour of a table: `ts / 3600`, as SQLite divides, which rounds toward zero. */
internal data class Bucket(val device: String, val hour: Long)

internal object BackupChecksum {

    const val HOUR_SEC = 3600L
    const val DEVICE = "deviceId"
    const val TS = "ts"

    /** An identifier as SQL. Names come from the database's own schema. */
    fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    fun columns(db: SqlReader, table: String): List<BackupColumn> =
        db.rows("PRAGMA table_info(${quote(table)})").map { row ->
            val key = (row[5] as Number).toLong() > 0
            BackupColumn(
                name = row[1] as String,
                type = (row[2] as? String).orEmpty().uppercase(Locale.ROOT),
                nullable = (row[3] as Number).toLong() == 0L && !key,
                key = key,
            )
        }

    /** A sample table has the strap and the second in its primary key. Everything else is a small table. */
    fun isSample(columns: List<BackupColumn>): Boolean {
        val keys = columns.filter { it.key }.map { it.name }
        return DEVICE in keys && TS in keys
    }

    fun expressions(columns: List<BackupColumn>): List<String> = buildList {
        add("count(*)")
        for (column in columns) {
            if (column.name == DEVICE) continue
            val name = quote(column.name)
            when (column.type) {
                "INTEGER" -> add("coalesce(sum($name), 0)")
                "TEXT", "BLOB" -> add("coalesce(sum(length($name)), 0)")
            }
            if (column.nullable) add("count($name)")
        }
    }

    /**
     * The checksum of every hour of [table] from [fromHour] on (all of them when null).
     *
     * [columns] are the table's columns as the app's database declares them. They are passed in, not
     * read from [db], because a delta file's tables carry the rows but not the declarations.
     */
    fun hourPrints(db: SqlReader, table: String, columns: List<BackupColumn>, fromHour: Long? = null): Map<Bucket, String> {
        val sql = buildString {
            append("SELECT ${quote(DEVICE)}, ${quote(TS)} / $HOUR_SEC, ${expressions(columns).joinToString(", ")} ")
            append("FROM ${quote(table)}")
            if (fromHour != null) append(" WHERE ${quote(TS)} >= ?")
            append(" GROUP BY 1, 2")
        }
        val args = if (fromHour != null) listOf(fromHour * HOUR_SEC) else emptyList()
        return db.rows(sql, args).associate { row ->
            Bucket(row[0] as String, (row[1] as Number).toLong()) to
                row.drop(2).joinToString(",") { (it as Number).toLong().toString() }
        }
    }
}
