// Fork stand-in: NO-OP. Replaces widget/StressWidgetRefresh.kt (upstream commit
// f36b82d22a87f88e81532dc76016943f33764a0b). The periodic stress-widget rescore is removed in this
// fork: nothing is scheduled with WorkManager.
package com.lhoop.widget

import android.content.Context

object StressWidgetRefresh {
    fun ensureScheduled(context: Context) {}
}
