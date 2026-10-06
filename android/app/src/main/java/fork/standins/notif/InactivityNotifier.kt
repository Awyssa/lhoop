// Fork stand-in: NO-OP. Replaces notif/InactivityNotifier.kt (upstream commit
// f36b82d22a87f88e81532dc76016943f33764a0b). The phone notification that mirrors the strap's
// inactivity buzz is removed in this fork. The strap buzz itself lives in ble/WhoopBleClient.kt and is
// untouched.
package com.lhoop.notif

import android.content.Context

object InactivityNotifier {
    fun onNudged(context: Context, minutes: Int) {}
}
