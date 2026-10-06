// Fork-owned. The app's own sleep and recovery scores, built to read the way WHOOP's did.
//
// Every rule and constant here was fitted on the owner's WHOOP history (240 nights) and is described,
// with how well it fits, in fork/docs/05-whoop-scoring-model.md. `fork/whoop-history/whoop_history.py`
// reproduces the fits. The model was checked on WHOOP's own nightly inputs. It has NOT yet been checked
// on nights measured by this app, so the screen labels it experimental.
//
// Pure Kotlin on purpose: no Android, no database, no clock. The caller passes the nights in.
package fork.app.scoring

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One night's measurements. [day] is the calendar day the night ended on. [bedMinute] and [wakeMinute]
 * are minutes from that day's local midnight, so a night that began the evening before has a negative
 * [bedMinute]. Anything not measured is null.
 */
data class NightInput(
    val day: LocalDate,
    val asleepMin: Double?,
    val efficiencyPct: Double?,
    val bedMinute: Double?,
    val wakeMinute: Double?,
    val hrvMs: Double?,
    val restingHr: Double?,
)

/** The scores for one night and every intermediate value they came from, so the screen can explain them. */
data class WhoopStyleScore(
    val day: LocalDate,
    /** Sleep needed tonight: the habitual need plus the debt carried in. */
    val needMin: Double,
    /** Debt carried into this night from the previous one. Zero after a night with no data. */
    val debtInMin: Double,
    /** Time asleep as a share of [needMin], capped at 100. Null without a time asleep. */
    val sufficiencyPct: Double?,
    /** How closely tonight's bed and wake times match the previous three nights. Null with none of them. */
    val consistencyPct: Double?,
    val sleepScore: Double?,
    /** Where tonight's HRV sits against the earlier nights, 0 to 1. */
    val hrvComponent: Double?,
    /** Where tonight's resting heart rate sits against the earlier nights, 0 to 1. Higher is better. */
    val rhrComponent: Double?,
    /** How many earlier nights the two components were compared against (at most [WhoopStyle.BASELINE_NIGHTS]). */
    val baselineNights: Int,
    val recovery: Double?,
)

object WhoopStyle {

    // --- Sleep need and debt -------------------------------------------------------------------------

    /** The most debt WHOOP ever carried in the history: 2.13 hours. */
    const val DEBT_CAP_MIN = 127.8

    /**
     * Debt left after a night, in minutes, from how far the night fell short of its need. Fitted to
     * WHOOP's `debt_post`: about two thirds of a small shortfall, a smaller share of a large one, capped.
     * It reproduces WHOOP's value to within 4 minutes on average.
     */
    fun debtAfter(shortfallMin: Double): Double {
        if (shortfallMin <= 0.0) return 0.0
        val h = shortfallMin / 60.0
        return min(DEBT_CAP_MIN, (0.70 * h - 0.053 * h * h) * 60.0).coerceAtLeast(0.0)
    }

    // --- Consistency ---------------------------------------------------------------------------------

    /** Tonight is compared with up to this many of the calendar nights just before it. */
    const val CONSISTENCY_NIGHTS = 3

    /**
     * The share of the 24 hours in which two nights agree on asleep or awake, 0 to 1, taking the time
     * from bed to wake as asleep. Two identical nights score 1.
     */
    fun sameStateShare(bedA: Double, wakeA: Double, bedB: Double, wakeB: Double): Double {
        val overlap = max(0.0, min(wakeA, wakeB) - max(bedA, bedB))
        val eitherAsleep = (wakeA - bedA) + (wakeB - bedB) - overlap
        return ((overlap + (MINUTES_PER_DAY - eitherAsleep)) / MINUTES_PER_DAY).coerceIn(0.0, 1.0)
    }

    /** WHOOP's 0 to 100 consistency from the mean [sameStateShare] against the earlier nights. */
    fun consistency(meanSameStateShare: Double): Double =
        (-76.6 + 160.8 * meanSameStateShare).coerceIn(0.0, 100.0)

    /** Used in the sleep score when no earlier night exists to compare bed times with. */
    const val NEUTRAL_CONSISTENCY = 50.0

    // --- Sleep score ---------------------------------------------------------------------------------

    /** Hours against need, efficiency and consistency, each 0 to 100. Stage composition does not enter. */
    fun sleepScore(sufficiencyPct: Double, efficiencyPct: Double, consistencyPct: Double): Double =
        (-21.8 + 0.66 * sufficiencyPct + 0.25 * efficiencyPct + 0.35 * consistencyPct).coerceIn(0.0, 100.0)

    // --- Recovery ------------------------------------------------------------------------------------

    /** HRV and resting heart rate are compared with up to this many earlier nights. */
    const val BASELINE_NIGHTS = 8

    /** Earlier nights older than this many days are not used as a baseline. */
    const val BASELINE_WINDOW_DAYS = 14L

    /** With fewer earlier nights than this there is no recovery score. */
    const val MIN_BASELINE_NIGHTS = 3

    /** A floor on the spread of ln(HRV), so a run of near-identical nights cannot make a small change look huge. */
    const val MIN_LN_HRV_SPREAD = 0.05

    /** One standard deviation of resting heart rate, in beats per minute, as WHOOP's component treats it. */
    const val RHR_SPREAD_BPM = 6.0

