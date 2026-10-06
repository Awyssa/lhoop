// Fork stand-in: NO-OP. Replaces notif/StaleBatteryWorker.kt (upstream commit
// 6ce65730). The periodic stale-battery warning is removed in this
// fork: nothing is scheduled. Upstream's class is a WorkManager CoroutineWorker; only its
// ensureScheduled entry point is called by kept code (LhoopApplication.onCreate).
package com.lhoop.notif

import android.content.Context

object StaleBatteryWorker {
    fun ensureScheduled(context: Context) {}
}
