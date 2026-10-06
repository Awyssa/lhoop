package fork.app

import fork.app.scoring.SleepRecord
import fork.app.scoring.SleepVitals
import fork.app.scoring.StrapSleep
import fork.app.scoring.Stretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SleepStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = SleepStore(File(folder.root, "fork/sleeps.json"))

    private val twoStretches = SleepRecord(
        deviceId = "strap",
        sleep = StrapSleep(
            bedStartTs = 1_000, startTs = 1_600, endTs = 30_000, asleepSec = 25_000, restlessSec = 1_800, upAfterSec = 630,
            wakeConfirmed = true,
            stretches = listOf(Stretch(1_600, 12_000, 10_000, 300), Stretch(15_000, 30_000, 15_000, 1_500)),
        ),
        offsetSec = 3_600,
        vitals = SleepVitals(hrvMs = 31.25, lateHrvMs = 40.5, hrvWindows = 77, restingHr = 58),
        dataThroughTs = 50_000,
    )

    /** A nap too short for HRV, on another strap, west of UTC. */
    private val partFigures = SleepRecord(
        deviceId = "other",
        sleep = StrapSleep(40_000, 40_000, 42_000, 2_001, 0, 0, false, listOf(Stretch(40_000, 42_000, 2_001, 0))),
        offsetSec = -18_000,
        vitals = SleepVitals(hrvMs = null, lateHrvMs = null, hrvWindows = 3, restingHr = null),
        dataThroughTs = 42_050,
    )

    private val noFigures = partFigures.copy(deviceId = "strap", vitals = null)

    @Test
    fun whatIsWrittenIsReadBackUnchanged() {
        val records = listOf(twoStretches, partFigures, noFigures)
        store().write(records)
        assertEquals(records, store().read())
    }

    @Test
    fun writingReplacesTheFileAndLeavesNothingElseBehind() {
        store().write(listOf(twoStretches, partFigures))
        store().write(listOf(partFigures))
        assertEquals(listOf(partFigures), store().read())
        assertEquals(listOf("sleeps.json"), File(folder.root, "fork").list()!!.toList())
        store().write(emptyList())
        assertEquals(emptyList<SleepRecord>(), store().read())
    }

    @Test
    fun aMissingOrUnreadableFileReadsAsNothing() {
        assertEquals(emptyList<SleepRecord>(), store().read())
        val file = File(folder.root, "fork/sleeps.json").apply { parentFile!!.mkdirs() }
        file.writeText("not json")
        assertEquals(emptyList<SleepRecord>(), store().read())
        file.writeText("""{"rules":${SleepStore.RULES},"sleeps":[{"device":"strap"}]}""")
        assertEquals(emptyList<SleepRecord>(), store().read())
    }

    @Test
    fun aFileWrittenUnderOtherRulesIsIgnored() {
        store().write(listOf(twoStretches))
        val file = File(folder.root, "fork/sleeps.json")
        val other = file.readText().replace("\"rules\":${SleepStore.RULES}", "\"rules\":${SleepStore.RULES + 1}")
        assertFalse(other == file.readText())
        file.writeText(other)
        assertEquals(emptyList<SleepRecord>(), store().read())
    }
}
