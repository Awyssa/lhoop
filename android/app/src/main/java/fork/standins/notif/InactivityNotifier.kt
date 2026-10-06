// Fork stand-in: NO-OP. Replaces notif/InactivityNotifier.kt (upstream commit
// 6ce65730). The phone notification that mirrors the strap's
// inactivity buzz is removed in this fork. The strap buzz itself lives in ble/WhoopBleClient.kt and is
// untouched.
package com.lhoop.notif

import android.content.Context

object InactivityNotifier {
    fun onNudged(context: Context, minutes: Int) {}
}
