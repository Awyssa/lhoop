package fork.app.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
        val napCreditMin: Double,
        val sufficiency: Double?,
        val consistency: Double?,
        val sleepScore: Double?,
        val hrvComponent: Double?,
        val rhrComponent: Double?,
        val baselineNights: Int,
        val recovery: Double?,
    )

    private fun night(
        offset: Long,
        asleep: Double?,
        eff: Double?,
        bed: Double?,
        wake: Double?,
        hrv: Double?,
        rhr: Double?,
        naps: List<NapInput> = emptyList(),
    ) = NightInput(LocalDate.of(2026, 1, 1).plusDays(offset), asleep, eff, bed, wake, hrv, rhr, naps)

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
        Expected("2026-01-01", 450.000000, 0.000000, 0.000000, 96.000000, null, 81.810000, null, null, 0, null),
        Expected("2026-01-02", 462.313800, 12.313800, 0.000000, 82.195254, 78.616667, 81.964701, null, null, 1, null),
        Expected("2026-01-03", 501.634581, 51.634581, 0.000000, 90.703476, 78.616667, 88.830127, null, null, 2, null),
        Expected("2026-01-04", 480.723147, 30.723147, 0.000000, 62.405982, 72.102778, 65.623920, 0.002881, 0.218350, 3, 19.984661),
        Expected("2026-01-05", 547.655780, 97.655780, 0.000000, 90.385242, 71.358333, 86.329676, 0.913190, 0.720166, 4, 82.383261),
        Expected("2026-01-06", 484.409889, 34.409889, 0.000000, 86.703432, 76.383333, 84.658432, 0.466009, 0.486704, 5, 56.397297),
        Expected("2026-01-07", 491.422296, 41.422296, 0.000000, 50.872743, 64.658333, 54.406427, null, null, 6, null),
        Expected("2026-01-08", 567.510767, 117.510767, 0.000000, 82.817812, 73.033333, 81.421423, 0.697408, 0.555231, 6, 66.722993),
        Expected("2026-01-09", 509.858495, 59.858495, 0.000000, 80.414469, 75.266667, null, 0.382244, 0.415162, 7, null),
        Expected("2026-01-11", 450.000000, 0.000000, 0.000000, 80.000000, 76.104167, 79.386458, 0.145604, 0.301241, 8, 35.315874),
        Expected("2026-01-12", 505.845000, 55.845000, 0.000000, 86.983167, 78.002500, 85.784765, 0.815892, 0.653886, 8, 76.342324),
        Expected("2026-01-13", 492.261752, 42.261752, 0.000000, null, null, null, 0.593755, 0.566184, 8, null),
        Expected("2026-01-14", 492.261752, 42.261752, 0.000000, 100.000000, 69.013333, 92.104667, 0.967302, 0.803494, 8, 89.466549),
    )

    /** The same nights with other sleeps before five of them. Each nap is start, end, time asleep, in minutes. */
    private val napsBefore = mapOf(
        3L to listOf(NapInput(-600.0, -510.0, 80.0)),                                   // the afternoon before a short night
        7L to listOf(NapInput(-700.0, -650.0, 45.0), NapInput(-400.0, -340.0, 50.0)),   // two before one night
        10L to listOf(NapInput(-300.0, -200.0, 90.0)),                                  // before the first night after a gap
        12L to listOf(NapInput(-500.0, -440.0, 55.0)),                                  // before a night with no time asleep
        13L to listOf(NapInput(-1380.0, -1330.0, 40.0)),                                // over a day before the night's midnight
    )

    private val nightsWithNaps = nights.map { n ->
        n.copy(naps = napsBefore[ChronoUnit.DAYS.between(LocalDate.of(2026, 1, 1), n.day)].orEmpty())
    }

    private val expectedWithNaps = listOf(
        Expected("2026-01-01", 450.000000, 0.000000, 0.000000, 96.000000, null, 81.810000, null, null, 0, null),
        Expected("2026-01-02", 462.313800, 12.313800, 0.000000, 82.195254, 78.616667, 81.964701, null, null, 1, null),
        Expected("2026-01-03", 501.634581, 51.634581, 0.000000, 90.703476, 78.616667, 88.830127, null, null, 2, null),
        Expected("2026-01-04", 400.723147, 30.723147, 80.000000, 74.864655, 62.052778, 70.329144, 0.002881, 0.218350, 3, 22.431377),
        Expected("2026-01-05", 511.544652, 61.544652, 0.000000, 96.765746, 68.008333, 89.368309, 0.913190, 0.720166, 4, 83.963351),
        Expected("2026-01-06", 461.339465, 11.339465, 0.000000, 91.039252, 73.033333, 86.347573, 0.466009, 0.486704, 5, 57.275651),
        Expected("2026-01-07", 477.428052, 27.428052, 0.000000, 52.363911, 61.308333, 54.218098, null, null, 6, null),
        Expected("2026-01-08", 468.510528, 113.510528, 95.000000, 100.000000, 60.750000, 88.462500, 0.697408, 0.555231, 6, 70.384353),
        Expected("2026-01-09", 450.000000, 0.000000, 0.000000, 91.111111, 71.172222, null, 0.382244, 0.415162, 7, null),
        Expected("2026-01-11", 360.000000, 0.000000, 90.000000, 100.000000, 58.795833, 86.528542, 0.145604, 0.301241, 8, 39.029758),
        Expected("2026-01-12", 450.000000, 0.000000, 0.000000, 97.777778, 72.419167, 90.955042, 0.815892, 0.653886, 8, 79.030868),
        Expected("2026-01-13", 401.911667, 6.911667, 55.000000, null, null, null, 0.593755, 0.566184, 8, null),
        Expected("2026-01-14", 416.911667, 6.911667, 40.000000, 100.000000, 63.430000, 90.150500, 0.967302, 0.803494, 8, 88.450382),
    )

    private fun assertClose(label: String, want: Double?, got: Double?) {
        if (want == null) assertNull(label, got) else assertEquals(label, want, got!!, 1e-3)
    }

    @Test
    fun theWholeModelMatchesThePythonReferenceNightByNight() {
        // Shuffled on purpose: the order the nights arrive in must not matter.
        assertMatches(expected, WhoopStyle.score(nights.reversed(), habitualNeedMin = 450.0))
    }

    @Test
    fun theWholeModelMatchesThePythonReferenceWithNapsToo() {
        assertMatches(expectedWithNaps, WhoopStyle.score(nightsWithNaps.reversed(), habitualNeedMin = 450.0))
    }

    private fun assertMatches(expected: List<Expected>, scored: List<WhoopStyleScore>) {
        assertEquals(expected.map { it.day }, scored.map { it.day.toString() })
        expected.zip(scored).forEach { (want, got) ->
            assertClose("${want.day} need", want.needMin, got.needMin)
            assertClose("${want.day} debt in", want.debtInMin, got.debtInMin)
            assertClose("${want.day} nap credit", want.napCreditMin, got.napCreditMin)
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
    fun aNapTakesItsTimeAsleepOffTheNeedOfTheNightAfterIt() {
        val plain = WhoopStyle.score(listOf(night(0, 300.0, 90.0, 0.0, 330.0, null, null), night(1, 440.0, 90.0, -30.0, 430.0, null, null)), 480.0)
        val napped = WhoopStyle.score(
            listOf(
                night(0, 300.0, 90.0, 0.0, 330.0, null, null, naps = listOf(NapInput(-540.0, -420.0, 100.0))),
                night(1, 440.0, 90.0, -30.0, 430.0, null, null),
            ),
            480.0,
        )
        // The night after the nap needs 100 minutes less, so it falls less short and leaves less debt.
        assertEquals(480.0, plain[0].needMin, 1e-9)
        assertEquals(380.0, napped[0].needMin, 1e-9)
        assertEquals(100.0, napped[0].napCreditMin, 1e-9)
        assertEquals(WhoopStyle.debtAfter(180.0), plain[1].debtInMin, 1e-9)
        assertEquals(WhoopStyle.debtAfter(80.0), napped[1].debtInMin, 1e-9)
        // The credit is the first night's alone: the second has no nap of its own.
        assertEquals(0.0, napped[1].napCreditMin, 0.0)
    }

    @Test
    fun napCreditNeverTakesTheNeedBelowAnHour() {
        val scored = WhoopStyle.score(
            listOf(night(0, 30.0, 90.0, 0.0, 40.0, null, null, naps = listOf(NapInput(-700.0, -100.0, 560.0)))),
            480.0,
        ).single()
        assertEquals(WhoopStyle.MIN_NEED_MIN, scored.needMin, 0.0)
        assertEquals(50.0, scored.sufficiencyPct!!, 1e-9)
        // A nap with a negative time asleep, which no real sleep has, earns nothing rather than adding need.
        assertEquals(0.0, WhoopStyle.napCredit(listOf(NapInput(-100.0, -50.0, -30.0))), 0.0)
    }

    @Test
    fun aNapCountsAsAsleepWhenTwoDaysAreCompared() {
        val night = listOf(-40.0 to 430.0)
        // Two identical nights, one with a two-hour nap the afternoon before: they differ for those two hours.
        assertEquals(1.0 - 120.0 / 1440.0, WhoopStyle.sameStateShare(night, night + (-600.0 to -480.0)), 1e-12)
        // The same nap on both days: they agree all day again, whichever day's midnight it was counted from.
        assertEquals(1.0, WhoopStyle.sameStateShare(night + (-600.0 to -480.0), night + (-2040.0 to -1920.0)), 1e-12)
        // A nap at an hour the night already covers on the clock adds nothing.
        assertEquals(1.0, WhoopStyle.sameStateShare(listOf(0.0 to 420.0), listOf(0.0 to 420.0, -1380.0 to -1320.0)), 1e-12)
    }

    @Test
    fun daysAreComparedByTheClockSoASleepEndingBeforeMidnightMeetsOneBeginningBeforeIt() {
        // 23:20 to 07:10 against 23:30 to 23:59 on the day itself: both asleep for those 29 minutes.
        assertEquals(0.69375, WhoopStyle.sameStateShare(-40.0, 430.0, 1410.0, 1439.0), 1e-12)
    }

    @Test
    fun stretchesFoldOntoOneClockMergedAndInOrder() {
        assertEquals(
            listOf(0.0 to 500.0, 840.0 to 960.0, 1400.0 to 1440.0),
            WhoopStyle.clockSpans(listOf(-40.0 to 430.0, 400.0 to 500.0, -600.0 to -480.0, 10.0 to 5.0)),
        )
        assertEquals(listOf(0.0 to 1440.0), WhoopStyle.clockSpans(listOf(-100.0 to 1400.0)))
        assertEquals(emptyList<Pair<Double, Double>>(), WhoopStyle.clockSpans(emptyList()))
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
