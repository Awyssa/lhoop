// Fork stand-in: NO-OP. Replaces notif/IllnessAlertNotifier.kt (upstream commit
// 6ce65730). The illness early-warning notification is removed in
// this fork. Nothing is posted and, unlike upstream, the raised/clear edge is NOT persisted to
// LhoopPrefs (KEY_ILLNESS_WAS_RAISED / KEY_ILLNESS_LAST_NOTIFIED_DAY are left untouched).
package com.lhoop.notif

import android.content.Context
import com.lhoop.data.DailyMetric

object IllnessAlertNotifier {
    /** Upstream appends the two-day window to the alert text; with no notification to build, the alert
     *  is handed back unchanged (the value upstream returns when fewer than two days exist). */
    fun withWindow(context: Context, alert: String, days: List<DailyMetric>): String = alert

    fun onEvaluated(context: Context, alert: String?) {}
}
