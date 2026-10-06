// Fork stand-in: NO-OP. Replaces alarm/SmartAlarmScheduler.kt (upstream commit
// f36b82d22a87f88e81532dc76016943f33764a0b). The phone smart alarm is removed in this fork: no exact
// alarm is ever scheduled, moved or cancelled through AlarmManager.
package com.lhoop.alarm

import android.content.Context

object SmartAlarmScheduler {
    fun canScheduleExact(context: Context): Boolean = false

    fun advanceTo(context: Context, store: SmartAlarmStore, fireAtMs: Long) {}
}
