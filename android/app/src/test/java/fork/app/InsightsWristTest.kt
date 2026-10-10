package fork.app

import fork.app.scoring.SleepRecord
import fork.app.scoring.SleepVitals
import fork.app.scoring.StrapSleep
import fork.app.scoring.Stretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** What the screens say about hours off the wrist. Every time here is made up. Winter, so the offset is zero. */
class InsightsWristTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val day0: LocalDate = LocalDate.of(2031, 3, 12)   // a Wednesday

    private fun ts(d: LocalDate, h: Int, min: Int = 0): Long = ZonedDateTime.of(d.year, d.monthValue, d.dayOfMonth, h, min, 0, 0, zone).toEpochSecond()

    private fun wrist(vararg off: OffWristSpan, through: Long = ts(day0, 9), seen: Boolean = true) =
        NightsViewModel.Wrist(off.toList(), through, seen)

    /** In bed 23:00 the evening before [day] to 07:00, asleep from ten minutes in. */
    private fun night(day: LocalDate): Night {
        val bed = ts(day.minusDays(1), 23)
        val end = ts(day, 7) - 1
        val sleep = StrapSleep(bed, bed + 600, end, end - bed - 599, 0, 0, true, listOf(Stretch(bed + 600, end, end - bed - 599, 0)))
        return Night(day.toString(), SleepRecord("d", sleep, 0, SleepVitals(40.0, null, 80, 56), end + 86_400), emptyList(), core = null)
    }

    @Test
    fun thePillSaysOffTheWristOnlyWhenNothingElseIsWrong() {
        val now = ts(day0, 8)
        fun status(connected: Boolean, service: Boolean, off: Boolean) =
            Insights.strapStatus(connected, service, ts(day0, 7, 42), now, zone, Locale.UK, offWrist = off)
        assertEquals(StrapStatus("Off the wrist", ok = false), status(true, true, true))
        assertEquals(StrapStatus("Synced 07:42", ok = true), status(true, true, false))
        assertEquals(StrapStatus("Not connected", ok = false), status(false, true, true))
        assertEquals(StrapStatus("Background recording off", ok = false), status(true, false, true))
    }

    @Test
    fun sinceNamesTheDayOnlyWhenItIsNotToday() {
        val now = ts(day0, 21)
        assertEquals("18:40", Insights.since(ts(day0, 18, 40), now, zone, Locale.UK))
        assertEquals("Tue 18:40", Insights.since(ts(day0.minusDays(1), 18, 40), now, zone, Locale.UK))
    }

    @Test
    fun theHomeScreenSaysWhenTheStrapIsOffNow() {
        val open = OffWristSpan(ts(day0, 18, 40), null)
        assertEquals(
            "Off the wrist since 18:40.",
            Insights.offWristToday(wrist(open, through = ts(day0, 20)), isLastNight = true, day0, ts(day0, 21), zone, Locale.UK),
        )
        val yesterday = OffWristSpan(ts(day0.minusDays(1), 18, 40), null)
        assertEquals(
            "Off the wrist since Tue 18:40.",
            Insights.offWristToday(wrist(yesterday), isLastNight = false, day0, ts(day0, 9), zone, Locale.UK),
        )
    }

    @Test
    fun aNightThatLeftNoRecordIsExplainedWhenTheStrapWasOffForAnHourOfIt() {
        // Off from 20:00 to 03:05: six hours and five minutes of the 21:00 to noon the night is looked for in.
        val off = OffWristSpan(ts(day0.minusDays(1), 20), ts(day0, 3, 5))
        val now = ts(day0, 9)
        assertEquals(
            "The strap was off the wrist for 6h 5m of last night.",
            Insights.offWristToday(wrist(off), isLastNight = false, day0, now, zone, Locale.UK),
        )
        // Nothing is said when last night did leave a night, or when the strap was off for less than an hour of it.
        assertNull(Insights.offWristToday(wrist(off), isLastNight = true, day0, now, zone, Locale.UK))
        val brief = OffWristSpan(ts(day0, 1), ts(day0, 1, 40))
        assertNull(Insights.offWristToday(wrist(brief), isLastNight = false, day0, now, zone, Locale.UK))
        assertNull(Insights.offWristToday(wrist(), isLastNight = false, day0, now, zone, Locale.UK))
    }

    @Test
    fun aDayOnTrendsGetsALineOnceItsStretchesAddUpToAnHour() {
        assertEquals("Off the wrist for 3h 10m, noon to noon.", Insights.offWristDay(3 * 3600L + 600))
        assertEquals("Off the wrist for 1h 0m, noon to noon.", Insights.offWristDay(3600L))
        assertNull(Insights.offWristDay(3599L))
        assertNull(Insights.offWristDay(0L))
    }

    @Test
    fun aDaysHoursAreTheSameOnTheBarAndInTheSentence() {
        // Off from 14:00 to 17:10 on the afternoon before the night: inside the night's noon-to-noon bar.
        val off = OffWristSpan(ts(day0.minusDays(1), 14), ts(day0.minusDays(1), 17, 10))
        val n = night(day0)
        val w = wrist(off)
        assertEquals(3 * 3600L + 600, Insights.offWristSecondsOnLastDay(n, w))
        assertEquals(3 * 3600L + 600, Insights.offWristSecondsOn(day0, n, w, zone))
        assertEquals(3 * 3600L + 600, Insights.offWristSecondsOn(day0, null, w, zone))
        assertEquals(0L, Insights.offWristSecondsOn(day0.plusDays(1), null, w, zone))
        val (from, to) = Insights.offWristOnLastDay(n, w).single()
        assertEquals(2f / 24f, from, 1e-4f)
        assertEquals((5f + 10f / 60f) / 24f, to, 1e-4f)
        assertEquals("Off the wrist 3h 10m", Insights.offWristLegend(Insights.offWristSecondsOnLastDay(n, w)))
    }

    @Test
    fun aStretchInsideTheTimeInBedIsSaidOnThatNight() {
        val n = night(day0)
        val off = OffWristSpan(ts(day0, 2), ts(day0, 2, 25))
        assertEquals(25 * 60L, Insights.offWristSecondsInBed(n, wrist(off)))
        assertEquals(
            "The strap was off the wrist for 25m of this time in bed. It recorded nothing then, so that time shows as awake.",
            Insights.offWristInBed(25 * 60L),
        )
        // Before going still, or after the sleep ended, is not time in bed.
        assertEquals(0L, Insights.offWristSecondsInBed(n, wrist(OffWristSpan(ts(day0.minusDays(1), 20), ts(day0.minusDays(1), 22)))))
        assertEquals(0L, Insights.offWristSecondsInBed(n, wrist(OffWristSpan(ts(day0, 7), ts(day0, 9)))))
    }

    @Test
    fun theStrapTabSaysWhetherItIsWornAndHowLongItWasOff() {
        val now = ts(day0, 21)
        assertEquals("on the wrist", Insights.worn(wrist(OffWristSpan(ts(day0, 9), ts(day0, 10))), now, zone, Locale.UK))
        assertEquals("off the wrist since 18:40", Insights.worn(wrist(OffWristSpan(ts(day0, 18, 40), null)), now, zone, Locale.UK))
        assertEquals("no wrist event recorded", Insights.worn(wrist(seen = false), now, zone, Locale.UK))

        // Two hours off this morning and three hours off four days ago; an open stretch counts as far as the strap has reported.
        val w = wrist(
            OffWristSpan(ts(day0.minusDays(4), 9), ts(day0.minusDays(4), 12)),
            OffWristSpan(ts(day0, 9), ts(day0, 11)),
            OffWristSpan(ts(day0, 20), null),
            through = ts(day0, 20, 30),
        )
        assertEquals("2h 30m", Insights.offWristLast(86_400L, w, now))
        assertEquals("5h 30m", Insights.offWristLast(7 * 86_400L, w, now))
    }
}
