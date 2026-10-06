// Fork stand-in: NO-OP. Replaces widget/WidgetSnapshot.kt (WidgetSnapshot + WidgetSnapshotStore)
// and the StressPoint type from widget/StressTrace.kt (upstream commit
// f36b82d22a87f88e81532dc76016943f33764a0b). Home-screen widgets are removed in this fork: a pushed
// snapshot is dropped, nothing is written to the "lhoop_widget" preferences and no Glance widget is
// asked to recompose.
package com.lhoop.widget

import android.content.Context

/** One point of the widget's stress curve. Declaration copied from upstream StressTrace.kt:14; kept code
 *  only passes lists of it through. */
data class StressPoint(val ts: Long, val level: Double?, val moving: Boolean = false)

/** The fields ble/WhoopConnectionService.kt fills in, with upstream's names, types and defaults.
 *  Upstream's heartRateStale and hrSeries are omitted (no kept code sets them). */
data class WidgetSnapshot(
    val recoveryPct: Int? = null,
    val restPct: Int? = null,
    val effortPct: Int? = null,
    val heartRate: Int? = null,
    val batteryPct: Int? = null,
    val connected: Boolean = false,
    val stressSeries: List<StressPoint> = emptyList(),
    val stressDay: Long? = null,
    val updatedAtMs: Long = 0L,
)

object WidgetSnapshotStore {
    suspend fun push(context: Context, snap: WidgetSnapshot) {}

    /** Upstream: true/false when a stress widget is / is not placed, null when the widget host did not
     *  answer. No widget can be placed in this fork, so the settled "no". */
    suspend fun stressWidgetPlacement(context: Context): Boolean? = false

    fun noteStressScored(context: Context, atMs: Long) {}
}
