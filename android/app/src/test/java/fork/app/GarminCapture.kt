package fork.app

import java.io.File
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** The stage totals on one captured page of Garmin Connect's sleep screen, in minutes. */
internal data class GarminNight(val day: LocalDate, val deepMin: Int, val lightMin: Int, val remMin: Int, val awakeMin: Int?) {
    val asleepMin: Int get() = deepMin + lightMin + remMin
}

/**
 * Reads the text `fork/tools/capture_garmin.sh` saves from the Garmin's sleep page, so a replayed night
 * can be printed beside the second device's. Test-side only: the app never reads another app's data.
 *
 * The page gives its stages as lines such as `'Deep 1 hour 5 minutes'`, and names its night either as
 * `'Today'` or as a weekday and date with no year, which is then the capture's year.
 * `fork/tools/stage_whatif.py` reads the same text the same way.
 */
internal object GarminCapture {

    private val stageLine = Regex("""'(Deep|Light|REM|Awake) (?:(\d+) hours?)? ?(?:(\d+) minutes?)?'""")
    private val dayLine = Regex("""'(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday) (\d{1,2}) ([A-Z][a-z]+)'""")

    /** The night on one page's [text], or null when the page is not the stage view or does not say which night it is. */
    fun parse(text: String, capturedOn: LocalDate): GarminNight? {
        val minutes = stageLine.findAll(text).associate { m ->
            m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 0) * 60 + (m.groupValues[3].toIntOrNull() ?: 0)
        }
        val deep = minutes["Deep"] ?: return null
        val light = minutes["Light"] ?: return null
        val rem = minutes["REM"] ?: return null
        val named = dayLine.find(text)
        val day = when {
            named != null -> {
                val month = Month.entries.firstOrNull { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH) == named.groupValues[2] } ?: return null
                runCatching { LocalDate.of(capturedOn.year, month, named.groupValues[1].toInt()) }.getOrNull() ?: return null
            }
            "'Today'" in text -> capturedOn
            else -> return null
        }
        return GarminNight(day, deep, light, rem, minutes["Awake"])
    }

    /** Every night with a stage capture under [folder]`/<date>/`, by day. A later capture of the same night wins. */
    fun nights(folder: File): Map<LocalDate, GarminNight> {
        val found = LinkedHashMap<LocalDate, GarminNight>()
        folder.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { dir ->
            val capturedOn = runCatching { LocalDate.parse(dir.name) }.getOrNull() ?: return@forEach
            dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".txt") }.sortedBy { it.name }.forEach { file ->
                parse(file.readText(), capturedOn)?.let { found[it.day] = it }
            }
        }
        return found
    }
}
