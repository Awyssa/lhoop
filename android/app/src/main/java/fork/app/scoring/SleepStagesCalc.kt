// Fork-owned. How much of a sleep was deep and how much REM.
//
// The split is the original engine's: its default stager (analytics/SleepStagerV2.kt) is run once over
// the sleep the strap flagged, the way SleepVitalsCalc runs the engine's HRV arithmetic over it. Two
// things differ from the engine's own stored stages. The bounds are the strap's, not the engine's sleep
// detection. And asleep-or-awake stays the strap's call: where the stager says "wake" inside a stretch
// the strap counted as sleep, that time is light sleep, which is also what the engine's own wake veto
// does (SleepStager.bandVetoRecoverStage).
//
// And one of the stager's constants is ours: [REM_RISE].
//
// These are estimates. Deep comes out near the same share of every sleep, by how the stager finds it.
// The whole account is in fork/docs/04-sleep-recovery-engine.md.
package fork.app.scoring

import com.lhoop.analytics.SleepStagerV2
import com.lhoop.analytics.StageSegment
import com.lhoop.data.GravitySample
import com.lhoop.data.HrSample
import com.lhoop.data.RrInterval
import kotlin.math.roundToLong

/** Seconds of deep and of REM in a sleep. */
data class SleepStages(val deepSec: Long, val remSec: Long)

object SleepStagesCalc {

    /** The stager looks this far before a sleep and after it, so the rows passed in should reach as far. */
    const val PAD_BEFORE_SEC = 360L
    const val PAD_AFTER_SEC = 420L

    /**
     * How strongly the stager favours REM as the sleep goes on: half the engine's own
     * [SleepStagerV2.REM_RISE].
     *
     * The engine adds this rise whatever the signals say, and at full strength it carried most of the
     * REM it called: over the first eight sleeps recorded, the app's REM share was far above the
     * wearer's own long-run share in 240 nights of WHOOP history, and about one and a half times a
     * second device's on the two nights both wore. Halved, the app's average sits with those two
     * references. It is the average that was set this way, not any one night: whether a night with
     * more REM reads higher is still to be shown. The owner's decision, 2026-10-10.
     */
    const val REM_RISE = 0.5

    /**
     * [grav], [hr] and [rr] must cover the sleep, with the padding above where there is data, in time
     * order. Null when there is no motion or heart rate to stage from.
     */
    fun compute(sleep: StrapSleep, grav: List<GravitySample>, hr: List<HrSample>, rr: List<RrInterval>): SleepStages? {
        if (grav.isEmpty() || hr.isEmpty()) return null
        return fromSegments(sleep, SleepStagerV2.stageSession(sleep.startTs, sleep.endTs + 1, grav, hr, rr, emptyList(), REM_RISE))
    }

    /**
     * Reads the stager's [segments] inside the sleep's stretches only, and applies the shares found there
     * to the time asleep: a stretch can hold short breaks the strap did not count, so its length is not
     * the time asleep. Null for a hypnogram of one segment, which is the stager saying it had too little
     * to work with.
     */
    fun fromSegments(sleep: StrapSleep, segments: List<StageSegment>): SleepStages? {
        if (segments.size < 2) return null
        var deep = 0L
        var rem = 0L
        var staged = 0L
        for (stretch in sleep.stretches) {
            for (segment in segments) {
                val overlap = minOf(segment.end, stretch.endTs + 1) - maxOf(segment.start, stretch.startTs)
                if (overlap <= 0) continue
                staged += overlap
                when (segment.stage) {
                    "deep" -> deep += overlap
                    "rem" -> rem += overlap
                }
            }
        }
        if (staged <= 0) return null
        return SleepStages(
            deepSec = (sleep.asleepSec * deep.toDouble() / staged).roundToLong(),
            remSec = (sleep.asleepSec * rem.toDouble() / staged).roundToLong(),
        )
    }
}
