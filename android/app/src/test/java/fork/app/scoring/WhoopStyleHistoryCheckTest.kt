package fork.app.scoring

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

/**
 * Runs the Kotlin scoring over the owner's real WHOOP history and checks it does as well as the Python
 * reference did when the model was fitted. The history is private health data and is never in git, so
 * this test is SKIPPED wherever the export is absent (CI, any other machine). To create the export:
 *
 *     python3 fork/whoop-history/whoop_history.py --export-nights whoop-data/derived/nights.csv
 *
 * It prints only aggregate figures.
 */
class WhoopStyleHistoryCheckTest {

    private data class Row(val input: NightInput, val habitualNeedMin: Double, val whoopSleep: Double?, val whoopRecovery: Double?)

    private fun band(v: Double) = if (v >= 67) 2 else if (v >= 34) 1 else 0

    @Test
    fun theModelReproducesWhoopsScoresOnTheRealHistoryAboutAsWellAsWhenItWasFitted() {
        val file = File("../../whoop-data/derived/nights.csv")
        assumeTrue("no private WHOOP export on this machine", file.isFile)

        val lines = file.readLines().filter { it.isNotBlank() }
        val header = lines.first().split(",")
        fun col(name: String) = header.indexOf(name).also { check(it >= 0) { "missing column $name" } }
        val rows = lines.drop(1).map { line ->
            val c = line.split(",")
            fun num(name: String) = c[col(name)].toDoubleOrNull()
            Row(
                NightInput(
                    day = LocalDate.parse(c[col("date")]),
                    asleepMin = num("asleep_min"),
                    efficiencyPct = num("efficiency_pct"),
                    bedMinute = num("bed_minute"),
                    wakeMinute = num("wake_minute"),
                    hrvMs = num("hrv_ms"),
                    restingHr = num("resting_hr"),
                ),
                habitualNeedMin = num("habitual_need_min")!!,
                whoopSleep = num("whoop_sleep_score"),
                whoopRecovery = num("whoop_recovery"),
            )
        }
        val habitual = rows.map { it.habitualNeedMin }.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
        val scored = WhoopStyle.score(rows.map { it.input }, habitual).associateBy { it.day }

        val sleep = rows.mapNotNull { r -> scored[r.input.day]?.sleepScore?.let { s -> r.whoopSleep?.let { s to it } } }
        val recovery = rows.mapNotNull { r -> scored[r.input.day]?.recovery?.let { s -> r.whoopRecovery?.let { s to it } } }
        val sleepError = sleep.map { abs(it.first - it.second) }.average()
        val recoveryError = recovery.map { abs(it.first - it.second) }.average()
        val sameBand = recovery.count { band(it.first) == band(it.second) }.toDouble() / recovery.size
        val twoBandsApart = recovery.count { abs(band(it.first) - band(it.second)) == 2 }

        println(
            "WHOOP-style model on the real history: sleep score mean error %.1f (n %d); recovery mean error %.1f, same band %.0f%%, two bands apart %d (n %d)"
                .format(sleepError, sleep.size, recoveryError, 100 * sameBand, twoBandsApart, recovery.size),
        )
        // The Python reference measured 6.3, 7.8 and 80% when the model was fitted.
        assertTrue("sleep score error $sleepError", sleepError < 7.0)
        assertTrue("recovery error $recoveryError", recoveryError < 8.5)
        assertTrue("same band $sameBand", sameBand > 0.75)
    }
}
