package fork.app.scoring

import fork.app.scoring.StateScript.ASLEEP
import fork.app.scoring.StateScript.AWAKE
import fork.app.scoring.StateScript.HOUR
import fork.app.scoring.StateScript.MIN
import fork.app.scoring.StateScript.NO_DATA
import fork.app.scoring.StateScript.STILL
import fork.app.scoring.StateScript.UP
import fork.app.scoring.StateScript.minutes
import fork.app.scoring.StateScript.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrapSleepTest {

    private val t0 = utc(2026, 3, 10, 23, 0)

    @Test
    fun aPlainNightRunsFromGoingStillToTheLastAsleepSecond() {
        // Lies down 25 seconds past the minute, is flagged asleep 11 minutes later, sleeps 6 hours, gets up.
        val found = StrapSleeps.find(
            minutes(t0 + 25, STILL to 11 * MIN, ASLEEP to 6 * HOUR, UP to 630, AWAKE to 30 * MIN),
        )
        assertEquals(1, found.size)
        val s = found.single()
        assertEquals(t0 + 25, s.bedStartTs)
        assertEquals(t0 + 25 + 11 * MIN, s.startTs)
        assertEquals(s.startTs + 6 * HOUR - 1, s.endTs)
        assertEquals(6L * HOUR, s.asleepSec)
        assertEquals(0L, s.restlessSec)
        assertEquals(0L, s.awakeSec)
        // The ten and a half minutes of "up" before the strap said awake are the wearer getting up.
        assertEquals(630L, s.upAfterSec)
        assertTrue(s.wakeConfirmed)
        assertEquals(11L * MIN + 6 * HOUR, s.inBedSec)
        assertEquals(100.0 * 6 * HOUR / (11 * MIN + 6 * HOUR), s.efficiencyPct, 1e-9)
        assertEquals(listOf(Stretch(s.startTs, s.endTs, 6L * HOUR, 0L)), s.stretches)
    }

    @Test
    fun timeUpInTheMiddleOfASleepCountsAsSleepAndIsReportedAsRestless() {
        // Stirs in the night; the strap takes a while to call it sleep again, and never calls it awake.
        val s = StrapSleeps.find(
            minutes(t0, ASLEEP to 2 * HOUR, UP to 1230, ASLEEP to 3 * HOUR, UP to 630, AWAKE to HOUR),
        ).single()
        assertEquals(5L * HOUR + 1230, s.asleepSec)
        assertEquals(1230L, s.restlessSec)
        assertEquals(0L, s.awakeSec)
        assertEquals(t0, s.startTs)
        assertEquals(t0 + 5 * HOUR + 1230 - 1, s.endTs)
        assertEquals(630L, s.upAfterSec)
        assertEquals(1, s.stretches.size)
    }

    @Test
    fun aLongRestlessEndCountsAsSleepUntilTheWearerGotUp() {
        // Two restless hours at the end of the night, then up: the strap says awake ten and a half minutes later.
        val s = StrapSleeps.find(minutes(t0, ASLEEP to 5 * HOUR, UP to 2 * HOUR, AWAKE to HOUR)).single()
        assertEquals(t0 + 7 * HOUR - 630 - 1, s.endTs)
        assertEquals(7L * HOUR - 630, s.asleepSec)
        assertEquals(2L * HOUR - 630, s.restlessSec)
        assertEquals(630L, s.upAfterSec)
        assertTrue(s.wakeConfirmed)
        assertEquals(0L, s.awakeSec)
    }

    @Test
    fun aRestlessEndIsNotCountedUntilTheStrapHasCalledTheWearerAwake() {
        // The data stops while the strap is still undecided: the strap came off, or the night is not over.
        for (after in listOf(arrayOf(), arrayOf(NO_DATA to HOUR, AWAKE to HOUR))) {
            val s = StrapSleeps.find(minutes(t0, ASLEEP to 5 * HOUR, UP to 2 * HOUR, *after)).single()
            assertEquals(t0 + 5 * HOUR - 1, s.endTs)
            assertEquals(5L * HOUR, s.asleepSec)
            assertEquals(0L, s.restlessSec)
            assertEquals(2L * HOUR, s.upAfterSec)
            assertFalse(s.wakeConfirmed)
        }
    }

    @Test
    fun aWakingThatFallsOnAMinuteBoundaryIsStillSeen() {
        // "Up" runs to the last second of a minute and "awake" starts the next one.
        val s = StrapSleeps.find(minutes(t0, ASLEEP to HOUR, UP to 20 * MIN, AWAKE to 5 * MIN)).single()
        assertTrue(s.wakeConfirmed)
        assertEquals(t0 + HOUR + 20 * MIN - 630 - 1, s.endTs)
        assertEquals(20L * MIN - 630, s.restlessSec)
    }

    @Test
    fun aStillEveningIsNotASleep() {
        assertEquals(
            emptyList<StrapSleep>(),
            StrapSleeps.find(minutes(t0, STILL to 40 * MIN, AWAKE to 10 * MIN, STILL to 30 * MIN, AWAKE to HOUR)),
        )
    }

    @Test
    fun aRunOfAsleepShorterThanTwentyMinutesIsIgnored() {
        assertTrue(StrapSleeps.find(minutes(t0, ASLEEP to 20 * MIN - 1, UP to 630)).isEmpty())
        assertEquals(1, StrapSleeps.find(minutes(t0, ASLEEP to 20 * MIN, UP to 630)).size)
        // A blip of "asleep" when the strap comes back after being off the wrist does not extend the night.
        val s = StrapSleeps.find(
            minutes(t0, ASLEEP to 4 * HOUR, NO_DATA to 30 * MIN, ASLEEP to 40, UP to 630, AWAKE to HOUR),
        ).single()
        assertEquals(t0 + 4 * HOUR - 1, s.endTs)
        assertEquals(0L, s.upAfterSec)
        assertFalse(s.wakeConfirmed)
    }

    @Test
    fun aBreakInTheDataOfFiveMinutesIsBridgedAndALongerOneIsNot() {
        val bridged = StrapSleeps.find(minutes(t0, ASLEEP to HOUR, NO_DATA to 5 * MIN, ASLEEP to HOUR)).single()
        assertEquals(1, bridged.stretches.size)
        assertEquals(2L * HOUR, bridged.asleepSec)
        assertEquals(5L * MIN, bridged.awakeSec)

        // Two stretches, still one sleep: they are far less than ninety minutes apart.
        val split = StrapSleeps.find(minutes(t0, ASLEEP to HOUR, NO_DATA to 6 * MIN, ASLEEP to HOUR)).single()
        assertEquals(2, split.stretches.size)
        assertEquals(2L * HOUR, split.asleepSec)
        assertEquals(6L * MIN, split.awakeSec)
    }

    @Test
    fun stretchesLessThanNinetyMinutesApartAreOneSleepAndFurtherApartAreTwo() {
        fun night(awakeMin: Int) = StrapSleeps.find(
            minutes(t0, ASLEEP to HOUR, UP to 630, AWAKE to awakeMin * MIN, STILL to 10 * MIN, ASLEEP to 2 * HOUR, UP to 630),
        )
        val one = night(awakeMin = 60).single()
        assertEquals(2, one.stretches.size)
        assertEquals(3L * HOUR, one.asleepSec)
        assertEquals(630L + 60 * MIN + 10 * MIN, one.awakeSec)
        assertEquals(t0, one.bedStartTs)

        val two = night(awakeMin = 120)
        assertEquals(2, two.size)
        assertEquals(1L * HOUR, two[0].asleepSec)
        assertEquals(2L * HOUR, two[1].asleepSec)
        // The second sleep's time in bed starts with its own ten still minutes.
        assertEquals(two[1].startTs - 10 * MIN, two[1].bedStartTs)
    }

    @Test
    fun theTimeInBedReachesBackOverUnbrokenStillnessAndAtMostAnHour() {
        val long = StrapSleeps.find(minutes(t0, STILL to 90 * MIN, ASLEEP to HOUR)).single()
        assertEquals(long.startTs - 60 * MIN, long.bedStartTs)

        val broken = StrapSleeps.find(
            minutes(t0, STILL to 20 * MIN, AWAKE to 2 * MIN, STILL to 10 * MIN, ASLEEP to HOUR),
        ).single()
        assertEquals(broken.startTs - 10 * MIN, broken.bedStartTs)

        val none = StrapSleeps.find(minutes(t0, AWAKE to 5 * MIN, ASLEEP to HOUR)).single()
        assertEquals(none.startTs, none.bedStartTs)
    }

    @Test
    fun theOrderTheMinutesArriveInDoesNotMatter() {
        val script = minutes(
            t0 + 7, STILL to 11 * MIN, ASLEEP to 3 * HOUR, UP to 20 * MIN, ASLEEP to 2 * HOUR, UP to 630,
            AWAKE to 6 * HOUR, STILL to 10 * MIN, ASLEEP to 45 * MIN, UP to 630,
        )
        val inOrder = StrapSleeps.find(script)
        assertEquals(2, inOrder.size)
        assertEquals(inOrder, StrapSleeps.find(script.reversed()))
        assertEquals(inOrder, StrapSleeps.find(script.shuffled(java.util.Random(7))))
    }
}
