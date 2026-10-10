package fork.app

import fork.app.scoring.NightInput
import fork.app.scoring.SleepRecord
import fork.app.scoring.SleepVitals
import fork.app.scoring.StateMinute
import fork.app.scoring.StrapSleep
import fork.app.scoring.Stretch
import fork.app.scoring.WhoopStyleScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.sqrt

/** The rules behind what the screens say and draw. Every figure here is made up. */
class InsightsTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val day0: LocalDate = LocalDate.of(2026, 3, 10)

    private fun ts(d: LocalDate, h: Int, min: Int): Long = ZonedDateTime.of(d.year, d.monthValue, d.dayOfMonth, h, min, 0, 0, zone).toEpochSecond()

    private fun input(back: Long, hrv: Double?, rhr: Double?) = NightInput(day0.minusDays(back), 420.0, 90.0, -30.0, 420.0, hrv, rhr)

    private fun score(
        recovery: Double? = 64.0,
        sufficiency: Double? = 92.0,
        baseline: Int = 8,
        debt: Double = 0.0,
        credit: Double = 0.0,
        need: Double = 480.0,
    ) = WhoopStyleScore(day0, need, debt, credit, sufficiency, 70.0, 80.0, 0.4, 0.5, baseline, recovery)

    private fun sleep(bed: Long, start: Long, end: Long, asleep: Long = end - start + 1) =
        StrapSleep(bed, start, end, asleep, 0, 0, true, listOf(Stretch(start, end, asleep, 0)))

    /** In bed from [bedH]:[bedM] the evening before [day] to [wakeH]:[wakeM], asleep from ten minutes in. Winter time, so the offset is zero. */
    private fun night(day: LocalDate, bedH: Int = 23, bedM: Int = 0, wakeH: Int = 7, wakeM: Int = 0, hrv: Double? = 40.0, rhr: Int? = 56, naps: List<SleepRecord> = emptyList()): Night {
        val bed = ts(day.minusDays(1), bedH, bedM)
        val end = ts(day, wakeH, wakeM) - 1
        return Night(day.toString(), SleepRecord("d", sleep(bed, bed + 600, end), 0, SleepVitals(hrv, null, 80, rhr), end + 86_400), naps, core = null)
    }

    private fun minute(m: Long, awake: Int = 0, still: Int = 0, asleep: Int = 0, up: Int = 0) = StateMinute(m, awake, still, asleep, up, null, null, null, null)

    // --- Against the usual ---------------------------------------------------------------------------

    @Test
    fun theUsualIsTheGeometricMeanForHrvAndThePlainMeanForRestingHeartRate() {
        val usual = Insights.usual(listOf(input(1, 36.0, 54.0), input(2, 49.0, 58.0), input(3, null, 59.0)))
        assertEquals(3, usual.nights)
        assertEquals(sqrt(36.0 * 49.0), usual.hrvMs!!, 1e-9)
        assertEquals(36.0, usual.hrvLow!!, 0.0)
        assertEquals(49.0, usual.hrvHigh!!, 0.0)
        assertEquals(57.0, usual.restingHr!!, 1e-9)
        assertEquals(54.0, usual.rhrLow!!, 0.0)
        assertEquals(59.0, usual.rhrHigh!!, 0.0)
        val none = Insights.usual(emptyList())
        assertEquals(0, none.nights)
        assertNull(none.hrvMs)
        assertNull(none.restingHr)
    }

    @Test
    fun aFigureIsSaidAgainstTheUsualInTheWholeNumbersOnScreen() {
        assertEquals("3 under usual", Insights.againstUsual(41.6, 45.2))
        assertEquals("1 over usual", Insights.againstUsual(56.0, 55.4))
        assertEquals("at your usual", Insights.againstUsual(55.4, 54.6))
        assertNull(Insights.againstUsual(null, 50.0))
        assertNull(Insights.againstUsual(50.0, null))
    }

    @Test
    fun theLineUnderRecoveryNamesHrvAndSleep() {
        val usual = Insights.usual(List(8) { input(it + 1L, 45.0, 55.0) })
        assertEquals("HRV is a little under your usual. Sleep was nearly enough.", Insights.reason(score(), 42.0, 56, usual))
        assertEquals("HRV is well above your usual. Sleep covered what you needed.", Insights.reason(score(sufficiency = 100.0), 53.0, 56, usual))
        assertEquals("HRV is about your usual. Sleep fell short of what you needed.", Insights.reason(score(sufficiency = 78.0), 45.5, 56, usual))
        assertEquals("HRV is well under your usual. Sleep fell well short of what you needed.", Insights.reason(score(sufficiency = 60.0), 30.0, 56, usual))
        assertEquals("HRV is a little above your usual. Sleep was nearly enough.", Insights.reason(score(), 48.0, 56, usual))
    }

    @Test
    fun withNoRecoveryTheLineSaysWhy() {
        val usual = Insights.usual(emptyList())
        assertEquals("No score: this night has no HRV or resting heart rate.", Insights.reason(score(recovery = null), null, 56, usual))
        assertEquals(
            "The score starts once there are 3 earlier nights with HRV. There are 2.",
            Insights.reason(score(recovery = null, baseline = 2), 42.0, 56, usual),
        )
        assertEquals(
            "The score starts once there are 3 earlier nights with HRV. There is 1.",
            Insights.reason(score(recovery = null, baseline = 1), 42.0, 56, usual),
        )
        assertEquals("No score for this night.", Insights.reason(score(recovery = null, baseline = 8), 42.0, 56, usual))
        assertEquals("No score for this night.", Insights.reason(null, 42.0, 56, usual))
    }

    @Test
    fun theSleepNeedIsSpeltOutFromItsPartsOnlyWhenItHasAny() {
        // The usual need and nothing else: the card has just said that figure, so it is not said twice.
        assertNull(Insights.needSum(480, score()))
        assertNull(Insights.needSum(480, score(debt = 0.4, credit = 0.9)))
        assertEquals("8h 0m usual + 25m debt", Insights.needSum(480, score(debt = 25.0)))
        assertEquals("8h 0m usual − 35m earlier sleep", Insights.needSum(480, score(credit = 35.0)))
        assertEquals("7h 30m usual + 25m debt − 35m earlier sleep", Insights.needSum(450, score(debt = 25.0, credit = 35.0)))
    }

    @Test
    fun aShortBaselineIsSaidUnderTheScoreUntilItIsFull() {
        fun usual(nights: Int) = Insights.usual((1..nights).map { input(it.toLong(), 40.0, 56.0) })
        assertEquals("Based on your last 4 nights. A full score uses 8.", Insights.baselineNote(64.0, usual(4)))
        assertEquals("Based on your last 7 nights. A full score uses 8.", Insights.baselineNote(64.0, usual(7)))
        assertNull(Insights.baselineNote(64.0, usual(8)))
        // No score, nothing to qualify: the line above already says why there is none.
        assertNull(Insights.baselineNote(null, usual(2)))
    }

    @Test
    fun sleepIsComparedWithTheSameWindowOfEarlierNights() {
        fun point(back: Long, pct: Double?) = TrendPoint(day0.minusDays(back), 420.0, 480.0, pct, 80.0, 60.0, 40.0, 56.0, -30.0, 420.0)
        val points = listOf(point(0, 99.0), point(1, 80.0), point(2, null), point(3, 100.0), point(14, 60.0), point(15, 10.0))
        val (low, high, mean) = Insights.sleepUsual(day0, points)!!
        assertEquals(60.0, low, 0.0)
        assertEquals(100.0, high, 0.0)
        assertEquals(80.0, mean, 1e-9)
        assertNull(Insights.sleepUsual(day0.minusDays(15), points))
    }

    // --- The strap ------------------------------------------------------------------------------------

    @Test
    fun thePillSaysWhatTheAppCanSeeOfTheStrap() {
        val now = ts(day0, 8, 0)
        fun status(connected: Boolean, service: Boolean, last: Long?) = Insights.strapStatus(connected, service, last, now, zone, Locale.UK)
        assertEquals(StrapStatus("Not connected", ok = false), status(false, true, now - 60))
        assertEquals(StrapStatus("Background recording off", ok = false), status(true, false, now - 60))
        assertEquals(StrapStatus("Connected", ok = true), status(true, true, null))
        assertEquals(StrapStatus("Synced 07:42", ok = true), status(true, true, ts(day0, 7, 42)))
        // Connected, but nothing has come in for over two hours.
        assertEquals(StrapStatus("Last synced 05:10", ok = false), status(true, true, ts(day0, 5, 10)))
        assertEquals(StrapStatus("Last synced yesterday", ok = false), status(true, true, ts(day0.minusDays(1), 22, 0)))
        assertEquals(StrapStatus("Last synced 7 Mar", ok = false), status(true, true, ts(day0.minusDays(3), 22, 0)))
    }

    // --- The night, drawn ----------------------------------------------------------------------------

    @Test
    fun theStripRunsFromGoingStillToTheEndInRunsOfOneState() {
        // In bed at minute 100, asleep from minute 103 to minute 111.
        val s = sleep(bed = 100 * 60L, start = 103 * 60L, end = 111 * 60L + 59)
        val minutes = listOf(
            minute(100, still = 60), minute(101, still = 60), minute(102, still = 40, asleep = 20),
            minute(103, asleep = 60), minute(104, asleep = 60), minute(105, asleep = 20, up = 40),
            minute(106, up = 60), minute(107, awake = 45, up = 15),
            // minute 108 has no data at all
            minute(109, asleep = 60), minute(110, asleep = 31, awake = 29), minute(111, asleep = 60),
        )
        assertEquals(
            listOf(
                StripRun(StripState.AWAKE, 3),      // still, before the first asleep second
                StripRun(StripState.ASLEEP, 2),
                StripRun(StripState.RESTLESS, 2),   // mostly "up"
                StripRun(StripState.AWAKE, 2),      // mostly awake, then the minute with no data
                StripRun(StripState.ASLEEP, 3),
            ),
            Insights.strip(s, minutes),
        )
        assertEquals(12, Insights.strip(s, minutes).sumOf { it.minutes })
    }

    @Test
    fun theHeartCurveIsTheMeanOfEachFewMinutesPlacedAlongTheTimeInBed() {
        val s = sleep(bed = 100 * 60L, start = 100 * 60L, end = 111 * 60L + 59) // twelve minutes
        val heart = listOf(100L to 60.0, 101L to 62.0, 102L to 64.0, 103L to 50.0, 106L to 70.0, 107L to 72.0, 200L to 99.0)
        val curve = Insights.heartCurve(s, heart, bucketMin = 3)
        assertEquals(3, curve.size)
        assertEquals(62.0, curve[0].second, 1e-9)
        assertEquals(1.5f / 12f, curve[0].first, 1e-6f)
        assertEquals(50.0, curve[1].second, 1e-9)
        assertEquals(71.0, curve[2].second, 1e-9)
        assertEquals(7.5f / 12f, curve[2].first, 1e-6f)
        assertTrue(Insights.heartCurve(s, emptyList()).isEmpty())
    }

    @Test
    fun aNightAndTheSleepBeforeItArePlacedBetweenNoonAndNoon() {
        val nap = night(day0.minusDays(1), bedH = 23, wakeH = 7).record.copy(sleep = sleep(ts(day0.minusDays(1), 15, 0), ts(day0.minusDays(1), 15, 0), ts(day0.minusDays(1), 18, 0) - 1))
        val spans = Insights.lastDay(night(day0, bedH = 0, bedM = 0, wakeH = 6, naps = listOf(nap)).copy(record = night(day0).record.copy(sleep = sleep(ts(day0, 0, 0), ts(day0, 0, 0), ts(day0, 6, 0) - 1))))
        assertEquals(2, spans.size)
        assertEquals(3f / 24f, spans[0].first, 1e-4f)      // 15:00 is three hours after noon
        assertEquals(6f / 24f, spans[0].second, 1e-4f)
        assertEquals(12f / 24f, spans[1].first, 1e-4f)     // midnight
        assertEquals(18f / 24f, spans[1].second, 1e-4f)
    }

    @Test
    fun aSleepThatRunsPastNoonIsCutOffAtTheEdge() {
        val late = night(day0).record.copy(sleep = sleep(ts(day0, 9, 0), ts(day0, 9, 0), ts(day0, 14, 0) - 1))
        val spans = Insights.lastDay(night(day0).copy(record = late))
        assertEquals(21f / 24f, spans.single().first, 1e-4f)
        assertEquals(1f, spans.single().second, 0f)
    }

    // --- Over time -----------------------------------------------------------------------------------

    @Test
    fun theWeekIsSevenDaysEndingOnTheGivenOneWithItsGaps() {
        val nights = listOf(night(day0), night(day0.minusDays(2), hrv = null), night(day0.minusDays(9)))
        val points = Insights.trend(nights, mapOf(day0.toString() to score()))
        assertEquals(listOf(day0.minusDays(9), day0.minusDays(2), day0), points.map { it.day })
        assertEquals(64.0, points.last().recovery!!, 0.0)
        assertNull(points[1].recovery)
        assertEquals(-60.0, points.last().bedMinute!!, 1e-9)
        assertEquals(420.0, points.last().wakeMinute!!, 1.0 / 60 + 1e-9)

        val week = Insights.week(points, day0)
        assertEquals((6L downTo 0L).map { day0.minusDays(it) }, week.map { it.first })
        assertEquals(listOf(false, false, false, false, true, false, true), week.map { it.second != null })
    }

    private fun point(back: Long, recovery: Double?, sleepScore: Double?, asleep: Double = 420.0, hrv: Double? = 40.0, rhr: Double? = 56.0) =
        TrendPoint(day0.minusDays(back), asleep, 480.0, 90.0, sleepScore, recovery, hrv, rhr, -30.0, 420.0)

    @Test
    fun progressNeedsEnoughNightsOnBothSides() {
        val few = Insights.progress((0L..2L).map { point(it, 60.0, 80.0) } + (7L..20L).map { point(it, 50.0, 70.0) }, day0)
        assertFalse(few.ready)
        assertEquals(3, few.recentNights)
        assertEquals(14, few.earlierNights)
        assertNull(few.recovery)
        assertNull(few.direction)

        val noHistory = Insights.progress((0L..6L).map { point(it, 60.0, 80.0) }, day0)
        assertFalse(noHistory.ready)
        assertEquals(7, noHistory.recentNights)
        assertEquals(0, noHistory.earlierNights)
    }

    @Test
    fun progressIsTheLastWeekAgainstTheFourBeforeIt() {
        val recent = (0L..6L).map { point(it, 66.0, 84.0, asleep = 450.0, hrv = 44.0, rhr = 54.0) }
        val earlier = (7L..34L).map { point(it, 60.0, 80.0, asleep = 420.0, hrv = 40.0, rhr = 56.0) }
        val tooOld = listOf(point(35, 1.0, 1.0), point(60, 1.0, 1.0))
        val p = Insights.progress(recent + earlier + tooOld, day0)
        assertTrue(p.ready)
        assertEquals(7, p.recentNights)
        assertEquals(28, p.earlierNights)
        assertEquals(6.0, p.recovery!!.delta, 1e-9)
        assertEquals(4.0, p.sleepScore!!.delta, 1e-9)
        assertEquals(30.0, p.asleepMin!!.delta, 1e-9)
        assertEquals(4.0, p.hrvMs!!.delta, 1e-9)
        assertEquals(-2.0, p.restingHr!!.delta, 1e-9)
        assertEquals(Direction.UP, p.direction)
    }

    @Test
    fun theDirectionIsRecoveryAndTheSleepScoreTakenTogether() {
        fun direction(recovery: Double, sleepScore: Double) = Insights.progress(
            (0L..6L).map { point(it, recovery, sleepScore) } + (7L..20L).map { point(it, 60.0, 80.0) }, day0,
        ).direction
        assertEquals(Direction.STEADY, direction(62.0, 81.0))
        assertEquals(Direction.DOWN, direction(54.0, 80.0))
        // One up and one down by the same amount is steady.
        assertEquals(Direction.STEADY, direction(70.0, 70.0))
    }

    @Test
    fun aFigureWithTooFewValuesIsLeftOutWhileTheOthersAreCompared() {
        val recent = (0L..6L).map { point(it, null, 84.0) }
        val earlier = (7L..20L).map { point(it, 60.0, 80.0) }
        val p = Insights.progress(recent + earlier, day0)
        assertTrue(p.ready)
        assertNull(p.recovery)
        assertEquals(4.0, p.sleepScore!!.delta, 1e-9)
        assertEquals(Direction.UP, p.direction)
    }
}
