// Fork stand-in: NO-OP. Replaces widget/StressWidgetRefresh.kt (upstream commit
// 6ce65730). The periodic stress-widget rescore is removed in this
// fork: nothing is scheduled with WorkManager.
package com.lhoop.widget

import android.content.Context

object StressWidgetRefresh {
    fun ensureScheduled(context: Context) {}
}
