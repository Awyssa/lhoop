// Fork-owned. HRV and resting heart rate for a sleep found in the strap's state.
//
// The arithmetic is the core's own, called on the strap's bounds instead of the core's sessions:
// SleepStager.sessionHrvWindows (five-minute windows, each cleaned and needing 20 clean beats),
// SleepStager.sessionHrvOverCounted (refuses beats that add up to more time than the clock allows) and
// SleepStager.sessionRestingHR (the lowest five-minute mean). So when the bounds agree, the figures do.
//
// Only the unbroken stretches are read. Time awake between two stretches of one sleep is left out.
package fork.app.scoring

import com.lhoop.analytics.SleepStager
import com.lhoop.data.HrSample
import com.lhoop.data.RrInterval

object SleepVitalsCalc {

    /** How much of the end of the sleep the late figure covers. HRV usually climbs through the night. */
    const val LATE_HOURS = 3

    /** Fewer usable five-minute windows than this and there is no HRV figure. */
    const val MIN_WINDOWS = 6

    /** [hr] and [rr] must cover the sleep and be in time order, as the repository returns them. */
    fun compute(sleep: StrapSleep, hr: List<HrSample>, rr: List<RrInterval>): SleepVitals {
        val overCounted = sleep.stretches.any { SleepStager.sessionHrvOverCounted(it.startTs, it.endTs, rr) }
        val windows = if (overCounted) emptyList() else sleep.stretches.flatMap { stretch ->
            SleepStager.sessionHrvWindows(stretch.startTs, stretch.endTs, rr, emptyList())
                .mapNotNull { w -> w.rmssd?.let { w.startTs to it } }
        }
        val lateFrom = sleep.endTs - LATE_HOURS * 3600L
        val late = windows.filter { it.first >= lateFrom }
        return SleepVitals(
            hrvMs = windows.takeIf { it.size >= MIN_WINDOWS }?.map { it.second }?.average(),
            lateHrvMs = late.takeIf { it.size >= MIN_WINDOWS }?.map { it.second }?.average(),
            hrvWindows = windows.size,
            restingHr = sleep.stretches.mapNotNull { SleepStager.sessionRestingHR(it.startTs, it.endTs, hr) }.minOrNull(),
        )
    }
}
