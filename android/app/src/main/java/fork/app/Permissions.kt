// Fork-owned. The runtime permission sets upstream's UI layer requested, at commit
// 6ce65730.
package fork.app

import android.Manifest
import android.os.Build

/**
 * What a Bluetooth scan needs on this OS version: upstream `ui/BlePermissions.kt` lines 18-22. Android 12+
 * uses the granular Bluetooth permissions; older versions need fine location before scan results arrive.
 */
internal fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/**
 * What an already-onboarded launch requested in one prompt: upstream `ui/MainActivity.kt` lines 162-178.
 * The Bluetooth set, plus (Android 13+) the notification permission the foreground service's ongoing
 * notification needs.
 */
internal fun launchPermissions(): Array<String> = buildList {
    addAll(blePermissions())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()
