// Fork stand-in: NO-OP. Replaces push/SelfHostedPushScheduler.kt (upstream commit
// 6ce65730). The opt-in self-hosted push is removed in this fork:
// a completed history offload queues nothing and no network work is ever scheduled.
package com.lhoop.push

import android.content.Context

object SelfHostedPushScheduler {
    fun enqueueAfterSuccessfulOffload(context: Context) {}
}
