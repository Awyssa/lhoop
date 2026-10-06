// Fork stand-in: NO-OP. Replaces widget/StressWidgetProducer.kt (upstream commit
// f36b82d22a87f88e81532dc76016943f33764a0b). The stress widget is removed in this fork, so the
// connection service never scores a stress curve for it: shouldRescore is always false, which keeps
// the three day-sized reads (heart rate, R-R, gravity) off the live-state collector entirely.
package com.lhoop.widget

import com.lhoop.data.WhoopRepository
import java.time.ZoneId

internal object StressWidgetProducer {

    data class Curve(
        val points: List<StressPoint>,
        val epochDay: Long,
        val activityMaskedHours: Int,
    )

    /** Same values as upstream. RESCORE_INTERVAL_MS seeds a private constant in
     *  ble/WhoopConnectionService.kt; it is not read while shouldRescore stays false. */
    const val RESCORE_RETRY_MS: Long = 60L * 1000L
    const val RESCORE_INTERVAL_MS: Long = 15L * 60L * 1000L

    /** Never: there is no widget to score for. */
    fun shouldRescore(nowMs: Long, lastScoreAtMs: Long, intervalMs: Long): Boolean = false

    /** Unreachable while shouldRescore is false. Returns the stamp unchanged if ever called. */
    fun stampAfterAttempt(
        nowMs: Long,
        producedCurve: Boolean,
        widgetPlaced: Boolean?,
        intervalMs: Long,
        retryMs: Long = RESCORE_RETRY_MS,
    ): Long = nowMs

    /** Unreachable while shouldRescore is false. Null is upstream's "say nothing about stress". */
    suspend fun todayCurve(
        repo: WhoopRepository,
        deviceId: String?,
        personalBaseline: Boolean = false,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Curve? = null
}
