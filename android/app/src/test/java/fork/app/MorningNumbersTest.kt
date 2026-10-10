package fork.app

import fork.app.scoring.SleepRecord
import fork.app.scoring.SleepVitals
import fork.app.scoring.StrapSleep
import fork.app.scoring.Stretch
import fork.app.scoring.WhoopStyleScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** The widget's numbers and the file they wait in. Every figure here is made up. */
class MorningNumbersTest {

    @get:Rule val folder = TemporaryFolder()

    private val day: LocalDate = LocalDate.of(2031, 3, 12)

    /** Seven hours asleep ending at a made-up second, with or without a stage split and heart figures. */
    private fun night(day: LocalDate, vitals: SleepVitals?): Night {
        val end = 2_000_000_000L
        val start = end - 7 * 3600 + 1
        val sleep = StrapSleep(start - 600, start, end, 7 * 3600L, 0, 0, true, listOf(Stretch(start, end, 7 * 3600L, 0)))
        return Night(day.toString(), SleepRecord("d", sleep, 0, vitals, end + 86_400), emptyList(), core = null)
    }

    private fun score(day: LocalDate, recovery: Double?, baseline: Int) =
        WhoopStyleScore(day, 480.0, 0.0, 0.0, 87.5, 70.0, 80.0, 0.4, 0.5, baseline, recovery)

    @Test
    fun theNewestNightIsTheOneShownInTheWholeNumbersOnScreen() {
        val newest = night(day, SleepVitals(41.6, 50.0, 80, 55, deepSec = 5_400, remSec = 4_530))
        val state = NightsViewModel.State(
            loaded = true,
            nights = listOf(newest, night(day.minusDays(1), null)),
            whoopStyle = mapOf(day.toString() to score(day, 63.5, 5)),
            // The count the screens print beside the score is the one the widget repeats.
            usual = mapOf(day.toString() to Usual(5, 40.0, 30.0, 50.0, 56.0, 52.0, 60.0)),
        )
        assertEquals(
            MorningNumbers(day.toString(), recoveryPct = 64, baselineNights = 5, ongoing = false, asleepMin = 420, deepMin = 90, remMin = 76, hrvMs = 42),
            MorningNumbers.from(state),
        )
    }

    @Test
    fun aNightWithoutScoresOrStagesKeepsItsHoursAndLeavesTheRestEmpty() {
        val state = NightsViewModel.State(loaded = true, nights = listOf(night(day, null)))
        assertEquals(
            MorningNumbers(day.toString(), recoveryPct = null, baselineNights = 0, ongoing = false, asleepMin = 420, deepMin = null, remMin = null, hrvMs = null),
            MorningNumbers.from(state),
        )
    }

    @Test
    fun aNightWhoseDataStopsBeforeAWakingIsMarkedAsPossiblyUnfinished() {
        // The strap's data ends with the sleep and it never called the wearer awake.
        val end = 2_000_000_000L
        val start = end - 5 * 3600 + 1
        val sleep = StrapSleep(start, start, end, 5 * 3600L, 0, 0, wakeConfirmed = false, stretches = listOf(Stretch(start, end, 5 * 3600L, 0)))
        val unfinished = Night(day.toString(), SleepRecord("d", sleep, 0, null, dataThroughTs = end), emptyList(), core = null)
        assertTrue(unfinished.record.ongoing)
        assertEquals(true, MorningNumbers.from(NightsViewModel.State(loaded = true, nights = listOf(unfinished)))?.ongoing)
    }

    @Test
    fun noNightsIsNothingToShow() {
        assertNull(MorningNumbers.from(NightsViewModel.State(loaded = true)))
    }

    @Test
    fun theFileGivesBackWhatWasWrittenWithOrWithoutTheOptionalFigures() {
        val full = MorningNumbers(day.toString(), 64, 5, false, 420, 90, 76, 42)
        val bare = MorningNumbers(day.toString(), null, 0, true, 420, null, null, null)
        assertEquals(full, MorningNumbers.parse(full.json()))
        assertEquals(bare, MorningNumbers.parse(bare.json()))
        assertNull(MorningNumbers.parse("not a file this wrote"))
        assertNull(MorningNumbers.parse("{}"))
    }

    @Test
    fun theStoreWritesOnlyWhenSomethingChangedAndRemovesTheFileForNothing() {
        val file = MorningStore.fileIn(folder.root)
        val store = MorningStore(file)
        assertNull(store.read())
        assertFalse(store.write(null))
        val numbers = MorningNumbers(day.toString(), 64, 5, false, 420, 90, 76, 42)
        assertTrue(store.write(numbers))
        assertEquals(File(folder.root, "fork/morning.json"), file)
        assertEquals(numbers, store.read())
        assertFalse(store.write(numbers))
        assertTrue(store.write(numbers.copy(recoveryPct = 70)))
        assertEquals(70, store.read()?.recoveryPct)
        assertTrue(store.write(null))
        assertNull(store.read())
        assertFalse(file.exists())
    }
}