    /** Where tonight's HRV sits among the earlier nights' HRV, as a share from 0 to 1. Compared in logs. */
    fun hrvComponent(hrvMs: Double, earlierHrvMs: List<Double>): Double {
        val logs = earlierHrvMs.map { ln(it) }
        val mean = logs.average()
        val spread = max(sampleStandardDeviation(logs, mean), MIN_LN_HRV_SPREAD)
        return normalCdf((ln(hrvMs) - mean) / spread)
    }

    /** Where tonight's resting heart rate sits against the earlier nights' mean. Lower than usual is better. */
    fun rhrComponent(restingHr: Double, earlierRestingHr: List<Double>): Double =
        normalCdf(-(restingHr - earlierRestingHr.average()) / RHR_SPREAD_BPM)

    /** HRV first, then resting heart rate, then the sleep score. WHOOP reports 1 to 99. */
    fun recovery(hrvComponent: Double, rhrComponent: Double, sleepScore: Double): Double =
        (-18.4 + 46.3 * hrvComponent + 18.9 * rhrComponent + 0.52 * sleepScore).coerceIn(1.0, 99.0)

    // --- The whole thing -----------------------------------------------------------------------------

    /**
     * Scores every night in [nights]. They may arrive in any order and with days missing; at most one
     * night per day is used (the first given). [habitualNeedMin] is the wearer's usual sleep need.
     *
     * Not modelled, because the app does not measure them yet: extra need after a hard day, and credit
     * for naps. In the history those two moved the need by 39 minutes on average.
     */
    fun score(nights: List<NightInput>, habitualNeedMin: Double): List<WhoopStyleScore> {
        val byDay = LinkedHashMap<LocalDate, NightInput>()
        nights.sortedBy { it.day }.forEach { byDay.putIfAbsent(it.day, it) }
        val out = ArrayList<WhoopStyleScore>(byDay.size)
        var debt = 0.0
        var previousDay: LocalDate? = null
        for (night in byDay.values) {
            // Debt only carries from the night before. After a gap it starts again from nothing.
            if (previousDay != night.day.minusDays(1)) debt = 0.0
            val need = habitualNeedMin + debt
            val sufficiency = night.asleepMin?.let { (min(1.0, it / need) * 100.0).coerceAtLeast(0.0) }
            val consistency = consistencyFor(night, byDay)
            val sleep = if (sufficiency != null && night.efficiencyPct != null) {
                sleepScore(sufficiency, night.efficiencyPct, consistency ?: NEUTRAL_CONSISTENCY)
            } else null

            val earlier = baselineFor(night, byDay)
            val enough = earlier.size >= MIN_BASELINE_NIGHTS && night.hrvMs != null && night.restingHr != null
            val hrvC = if (enough) hrvComponent(night.hrvMs!!, earlier.map { it.hrvMs!! }) else null
            val rhrC = if (enough) rhrComponent(night.restingHr!!, earlier.map { it.restingHr!! }) else null
            val rec = if (hrvC != null && rhrC != null && sleep != null) recovery(hrvC, rhrC, sleep) else null

            out += WhoopStyleScore(
                day = night.day,
                needMin = need,
                debtInMin = debt,
                sufficiencyPct = sufficiency,
                consistencyPct = consistency,
                sleepScore = sleep,
                hrvComponent = hrvC,
                rhrComponent = rhrC,
                baselineNights = earlier.size,
                recovery = rec,
            )

            // A night with no time asleep recorded leaves the debt as it was: nothing is known about it.
            if (night.asleepMin != null) debt = debtAfter(need - night.asleepMin)
            previousDay = night.day
        }
        return out
    }

    private fun consistencyFor(night: NightInput, byDay: Map<LocalDate, NightInput>): Double? {
        val bed = night.bedMinute ?: return null
        val wake = night.wakeMinute ?: return null
        val shares = (1L..CONSISTENCY_NIGHTS).mapNotNull { back ->
            val p = byDay[night.day.minusDays(back)] ?: return@mapNotNull null
            val pBed = p.bedMinute ?: return@mapNotNull null
            val pWake = p.wakeMinute ?: return@mapNotNull null
            sameStateShare(bed, wake, pBed, pWake)
        }
        return if (shares.isEmpty()) null else consistency(shares.average())
    }

    /** Up to [BASELINE_NIGHTS] of the most recent earlier nights that have both HRV and resting heart rate. */
    private fun baselineFor(night: NightInput, byDay: Map<LocalDate, NightInput>): List<NightInput> {
        val found = ArrayList<NightInput>(BASELINE_NIGHTS)
        for (back in 1L..BASELINE_WINDOW_DAYS) {
            val p = byDay[night.day.minusDays(back)] ?: continue
            if (p.hrvMs != null && p.hrvMs > 0.0 && p.restingHr != null) found += p
            if (found.size == BASELINE_NIGHTS) break
        }
        return found
    }

    // --- Maths ---------------------------------------------------------------------------------------

    private const val MINUTES_PER_DAY = 1440.0

    private fun sampleStandardDeviation(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    /** The standard normal cumulative distribution. */
    fun normalCdf(z: Double): Double = 0.5 * (1.0 + erf(z / sqrt(2.0)))

    /** Abramowitz and Stegun 7.1.26; absolute error below 1.5e-7, far finer than a score needs. */
    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
        val y = 1.0 - poly * exp(-x * x)
        return if (x >= 0) y else -y
    }
}
