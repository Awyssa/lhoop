package fork.app.scoring

import fork.app.scoring.StateScript.ASLEEP
import fork.app.scoring.StateScript.AWAKE
import fork.app.scoring.StateScript.HOUR
import fork.app.scoring.StateScript.MIN
import fork.app.scoring.StateScript.STILL
import fork.app.scoring.StateScript.UP
import fork.app.scoring.StateScript.minutes
import fork.app.scoring.StateScript.record
import fork.app.scoring.StateScript.sleep
import fork.app.scoring.StateScript.utc
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepLogTest {

    private val t0 = utc(2026, 3, 10, 23, 0)
    private val worked = SleepVitals(hrvMs = 33.0, lateHrvMs = 44.0, hrvWindows = 70, restingHr = 58)

    /** Runs a refresh over made-up state, recording what it asked for. */
    private class Run(val scannedFrom: MutableList<Long> = ArrayList(), val vitalsAskedFor: MutableList<StrapSleep> = ArrayList())

    private fun refresh(
        stored: List<SleepRecord>,
        state: List<StateMinute>,
        through: Long,
        floor: Long = 0L,
        vitals: SleepVitals? = worked,
        run: Run = Run(),
    ): List<SleepRecord> = runBlocking {
        SleepLog.refresh(
            deviceId = "strap",
            stored = stored,
            floor = floor,
            through = through,
            minutesFrom = { from -> run.scannedFrom += from; state.filter { it.minute >= from / 60 } },
            vitalsOf = { run.vitalsAskedFor += it; vitals },
            offsetAt = { 3600 },
        )
    }

    @Test
    fun theFirstRefreshFindsEverySleepFromTheFloor() {
        val state = minutes(t0, STILL to 10 * MIN, ASLEEP to 7 * HOUR, UP to 630, AWAKE to 5 * HOUR)
        val run = Run()
        val out = refresh(emptyList(), state, through = t0 + 13 * HOUR, floor = t0 - 40 * 86_400L, run = run)
        assertEquals(listOf(t0 - 40 * 86_400L), run.scannedFrom)
        val r = out.single()
        assertEquals("strap", r.deviceId)
        assertEquals(t0, r.sleep.bedStartTs)
        assertEquals(7L * HOUR, r.sleep.asleepSec)
        assertEquals(worked, r.vitals)
        assertEquals(3600, r.offsetSec)
        assertEquals(t0 + 13 * HOUR, r.dataThroughTs)
        assertTrue(r.settled)
    }

    @Test
    fun aSettledSleepIsKeptAsStoredAndOnlyLaterStateIsRead() {
        val old = record(sleep(t0, t0 + 7 * HOUR), dataThroughTs = t0 + 12 * HOUR, offsetSec = 7200)
        val next = t0 + 24 * HOUR
        val state = minutes(t0, ASLEEP to 6 * HOUR) + minutes(next, ASLEEP to 6 * HOUR, UP to 630)
        val run = Run()
        val out = refresh(listOf(old), state, through = next + 6 * HOUR + 700, run = run)
        assertEquals(listOf(old.sleep.endTs + 1), run.scannedFrom)
        assertEquals(old, out[0])
        assertEquals(next, out[1].sleep.startTs)
        // Only the new sleep's heart figures were worked out.
        assertEquals(listOf(out[1].sleep), run.vitalsAskedFor)
        assertFalse(out[1].settled)
    }

    @Test
    fun aSleepStillGoingOnIsWorkedOutAgainAsItGrows() {
        val early = refresh(emptyList(), minutes(t0, ASLEEP to 2 * HOUR), through = t0 + 2 * HOUR - 1)
        assertTrue(early.single().ongoing)
        assertFalse(early.single().settled)

        val run = Run()
        // Still undecided: the data ends while the strap has the wearer "up".
        val stirring = refresh(early, minutes(t0, ASLEEP to 7 * HOUR, UP to 300), through = t0 + 7 * HOUR + 299, run = run)
        assertEquals(7L * HOUR, stirring.single().sleep.asleepSec)
        assertEquals(1, run.vitalsAskedFor.size)
        assertTrue(stirring.single().ongoing)

        // Then the strap calls them awake, and it is over.
        val later = refresh(stirring, minutes(t0, ASLEEP to 7 * HOUR, UP to 630, AWAKE to 600), through = t0 + 7 * HOUR + 1229)
        assertEquals(7L * HOUR, later.single().sleep.asleepSec)
        assertFalse(later.single().ongoing)
        assertTrue(later.single().sleep.wakeConfirmed)
    }

    @Test
    fun anUnchangedUnsettledSleepKeepsItsFiguresWithoutReadingThemAgain() {
        val state = minutes(t0, ASLEEP to 7 * HOUR, UP to 630, AWAKE to HOUR)
        val first = refresh(emptyList(), state, through = t0 + 8 * HOUR)
        val run = Run()
        val second = refresh(first, state, through = t0 + 9 * HOUR, vitals = null, run = run)
        assertEquals(emptyList<StrapSleep>(), run.vitalsAskedFor)
        assertEquals(worked, second.single().vitals)
        assertEquals(t0 + 9 * HOUR, second.single().dataThroughTs)
    }

    @Test
    fun aSleepWhoseHeartFiguresCouldNotBeReadIsTriedAgainHoweverOldItIs() {
        val state = minutes(t0, ASLEEP to 7 * HOUR, UP to 630, AWAKE to HOUR)
        val failed = refresh(emptyList(), state, through = t0 + 30 * HOUR, vitals = null)
        assertNull(failed.single().vitals)
        assertFalse(failed.single().settled)

        val run = Run()
        val retried = refresh(failed, state, through = t0 + 31 * HOUR, run = run)
        assertEquals(1, run.vitalsAskedFor.size)
        assertEquals(worked, retried.single().vitals)
        assertTrue(retried.single().settled)
    }

    @Test
    fun aSleepIsNotSettledWhileTheStrapStillCallsTheWearerUp() {
        // Asleep five hours, then "up" for four hours without the strap deciding, then asleep again.
        val whole = minutes(t0, ASLEEP to 5 * HOUR, UP to 4 * HOUR, ASLEEP to HOUR, UP to 630, AWAKE to 6 * HOUR)
        val oneRead = refresh(emptyList(), whole, through = t0 + 16 * HOUR)

        // Looked at three and a half hours into the "up": the first five hours must not be frozen as a sleep.
        val seenSoFar = minutes(t0, ASLEEP to 5 * HOUR, UP to 3 * HOUR + 30 * MIN)
        val early = refresh(emptyList(), seenSoFar, through = t0 + 8 * HOUR + 30 * MIN - 1)
        assertFalse(early.single().settled)
        assertEquals(oneRead, refresh(early, whole, through = t0 + 16 * HOUR))
        assertEquals(10L * HOUR, oneRead.single().sleep.asleepSec)
        assertEquals(4L * HOUR, oneRead.single().sleep.restlessSec)
    }

    @Test
    fun everythingFromTheFirstUnsettledSleepOnIsWorkedOutAgain() {
        val a = record(sleep(t0, t0 + 6 * HOUR), dataThroughTs = t0 + 20 * HOUR)
        val b = record(sleep(t0 + 24 * HOUR, t0 + 30 * HOUR, upAfterSec = 630), dataThroughTs = t0 + 31 * HOUR) // not settled
        val c = record(sleep(t0 + 48 * HOUR, t0 + 54 * HOUR), dataThroughTs = t0 + 80 * HOUR) // settled, but after b
        val state = minutes(t0 + 24 * HOUR, ASLEEP to 6 * HOUR + 1, UP to 630) +
            minutes(t0 + 48 * HOUR, ASLEEP to 5 * HOUR, UP to 630)
        val run = Run()
        val out = refresh(listOf(c, a, b), state, through = t0 + 90 * HOUR, run = run)
        assertEquals(listOf(a.sleep.endTs + 1), run.scannedFrom)
        assertEquals(a, out[0])
        assertEquals(b.sleep, out[1].sleep)
        assertEquals(5L * HOUR, out[2].sleep.asleepSec)
        // b is unchanged, so only c's replacement needed its figures worked out.
        assertEquals(listOf(out[2].sleep), run.vitalsAskedFor)
    }
}
