// Fork-owned. One run of the server backup: ask what the server wants, send it, and check.
//
// The same steps as the server's own test client, fork/server/backup/client.go:
//   1. Work out the checksum of every hour in the window, and ask the server which it wants.
//   2. Send those hours, a day of them to a file. The small tables ride in the last file.
//   3. Ask again with the checksums of what was sent. Nothing wanted means it is all merged.
// A run can stop anywhere and the next one carries on, because the server's answer says what is missing.
package fork.app.backup

import fork.app.backup.BackupChecksum.HOUR_SEC
import fork.app.backup.BackupChecksum.TS
import fork.app.backup.BackupChecksum.quote
import java.io.File
import java.util.zip.GZIPOutputStream

internal data class BackupReport(
    val schemaCreated: Boolean,
    val hoursWanted: Int,
    val files: Int,
    val bytesSent: Long,
    /** The server holds every hour this run looked at. */
    val verified: Boolean,
)

internal class BackupRun(
    private val core: SqlReader,
    private val server: BackupServer,
    private val workDir: File,
    private val openDelta: (File) -> DeltaSink,
) {

    /**
     * @param days look at the last this many days of the sample tables, or at all of them when null
     * @param meta what the delta files say about the app: `app_build`, `app_version`, and `settings_json` when there is one
     */
    fun run(days: Int?, meta: Map<String, String>): BackupReport {
        val shape = BackupDelta.shapeOf(core)
        val head = BackupDelta.headOf(core)
        val floor = days?.let { newestHour(shape)?.minus(it * 24L) }
        val prints = LinkedHashMap<String, MutableMap<Bucket, String>>()
        for (table in shape.sample) {
            val found = BackupChecksum.hourPrints(core, table, shape.tables.getValue(table), floor)
            if (found.isNotEmpty()) prints[table] = found.toMutableMap()
        }
        // A phone that has recorded nothing has nothing to send, and no question to ask the server.
        if (prints.isEmpty()) return BackupReport(schemaCreated = false, hoursWanted = 0, files = 0, bytesSent = 0, verified = true)

        var schemaCreated = false
        var want = plan(head, prints)
        if (want == null) {
            server.putSchema(BackupDelta.schemaRequest(core))
            schemaCreated = true
            want = plan(head, prints) ?: throw ServerSaidNo(409, "the server has no copy after being sent the schema")
        }
        val hoursWanted = want.values.sumOf { it.size }

        // A day of hours to a file. With nothing wanted there is still one file, for the small tables.
        val byDay = want.flatMap { (table, hours) -> hours.map { Math.floorDiv(it.hour, HOURS_PER_FILE) to (table to it) } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
        val files: List<Map<String, List<Bucket>>> =
            byDay.values.map { pairs -> pairs.groupBy({ it.first }, { it.second }) }.ifEmpty { listOf(emptyMap()) }
        val fileMeta = mapOf(
            "format" to BackupDelta.FORMAT.toString(),
            "user_version" to head.userVersion.toString(),
            "identity_hash" to head.identityHash,
        ) + meta

        var bytesSent = 0L
        workDir.mkdirs()
        for ((index, hours) in files.withIndex()) {
            val plain = File(workDir, "delta-$index.sqlite")
            val packed = File(workDir, "delta-$index.sqlite.gz")
            try {
                plain.delete()
                val sent = openDelta(plain).use { sink ->
                    BackupDelta.build(core, sink, shape, hours, if (index == files.lastIndex) shape.small else emptyList(), fileMeta)
                }
                for ((table, found) in sent) prints.getValue(table).putAll(found)
                gzip(plain, packed)
                server.delta(packed)
                bytesSent += packed.length()
            } finally {
                plain.delete()
                packed.delete()
            }
        }

        // The same hours again, with the checksums of what was sent.
        val left = plan(head, prints)
        return BackupReport(schemaCreated, hoursWanted, files.size, bytesSent, verified = left != null && left.isEmpty())
    }

    /** The hours the server wants, by table. Null when the server has no copy yet. One request a table, so none grows with the years. */
    private fun plan(head: SchemaHead, prints: Map<String, Map<Bucket, String>>): Map<String, List<Bucket>>? {
        val want = LinkedHashMap<String, List<Bucket>>()
        for ((table, hours) in prints) {
            val answer = server.plan(head, table, hours)
            when (answer.schema) {
                PlanAnswer.OK -> if (answer.want.isNotEmpty()) want[table] = answer.want
                PlanAnswer.MISSING -> return null
                else -> throw ServerSaidNo(409, "the server's copy was made for another schema than this app's")
            }
        }
        return want
    }

    private fun newestHour(shape: BackupShape): Long? =
        shape.sample.mapNotNull { table ->
            (core.rows("SELECT max(${quote(TS)}) FROM ${quote(table)}").single()[0] as? Number)?.toLong()
        }.maxOrNull()?.let { it / HOUR_SEC }

    private fun gzip(from: File, to: File) {
        from.inputStream().use { input ->
            GZIPOutputStream(to.outputStream(), 1 shl 16).use { input.copyTo(it, 1 shl 16) }
        }
    }

    private companion object {
        const val HOURS_PER_FILE = 24L
    }
}
