package fork.app

import com.lhoop.data.DailyMetric
import com.lhoop.data.SleepSession
import fork.app.scoring.SleepRecord
import fork.app.scoring.SleepVitals
import fork.app.scoring.StrapSleep
import fork.app.scoring.Stretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

class NightsTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")

    private fun day(key: String, sleep: Double? = 420.0, hrv: Double? = 40.0, recovery: Double? = 60.0) =
        DailyMetric(deviceId = "d", day = key, totalSleepMin = sleep, avgHrv = hrv, recovery = recovery)

    private fun ts(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toEpochSecond()

    /** A night in bed from [bed], asleep from [start] to [end], with [awakeSec] of it not asleep. British summer time. */
    private fun record(bed: Long, start: Long, end: Long, awakeSec: Long = 0, vitals: SleepVitals? = SleepVitals(42.0, 55.0, 80, 58)) =
        SleepRecord(
            deviceId = "d",
            sleep = StrapSleep(
                bedStartTs = bed, startTs = start, endTs = end, asleepSec = end - start + 1 - awakeSec, restlessSec = 0,
                upAfterSec = 0, wakeConfirmed = true, stretches = listOf(Stretch(start, end, end - start + 1 - awakeSec, 0)),
            ),
            offsetSec = 3600,
            vitals = vitals,
            dataThroughTs = end + 86_400,
        )

    @Test
    fun efficiencyIsShownAsAPercentageWhicheverWayItWasStored() {
        assertEquals(92.0, Nights.efficiencyPct(0.92)!!, 1e-9)
        assertEquals(92.0, Nights.efficiencyPct(92.0)!!, 1e-9)
        assertEquals(100.0, Nights.efficiencyPct(1.0)!!, 1e-9)
        assertNull(Nights.efficiencyPct(null))
    }

    @Test
    fun recoveryBandsFollowTheCoresThresholds() {
        assertEquals(RecoveryBand.RED, Nights.band(33.9))
        assertEquals(RecoveryBand.YELLOW, Nights.band(34.0))
        assertEquals(RecoveryBand.YELLOW, Nights.band(66.9))
        assertEquals(RecoveryBand.GREEN, Nights.band(67.0))
        assertNull(Nights.band(null))
    }

    @Test
    fun aStoredDayWithNothingFromANightIsNotANight() {
        assertFalse(Nights.hasNight(DailyMetric(deviceId = "d", day = "2026-10-03", strain = 5.0)))
        assertTrue(Nights.hasNight(day("2026-10-03")))
        assertTrue(Nights.hasNight(DailyMetric(deviceId = "d", day = "2026-10-03", restingHr = 60)))
    }

    @Test
    fun theNewestNightIsLastNightUntilTheDayRollsOverAtFour() {
        // The core's "today" is still the 2nd until 04:00 on the 3rd.
        assertTrue(Nights.isLastNight("2026-10-02", logicalToday = "2026-10-02"))
        // A night that already ended on the 3rd, looked at before 04:00, is last night too.
        assertTrue(Nights.isLastNight("2026-10-03", logicalToday = "2026-10-02"))
        // After the rollover, a newest night dated the 2nd means last night is not in yet.
        assertFalse(Nights.isLastNight("2026-10-02", logicalToday = "2026-10-03"))
    }

    @Test
    fun theCoresFiguresForADayAreCarriedAsStored() {
        val night = SleepSession(deviceId = "d", startTs = ts(2026, 10, 2, 23, 40), endTs = ts(2026, 10, 3, 7, 10))
        val nap = SleepSession(deviceId = "d", startTs = ts(2026, 10, 3, 14, 0), endTs = ts(2026, 10, 3, 14, 40))
        val other = SleepSession(deviceId = "d", startTs = ts(2026, 10, 1, 23, 0), endTs = ts(2026, 10, 2, 6, 0))
        val onTheThird = Nights.sessionsOn("2026-10-03", listOf(other, nap, night), zone)
        assertEquals(listOf(nap, night), onTheThird)
        assertEquals(emptyList<SleepSession>(), Nights.sessionsOn("2026-10-04", listOf(other, nap, night), zone))

        val stored = DailyMetric(
            deviceId = "d", day = "2026-10-03", totalSleepMin = 430.0, efficiency = 0.915, restingHr = 57, avgHrv = 48.5,
            recovery = 71.0, deepMin = 90.0, remMin = 100.0, lightMin = 240.0, respRateBpm = 14.2, skinTempDevC = -0.2,
        )
        val core = Nights.core(stored, sleepScore = 88.0, daySessions = onTheThird)
        assertEquals(71.0, core.recoveryPct!!, 1e-9)
        assertEquals(88.0, core.sleepScore!!, 1e-9)
        assertEquals(430.0, core.asleepMin!!, 1e-9)
        assertEquals(91.5, core.efficiencyPct!!, 1e-9)
        assertEquals(48.5, core.hrvMs!!, 1e-9)
        assertEquals(57, core.restingHr)
        // Its bed times are those of the longest session it detected ending that day.
        assertEquals(night.startTs, core.bedStartTs)
        assertEquals(night.endTs, core.bedEndTs)
        assertFalse(core.heartRateOnly)
    }

    @Test
    fun anEditedBedTimeIsTheOneCarried() {
        val edited = SleepSession(
            deviceId = "d", startTs = ts(2026, 10, 2, 23, 40), endTs = ts(2026, 10, 3, 7, 10),
            startTsAdjusted = ts(2026, 10, 3, 0, 15),
        )
        val core = Nights.core(day("2026-10-03"), sleepScore = 80.0, daySessions = listOf(edited))
        assertEquals(ts(2026, 10, 3, 0, 15), core.bedStartTs)
        assertEquals(ts(2026, 10, 3, 7, 10), core.bedEndTs)
    }

    @Test
    fun aNightTheCoreWithheldVitalsForIsFlagged() {
        val hrOnly = DailyMetric(deviceId = "d", day = "2026-10-03", totalSleepMin = 400.0, sleepHrOnly = true)
        val core = Nights.core(hrOnly, sleepScore = null, daySessions = emptyList())
        assertTrue(core.heartRateOnly)
        assertNull(core.hrvMs)
        assertNull(core.bedStartTs)
    }

    @Test
    fun aNightBecomesAScoringInputWithAnEveningBedtimeBeforeMidnight() {
        // In bed 23:30, asleep 23:45 to 07:15 with fifteen minutes awake in between.
        val r = record(bed = ts(2026, 10, 2, 23, 30), start = ts(2026, 10, 2, 23, 45), end = ts(2026, 10, 3, 7, 15) - 1, awakeSec = 900)
        val night = Night("2026-10-03", r, naps = emptyList(), core = null)
        val input = Nights.input(night)!!
        assertEquals(-30.0, input.bedMinute!!, 1e-9)
        assertEquals(435.0, input.wakeMinute!!, 1.0 / 60 + 1e-9)
        assertEquals(435.0, input.asleepMin!!, 1e-9)
        assertEquals(100.0 * 435 / 465, input.efficiencyPct!!, 1e-9)
        assertEquals(42.0, input.hrvMs!!, 1e-9)
        assertEquals(58.0, input.restingHr!!, 1e-9)
        assertNull(Nights.input(night.copy(day = "someday")))

        val without = Nights.input(night.copy(record = r.copy(vitals = null)))!!
        assertNull(without.hrvMs)
        assertNull(without.restingHr)
        assertEquals(435.0, without.asleepMin!!, 1e-9)
        assertTrue(input.naps.isEmpty())
    }

    @Test
    fun theSleepsBeforeANightGoWithItCountedFromTheNightsOwnMidnight() {
        val r = record(bed = ts(2026, 10, 2, 23, 30), start = ts(2026, 10, 2, 23, 45), end = ts(2026, 10, 3, 7, 15) - 1)
        // In bed 14:00 the afternoon before, asleep 14:10 to 15:30 with ten minutes of it awake.
        val nap = record(bed = ts(2026, 10, 2, 14, 0), start = ts(2026, 10, 2, 14, 10), end = ts(2026, 10, 2, 15, 30) - 1, awakeSec = 600)
        val input = Nights.input(Night("2026-10-03", r, naps = listOf(nap), core = null))!!
        val only = input.naps.single()
        assertEquals(-600.0, only.startMinute, 1e-9)
        assertEquals(-510.0, only.endMinute, 1.0 / 60 + 1e-9)
        assertEquals(70.0, only.asleepMin, 1e-9)
    }

    @Test
    fun theNoteUnderOtherSleepsSaysWhatEachKindDoes() {
        assertEquals("An earlier sleep takes its time asleep off what this night needed.", Nights.otherSleepsNote(earlier = true, since = false))
        assertEquals("A sleep since will do the same for tonight.", Nights.otherSleepsNote(earlier = false, since = true))
        assertEquals(
            "An earlier sleep takes its time asleep off what this night needed. A sleep since will do the same for tonight.",
            Nights.otherSleepsNote(earlier = true, since = true),
        )
    }

    @Test
    fun timesAreShownWhereTheSleepWasSlept() {
        val r = record(bed = ts(2026, 6, 14, 14, 5), start = ts(2026, 6, 14, 14, 15), end = ts(2026, 6, 14, 15, 40))
        assertEquals("14:15", Nights.clock(r.sleep.startTs, r.offset, Locale.UK))
        assertEquals("14:05 to 15:40", Nights.span(r.sleep.bedStartTs, r.sleep.endTs, r.offset, Locale.UK))
        assertEquals("Sun 14:15 to 15:40", Nights.napLabel(r, Locale.UK))
        // The same instant read nine hours east of UTC.
        assertEquals("22:15", Nights.clock(r.sleep.startTs, ZoneOffset.ofHours(9), Locale.UK))
    }

    @Test
    fun valuesAreFormattedPlainlyAndMissingOnesAsADash() {
        assertEquals("7h 12m", Nights.duration(432.0))
        assertEquals("45m", Nights.duration(45.4))
        assertEquals("8h 0m", Nights.duration(479.6))
        assertEquals(Nights.DASH, Nights.duration(null))
        assertEquals("7h 30m", Nights.durationSec(27_000))
        assertEquals("42", Nights.whole(41.6))
        assertEquals("68%", Nights.whole(67.5, "%"))
        assertEquals(Nights.DASH, Nights.whole(null, "%"))
        assertEquals("+0.3 °C", Nights.signed1(0.31, " °C"))
        assertEquals("-0.4 °C", Nights.signed1(-0.44, " °C"))
    }
}
