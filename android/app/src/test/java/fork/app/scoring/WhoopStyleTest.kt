package fork.app.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Pins the Kotlin scoring to the Python reference in fork/whoop-history/whoop_history.py (`model_scores`).
 * The expected values below were produced by that reference from the same made-up nights, so the two
 * implementations cannot drift apart unnoticed. None of the numbers come from a real person.
 */
class WhoopStyleTest {

    private data class Expected(
        val day: String,
        val needMin: Double,
        val debtInMin: Double,
        val sufficiency: Double?,
        val consistency: Double?,
        val sleepScore: Double?,
        val hrvComponent: Double?,
        val rhrComponent: Double?,
        val baselineNights: Int,
        val recovery: Double?,
    )

    private fun night(offset: Long, asleep: Double?, eff: Double?, bed: Double?, wake: Double?, hrv: Double?, rhr: Double?) =
        NightInput(LocalDate.of(2026, 1, 1).plusDays(offset), asleep, eff, bed, wake, hrv, rhr)

    private val nights = listOf(
        night(0, 432.0, 91.0, -40.0, 430.0, 52.0, 58.0),
        night(1, 380.0, 88.0, -10.0, 410.0, 47.0, 60.0),
        night(2, 455.0, 93.0, -55.0, 440.0, 58.0, 57.0),
        night(3, 300.0, 84.0, 60.0, 420.0, 39.0, 63.0),
        night(4, 495.0, 94.0, -90.0, 450.0, 61.0, 56.0),
        night(5, 420.0, 90.0, -30.0, 435.0, 50.0, 59.0),
        night(6, 250.0, 80.0, 120.0, 400.0, null, null),      // no HRV or resting heart rate
        night(7, 470.0, 92.0, -60.0, 445.0, 55.0, 58.0),
        night(8, 410.0, null, -20.0, 430.0, 49.0, 60.0),      // no efficiency: no sleep score, so no recovery
        // day 9 is missing: debt starts again
        night(10, 360.0, 87.0, 10.0, 415.0, 44.0, 62.0),
        night(11, 440.0, 91.5, -45.0, 438.0, 57.0, 57.0),
        night(12, null, null, null, null, 53.0, 58.0),        // vitals only
        night(13, 520.0, 95.0, -120.0, 460.0, 66.0, 54.0),
    )

    private val expected = listOf(
        Expected("2026-01-01", 450.000000, 0.000000, 96.000000, null, 81.810000, null, null, 0, null),
        Expected("2026-01-02", 462.313800, 12.313800, 82.195254, 78.616667, 81.964701, null, null, 1, null),
        Expected("2026-01-03", 501.634581, 51.634581, 90.703476, 78.616667, 88.830127, null, null, 2, null),
        Expected("2026-01-04", 480.723147, 30.723147, 62.405982, 72.102778, 65.623920, 0.002881, 0.218350, 3, 19.984661),
        Expected("2026-01-05", 547.655780, 97.655780, 90.385242, 71.358333, 86.329676, 0.913190, 0.720166, 4, 82.383261),
        Expected("2026-01-06", 484.409889, 34.409889, 86.703432, 76.383333, 84.658432, 0.466009, 0.486704, 5, 56.397297),
        Expected("2026-01-07", 491.422296, 41.422296, 50.872743, 64.658333, 54.406427, null, null, 6, null),
        Expected("2026-01-08", 567.510767, 117.510767, 82.817812, 73.033333, 81.421423, 0.697408, 0.555231, 6, 66.722993),
        Expected("2026-01-09", 509.858495, 59.858495, 80.414469, 75.266667, null, 0.382244, 0.415162, 7, null),
        Expected("2026-01-11", 450.000000, 0.000000, 80.000000, 76.104167, 79.386458, 0.145604, 0.301241, 8, 35.315874),
        Expected("2026-01-12", 505.845000, 55.845000, 86.983167, 78.002500, 85.784765, 0.815892, 0.653886, 8, 76.342324),
        Expected("2026-01-13", 492.261752, 42.261752, null, null, null, 0.593755, 0.566184, 8, null),
        Expected("2026-01-14", 492.261752, 42.261752, 100.000000, 69.013333, 92.104667, 0.967302, 0.803494, 8, 89.466549),
    )

    private fun assertClose(label: String, want: Double?, got: Double?) {
        if (want == null) assertNull(label, got) else assertEquals(label, want, got!!, 1e-3)
    }

