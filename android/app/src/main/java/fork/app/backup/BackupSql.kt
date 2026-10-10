// Fork-owned. The least the server backup needs from a database.
//
// The backup's rules (which rows make an hour, what its checksum is, what goes into a delta file) are
// written against these two interfaces and nothing Android, so the unit tests run them on the JVM
// against real SQLite files. On the phone the core's database is behind [SqlReader] only: there is no
// way to write to it from here, which is the rule in AGENTS.md made structural.
package fork.app.backup

import java.io.Closeable

internal interface SqlReader {
    /** Every row of a query. Each value is in SQLite's own kind: Long, Double, String, ByteArray or null. */
    fun rows(sql: String, args: List<Any?> = emptyList()): List<List<Any?>>
}

internal interface SqlWriter : SqlReader {
    fun exec(sql: String)

    /** Runs [sql] once for each of [rows], all in one transaction. */
    fun insertAll(sql: String, rows: List<List<Any?>>)
}

/** A delta file being written. Closing it leaves one whole file on disk and nothing beside it. */
internal interface DeltaSink : SqlWriter, Closeable
