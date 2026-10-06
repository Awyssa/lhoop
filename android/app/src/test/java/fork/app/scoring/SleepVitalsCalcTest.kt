package fork.app.scoring

import com.lhoop.data.HrSample
import com.lhoop.data.RrInterval
import fork.app.scoring.StateScript.HOUR
import fork.app.scoring.StateScript.MIN
import fork.app.scoring.StateScript.sleep
import fork.app.scoring.StateScript.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepVitalsCalcTest {

    private val t0 = utc(2026, 3, 10, 23, 0)

    /** One beat a second, alternating between two lengths [spread] apart, so every window's RMSSD is [spread]. */
    private fun beats(from: Long, seconds: Int, spread: Int, base: Int = 900): List<RrInterval> =
        List(seconds) { i -> RrInterval(deviceId = "strap", ts = from + i, rrMs = if (i % 2 == 0) base else base + spread) }

    private fun heartRate(from: Long, seconds: Int, bpm: Int): List<HrSample> =
        List(seconds) { i -> HrSample("strap", from + i, bpm) }

    @Test
    fun hrvIsTheMeanOfTheFiveMinuteWindowsAndTheLateFigureCoversTheLastThreeHours() {
        // Five hours asleep: two hours with an RMSSD of 20, then three with 40.
        val night = sleep(t0, t0 + 5 * HOUR - 1)
        val rr = beats(t0, 2 * HOUR, spread = 20) + beats(t0 + 2 * HOUR, 3 * HOUR, spread = 40)
        val v = SleepVitalsCalc.compute(night, heartRate(t0, 5 * HOUR, 60), rr)
        assertEquals(60, v.hrvWindows)
        assertEquals((24 * 20.0 + 36 * 40.0) / 60, v.hrvMs!!, 1e-6)
        assertEquals(40.0, v.lateHrvMs!!, 1e-6)
    }

    @Test
    fun restingHeartRateIsTheLowestFiveMinuteMean() {
        val night = sleep(t0, t0 + 2 * HOUR - 1)
        val hr = heartRate(t0, 50 * MIN, 70) + heartRate(t0 + 50 * MIN, 5 * MIN, 55) + heartRate(t0 + 55 * MIN, 65 * MIN, 68)
        assertEquals(55, SleepVitalsCalc.compute(night, hr, beats(t0, 2 * HOUR, 30)).restingHr)
        assertNull(SleepVitalsCalc.compute(night, emptyList(), emptyList()).restingHr)
    }

    @Test
    fun aSleepWithFewerThanSixUsableWindowsHasNoHrv() {
        val nap = sleep(t0, t0 + 25 * MIN - 1)
        val v = SleepVitalsCalc.compute(nap, heartRate(t0, 25 * MIN, 62), beats(t0, 25 * MIN, 30))
        assertEquals(5, v.hrvWindows)
        assertNull(v.hrvMs)
        assertNull(v.lateHrvMs)
        assertEquals(62, v.restingHr)
    }

    @Test
    fun timeAwakeBetweenTwoStretchesOfOneSleepIsLeftOut() {
        // An hour asleep, half an hour up and about, an hour asleep.
        val second = t0 + HOUR + 30 * MIN
        val night = StrapSleep(
            bedStartTs = t0, startTs = t0, endTs = second + HOUR - 1, asleepSec = 2L * HOUR, restlessSec = 0L,
            upAfterSec = 0L, wakeConfirmed = true,
            stretches = listOf(Stretch(t0, t0 + HOUR - 1, HOUR.toLong(), 0L), Stretch(second, second + HOUR - 1, HOUR.toLong(), 0L)),
        )
        val rr = beats(t0, HOUR, 20) + beats(t0 + HOUR, 30 * MIN, spread = 100, base = 800) + beats(second, HOUR, 20)
        val hr = heartRate(t0, HOUR, 60) + heartRate(t0 + HOUR, 30 * MIN, 40) + heartRate(second, HOUR, 60)
        val v = SleepVitalsCalc.compute(night, hr, rr)
        assertEquals(24, v.hrvWindows)
        assertEquals(20.0, v.hrvMs!!, 1e-6)
        assertEquals(60, v.restingHr)
    }

    @Test
    fun beatsThatAddUpToMoreTimeThanTheClockAllowsGiveNoHrv() {
        // Every beat stored twice, a second apart: 1.8 seconds of beats for each second of clock.
        val night = sleep(t0, t0 + 2 * HOUR - 1)
        val doubled = (beats(t0, 2 * HOUR, 20) + beats(t0, 2 * HOUR, 20)).sortedBy { it.ts }
        val v = SleepVitalsCalc.compute(night, heartRate(t0, 2 * HOUR, 60), doubled)
        assertNull(v.hrvMs)
        assertEquals(0, v.hrvWindows)
        assertEquals(60, v.restingHr)
    }
}
