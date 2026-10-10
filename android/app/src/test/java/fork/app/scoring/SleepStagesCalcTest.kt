package fork.app.scoring

import com.lhoop.analytics.SleepStagerV2
import com.lhoop.analytics.StageSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The arithmetic that turns a hypnogram into deep and REM for a sleep. The stager itself is the engine's and is tested there. */
class SleepStagesCalcTest {

    private fun sleep(vararg stretches: Stretch, asleepSec: Long = stretches.sumOf { it.asleepSec }) = StrapSleep(
        bedStartTs = stretches.first().startTs - 300,
        startTs = stretches.first().startTs,
        endTs = stretches.last().endTs,
        asleepSec = asleepSec,
        restlessSec = 0,
        upAfterSec = 0,
        wakeConfirmed = true,
        stretches = stretches.toList(),
    )

    /** An hour: a quarter light, a quarter deep, a quarter REM, five minutes the stager calls wake, ten more light. */
    private val hour = listOf(
        StageSegment(1_000, 1_900, "light"),
        StageSegment(1_900, 2_800, "deep"),
        StageSegment(2_800, 3_700, "rem"),
        StageSegment(3_700, 4_000, "wake"),
        StageSegment(4_000, 4_600, "light"),
    )

    @Test
    fun deepAndRemAreTheirSharesOfTheTimeAsleep() {
        val stages = SleepStagesCalc.fromSegments(sleep(Stretch(1_000, 4_599, 3_600, 0)), hour)!!
        assertEquals(900, stages.deepSec)
        assertEquals(900, stages.remSec)
    }

    @Test
    fun whatTheStagerCallsWakeInsideASleepIsLightSoDeepRemAndLightAddUpToTheTimeAsleep() {
        val s = sleep(Stretch(1_000, 4_599, 3_600, 0))
        val stages = SleepStagesCalc.fromSegments(s, hour)!!
        // The five minutes of "wake" are in neither figure, so they fall to light with the rest.
        assertEquals(1_800, s.asleepSec - stages.deepSec - stages.remSec)
    }

    @Test
    fun theSharesApplyToTheTimeAsleepWhenAStretchHoldsBreaksTheStrapDidNotCount() {
        val stages = SleepStagesCalc.fromSegments(sleep(Stretch(1_000, 4_599, 3_000, 0)), hour)!!
        assertEquals(750, stages.deepSec)
        assertEquals(750, stages.remSec)
    }

    @Test
    fun onlyWhatFallsInsideTheStretchesIsRead() {
        // Two stretches with ten minutes awake between them, which the stager happened to call deep.
        val s = sleep(Stretch(1_000, 1_899, 900, 0), Stretch(2_500, 3_399, 900, 0))
        val segments = listOf(
            StageSegment(1_000, 1_900, "light"),
            StageSegment(1_900, 2_500, "deep"),
            StageSegment(2_500, 3_400, "rem"),
        )
        val stages = SleepStagesCalc.fromSegments(s, segments)!!
        assertEquals(0, stages.deepSec)
        assertEquals(900, stages.remSec)
    }

    @Test
    fun aHypnogramOfOneSegmentIsTheStagerHavingTooLittleAndGivesNothing() {
        assertNull(SleepStagesCalc.fromSegments(sleep(Stretch(1_000, 4_599, 3_600, 0)), listOf(StageSegment(1_000, 4_600, "light"))))
        assertNull(SleepStagesCalc.fromSegments(sleep(Stretch(1_000, 4_599, 3_600, 0)), emptyList()))
    }

    @Test
    fun theAppHalvesTheEnginesRiseInRemAndChangesNothingElse() {
        assertEquals(1.0, SleepStagerV2.REM_RISE, 0.0)
        assertEquals(0.5, SleepStagesCalc.REM_RISE, 0.0)
        // Late in a sleep, with the latency guard spent: the engine's own leaning toward each stage, and the app's.
        val engine = SleepStagerV2.cyclePrior(0.8, Double.POSITIVE_INFINITY)
        val app = SleepStagerV2.cyclePrior(0.8, Double.POSITIVE_INFINITY, SleepStagesCalc.REM_RISE)
        assertEquals(0.8, engine.getValue("rem"), 1e-12)
        assertEquals(0.4, app.getValue("rem"), 1e-12)
        for (stage in listOf("deep", "light", "awake")) assertEquals(stage, engine.getValue(stage), app.getValue(stage), 0.0)
        // Early on, the guard against REM straight after falling asleep is the engine's own, untouched.
        assertEquals(
            SleepStagerV2.cyclePrior(0.0, 0.0).getValue("rem"),
            SleepStagerV2.cyclePrior(0.0, 0.0, SleepStagesCalc.REM_RISE).getValue("rem"),
            0.0,
        )
    }

    @Test
    fun withNoMotionOrHeartRateThereIsNothingToStageFrom() {
        assertNull(SleepStagesCalc.compute(sleep(Stretch(1_000, 4_599, 3_600, 0)), emptyList(), emptyList(), emptyList()))
    }
}
