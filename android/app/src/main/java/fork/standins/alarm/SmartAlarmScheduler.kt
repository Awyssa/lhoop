// Fork stand-in: NO-OP. Replaces alarm/SmartAlarmScheduler.kt (upstream commit
// 6ce65730). The phone smart alarm is removed in this fork: no exact
// alarm is ever scheduled, moved or cancelled through AlarmManager.
package com.lhoop.alarm

import android.content.Context

object SmartAlarmScheduler {
    fun canScheduleExact(context: Context): Boolean = false

    fun advanceTo(context: Context, store: SmartAlarmStore, fireAtMs: Long) {}
}
