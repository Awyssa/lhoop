// Fork stand-in: NO-OP. Replaces notif/StaleBatteryWorker.kt (upstream commit
// f36b82d22a87f88e81532dc76016943f33764a0b). The periodic stale-battery warning is removed in this
// fork: nothing is scheduled. Upstream's class is a WorkManager CoroutineWorker; only its
// ensureScheduled entry point is called by kept code (LhoopApplication.onCreate).
package com.lhoop.notif

import android.content.Context

object StaleBatteryWorker {
    fun ensureScheduled(context: Context) {}
}
