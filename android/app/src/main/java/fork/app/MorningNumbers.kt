// Fork-owned. The morning's numbers as the home-screen widget shows them, and the small file they wait in.
//
// The widget draws from this file and works nothing out itself, so it can be drawn when the app's
// process has only just been started to draw it. The file is written whenever the nights are worked out
// (NightsReader), from the same night and the same scores the home screen shows: one source, so the
// widget and the screen cannot disagree. Nothing here is the only copy of anything.
package fork.app

import org.json.JSONObject
import java.io.File

/**
 * The newest night's figures. [day] is the night's calendar day: the widget names the night by it and
 * never calls it "last night", because it can be drawn long after these were worked out. Deep and REM
 * are the stager's estimates and are marked as such wherever they are drawn. A null figure is one the
 * night does not have.
 */
internal data class MorningNumbers(
    val day: String,
    val recoveryPct: Int?,
    /** How many earlier nights the recovery score was compared with: the count the screens print. */
    val baselineNights: Int,
    /** The strap's data stopped where the sleep did, before it had called the wearer awake: it may not be over. */
    val ongoing: Boolean,
    val asleepMin: Int,
    val deepMin: Int?,
    val remMin: Int?,
    val hrvMs: Int?,
) {
    fun json(): String = JSONObject().apply {
        put("day", day)
        recoveryPct?.let { put("recovery", it) }
        put("baseline", baselineNights)
        put("ongoing", ongoing)
        put("asleep", asleepMin)
        deepMin?.let { put("deep", it) }
        remMin?.let { put("rem", it) }
        hrvMs?.let { put("hrv", it) }
    }.toString()

    companion object {
        /** The newest night of [state], or null when there is none to show. */
        fun from(state: NightsViewModel.State): MorningNumbers? {
            val night = state.nights.firstOrNull() ?: return null
            val score = state.whoopStyle[night.day]
            return MorningNumbers(
                day = night.day,
                recoveryPct = score?.recovery?.let(Math::round)?.toInt(),
                baselineNights = state.usual[night.day]?.nights ?: 0,
                ongoing = night.record.ongoing,
                asleepMin = Math.round(night.asleepMin).toInt(),
                deepMin = night.deepMin?.let(Math::round)?.toInt(),
                remMin = night.remMin?.let(Math::round)?.toInt(),
                hrvMs = night.hrvMs?.let(Math::round)?.toInt(),
            )
        }

        /** Null for anything that is not a file this wrote. */
        fun parse(text: String): MorningNumbers? = runCatching {
            val o = JSONObject(text)
            fun int(key: String): Int? = if (o.has(key)) o.getInt(key) else null
            MorningNumbers(
                day = o.getString("day"),
                recoveryPct = int("recovery"),
                baselineNights = o.getInt("baseline"),
                ongoing = o.getBoolean("ongoing"),
                asleepMin = o.getInt("asleep"),
                deepMin = int("deep"),
                remMin = int("rem"),
                hrvMs = int("hrv"),
            )
        }.getOrNull()
    }
}

/** The file the widget reads. Replaced in one step, like the stored sleeps. */
internal class MorningStore(private val file: File) {

    fun read(): MorningNumbers? = runCatching { if (file.isFile) MorningNumbers.parse(file.readText()) else null }.getOrNull()

    /** Writes [numbers], or removes the file when there is nothing to show. True when what is stored changed. */
    fun write(numbers: MorningNumbers?): Boolean {
        if (numbers == read()) return false
        if (numbers == null) return file.delete()
        file.parentFile?.mkdirs()
        val pending = File(file.path + ".tmp")
        pending.writeText(numbers.json())
        if (!pending.renameTo(file)) {
            file.delete()
            check(pending.renameTo(file)) { "could not replace ${file.name}" }
        }
        return true
    }

    companion object {
        fun fileIn(filesDir: File): File = File(filesDir, "fork/morning.json")
    }
}
