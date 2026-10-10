package fork.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The figures here are made up. The layout is the page's: one quoted label per line, after two coordinates. */
class GarminCaptureTest {

    private val capturedOn = LocalDate.of(2031, 3, 12)

    private fun page(vararg labels: String) = labels.joinToString("\n") { "  540   300   '$it'" }

    @Test
    fun aPageForTodayIsTheCaptureDay() {
        val night = GarminCapture.parse(
            page("Previous", "Today", "Stages", "7h 0m", "Total Sleep", "Deep 1 hour 30 minutes", "Light 4 hours", "REM 1 hour 30 minutes", "Awake 20 minutes"),
            capturedOn,
        )
        assertEquals(GarminNight(capturedOn, deepMin = 90, lightMin = 240, remMin = 90, awakeMin = 20), night)
        assertEquals(420, night!!.asleepMin)
    }

    @Test
    fun aPageForAnEarlierNightTakesItsDateAndTheCaptureYear() {
        val night = GarminCapture.parse(
            page("Previous", "Monday 10 March", "Next", "Deep 45 minutes", "Light 5 hours 1 minute", "REM 2 hours"),
            capturedOn,
        )
        assertEquals(GarminNight(LocalDate.of(2031, 3, 10), deepMin = 45, lightMin = 301, remMin = 120, awakeMin = null), night)
    }

    @Test
    fun aPageWithoutTheStagesIsNotANight() {
        assertNull(GarminCapture.parse(page("Previous", "Today", "Sleep Score", "80 out of 100 Score", "Duration, 7h 0m "), capturedOn))
    }

    @Test
    fun aPageThatDoesNotSayWhichNightIsNotUsed() {
        assertNull(GarminCapture.parse(page("Deep 1 hour", "Light 4 hours", "REM 1 hour"), capturedOn))
    }

    @Test
    fun aDateThatDoesNotExistIsNotUsed() {
        assertNull(GarminCapture.parse(page("Sunday 31 February", "Deep 1 hour", "Light 4 hours", "REM 1 hour"), capturedOn))
    }
}
