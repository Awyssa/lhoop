// Fork stand-in: NO-OP. Replaces push/SelfHostedPushScheduler.kt (upstream commit
// f36b82d22a87f88e81532dc76016943f33764a0b). The opt-in self-hosted push is removed in this fork:
// a completed history offload queues nothing and no network work is ever scheduled.
package com.lhoop.push

import android.content.Context

object SelfHostedPushScheduler {
    fun enqueueAfterSuccessfulOffload(context: Context) {}
}
