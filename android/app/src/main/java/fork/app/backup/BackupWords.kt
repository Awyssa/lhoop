// Fork-owned. What the Strap tab says about the server backup.
package fork.app.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object BackupWords {

    /** After this long without a complete backup the screen says so. Two days: one missed night is not news. */
    const val STALE_MS = 2 * 86_400_000L

    /** The one line under "Server backup". */
    fun line(status: ServerBackup.Status, nowMs: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String = when {
        !status.setUp -> "not set up"
        status.running -> "running"
        status.lastOkAt == 0L -> "nothing sent yet"
        else -> "complete " + at(status.lastOkAt, nowMs, zone, locale)
    }

    /** A warning for under that line, or null: the last run's problem, or that no run has finished for two days. */
    fun warning(status: ServerBackup.Status, nowMs: Long): String? = when {
        !status.setUp || status.running -> null
        status.problem != null -> status.problem
        status.lastOkAt != 0L && nowMs - status.lastOkAt > STALE_MS -> "No complete backup for over two days."
        else -> null
    }

    /** "07:12" today, "Tue 07:12" within the week, "3 Oct 07:12" before that. */
    fun at(whenMs: Long, nowMs: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val then = Instant.ofEpochMilli(whenMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val pattern = when {
            then.toLocalDate() == today -> "HH:mm"
            then.toLocalDate().isAfter(today.minusDays(7)) -> "EEE HH:mm"
            else -> "d MMM HH:mm"
        }
        return then.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    /** What a refusal from the server means for the owner. */
    fun refusal(status: Int, message: String): String = when (status) {
        401 -> "The server did not accept the token."
        409 -> "The server's copy belongs to another version of the app's data."
        507 -> "The server is short of disk."
        in 500..599 -> "The server failed ($status). It will be tried again."
        else -> "The server refused the backup ($status): ${message.substringAfter(": ")}"
    }
}
