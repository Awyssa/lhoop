// Fork stand-in: NO-OP. Replaces notif/BatteryAlertNotifier.kt (upstream commit
// 6ce65730). Strap-battery notifications are removed in this fork.
// Signatures match the four entry points ble/WhoopConnectionService.kt calls; each does nothing.
package com.lhoop.notif

import android.content.Context

object BatteryAlertNotifier {
    fun onRuntimeEstimate(context: Context, remainingHours: Double?, charging: Boolean?) {}

    fun onBatteryUpdate(context: Context, currPct: Int?, charging: Boolean?) {}

    fun onCriticalBattery(context: Context, currPct: Int?, charging: Boolean?) {}

    fun onBedtimeRunway(
        context: Context,
        nowSecOfDay: Int,
        habitualMidsleepSec: Int?,
        typicalSleepHours: Double?,
        usableRemainingHours: Double?,
        charging: Boolean?,
    ) {}
}
