// Fork-owned. After a phone restart, brings the strap link back without the app being opened.
//
// Upstream had nothing like it: there, nothing ran until the app was opened once. The work is
// StrapStartup.afterBoot, which runs the same steps a launch does.
//
// What Android allows here, as far as is known and none of it tried on a device before this was
// written: the broadcast arrives only after the phone is first unlocked (the saved strap and the
// database are in storage that is locked until then), so an unattended overnight restart records
// nothing until the owner unlocks it, and the strap keeps its records meanwhile. Nothing reaches an app
// that was force-stopped, or one restricted in the background. A receiver of this broadcast may start
// a foreground service of the connected-device type.
package fork.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lhoop.LhoopApplication

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        // Whatever goes wrong here must not take the process down: the app then simply waits to be opened, as before.
        runCatching { StrapStartup.afterBoot(context.applicationContext as LhoopApplication) }
    }
}