    @Test
    fun theWholeModelMatchesThePythonReferenceNightByNight() {
        // Shuffled on purpose: the order the nights arrive in must not matter.
        val scored = WhoopStyle.score(nights.reversed(), habitualNeedMin = 450.0)
        assertEquals(expected.map { it.day }, scored.map { it.day.toString() })
        expected.zip(scored).forEach { (want, got) ->
            assertClose("${want.day} need", want.needMin, got.needMin)
            assertClose("${want.day} debt in", want.debtInMin, got.debtInMin)
            assertClose("${want.day} sufficiency", want.sufficiency, got.sufficiencyPct)
            assertClose("${want.day} consistency", want.consistency, got.consistencyPct)
            assertClose("${want.day} sleep score", want.sleepScore, got.sleepScore)
            assertClose("${want.day} hrv component", want.hrvComponent, got.hrvComponent)
            assertClose("${want.day} rhr component", want.rhrComponent, got.rhrComponent)
            assertEquals("${want.day} baseline nights", want.baselineNights, got.baselineNights)
            assertClose("${want.day} recovery", want.recovery, got.recovery)
        }
    }

    @Test
    fun debtIsZeroWithNoShortfallAndNeverPassesTheCap() {
        assertEquals(0.0, WhoopStyle.debtAfter(-60.0), 0.0)
        assertEquals(0.0, WhoopStyle.debtAfter(0.0), 0.0)
        assertEquals(20.205, WhoopStyle.debtAfter(30.0), 1e-6)
        assertEquals(71.28, WhoopStyle.debtAfter(120.0), 1e-6)
        assertEquals(117.12, WhoopStyle.debtAfter(240.0), 1e-6)
        assertEquals(WhoopStyle.DEBT_CAP_MIN, WhoopStyle.debtAfter(480.0), 1e-9)
    }

    @Test
    fun twoIdenticalNightsAgreeAllDayAndDisjointOnesOnlyWhileBothAreAwake() {
        assertEquals(1.0, WhoopStyle.sameStateShare(-40.0, 430.0, -40.0, 430.0), 1e-12)
        assertEquals(0.9652777777777778, WhoopStyle.sameStateShare(-40.0, 430.0, -10.0, 410.0), 1e-12)
        assertEquals(0.4583333333333333, WhoopStyle.sameStateShare(0.0, 480.0, 600.0, 900.0), 1e-12)
    }

    @Test
    fun theNormalCurveIsAccurateEnoughForAScore() {
        assertEquals(0.022750132, WhoopStyle.normalCdf(-2.0), 1e-6)
        assertEquals(0.158655254, WhoopStyle.normalCdf(-1.0), 1e-6)
        assertEquals(0.5, WhoopStyle.normalCdf(0.0), 1e-6)
        assertEquals(0.691462461, WhoopStyle.normalCdf(0.5), 1e-6)
        assertEquals(0.933192799, WhoopStyle.normalCdf(1.5), 1e-6)
    }

    @Test
    fun scoresStayInsideTheirRanges() {
        assertEquals(0.0, WhoopStyle.sleepScore(0.0, 0.0, 0.0), 0.0)
        assertEquals(100.0, WhoopStyle.sleepScore(100.0, 100.0, 100.0), 0.0)
        assertEquals(1.0, WhoopStyle.recovery(0.0, 0.0, 0.0), 0.0)
        // The best possible night scores 98.8: the fitted weights top out just under WHOOP's 99.
        assertEquals(98.8, WhoopStyle.recovery(1.0, 1.0, 100.0), 1e-9)
        assertEquals(0.0, WhoopStyle.consistency(0.2), 0.0)
        assertEquals(100.0, WhoopStyle.consistency(1.2), 0.0)
    }

    @Test
    fun aRunOfIdenticalHrvDoesNotTurnASmallChangeIntoAnExtremeScore() {
        // Eight identical earlier nights have no spread at all; the floor keeps the comparison finite.
        val component = WhoopStyle.hrvComponent(51.0, List(8) { 50.0 })
        assertEquals(WhoopStyle.normalCdf(kotlin.math.ln(51.0 / 50.0) / WhoopStyle.MIN_LN_HRV_SPREAD), component, 1e-9)
    }

    @Test
    fun onlyOneNightPerDayIsUsed() {
        val twice = nights + night(0, 100.0, 50.0, 0.0, 100.0, 20.0, 80.0)
        assertEquals(nights.size, WhoopStyle.score(twice, 450.0).size)
    }
}
