// Fork stand-in: NO-OP. Replaces alarm/SmartAlarmStore.kt (upstream commit
// f36b82d22a87f88e81532dc76016943f33764a0b). The phone smart alarm is removed in this fork: the store
// always reads "disabled, nothing scheduled" and does NOT open upstream's "lhoop_smart_alarm"
// preferences, so a smart alarm enabled under the upstream app is ignored rather than honoured.
package com.lhoop.alarm

import android.content.Context

class SmartAlarmStore private constructor() {
    val enabled: Boolean get() = false
    val scheduledDeadlineMs: Long get() = 0L
    val scheduledWindowStartMs: Long get() = 0L

    companion object {
        fun from(context: Context): SmartAlarmStore = SmartAlarmStore()
    }
}
