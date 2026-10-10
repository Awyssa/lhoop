// Fork-owned. The part of starting the core that needs no Activity, and two things of the app's own
// that upstream never had: bringing the foreground service back when the app is opened, and bringing
// the strap link back after a phone restart.
//
// The first four functions are FoundationDriver's, moved here unchanged so that a phone restart drives
// the strap through exactly the steps a launch does. Their source is upstream ui/AppViewModel.kt at
// commit 6ce65730; the numbers are lines in that file. Each touches only process-scoped objects
// (the Bluetooth client, the source coordinator, saved preferences). Everything with a coroutine or an
// Activity stays in the driver.
//
// Nothing here changes what is sent to the strap. A restart runs the launch sequence at a new moment,
// and a resume starts a service.
package fork.app

import com.lhoop.LhoopApplication
import com.lhoop.ble.WhoopConnectionService
import com.lhoop.ui.LhoopPrefs

internal object StrapStartup {

    /** Whether [reconnectSaved] has run in this process, from a launch or from a restart. */
    @Volatile
    private var launchSequenceRan = false

    /**
     * 872: reconcile the live source against the registry's active device. For a WHOOP this pins the
     * connection to the row's saved address and points sample storage at the row's id.
     */
    fun reconcileSource(app: LhoopApplication) {
        app.sourceCoordinator.start()
    }

    /** 880: re-arm the opt-in rolling strap-log file after a process restart. */
    fun rearmDetailedCapture(app: LhoopApplication) {
        if (LhoopPrefs.detailedCapture(app.applicationContext)) app.ble.setDetailedCapture(true)
    }

    /** 1365 and 1368. The first is pushed BEFORE the launch reconnect so that reconnect arms the stream once bonded. */
    fun pushLinkSettings(app: LhoopApplication) {
        app.ble.setKeepStreamForData(continuousHrvEffective(app))
        applyPowerSaving(app)
    }

    /** 1372, then 1462-1486: reconnect directly to the strap last bonded to, and re-promote the service. */
    fun reconnectSaved(app: LhoopApplication) {
        launchSequenceRan = true
        val appContext = app.applicationContext
        val saved = LhoopPrefs.lastDevice(appContext) ?: return
        if (!LhoopPrefs.backgroundConnection(appContext)) return
        WhoopConnectionService.start(appContext)
        app.ble.reconnectToAddress(saved.first, saved.second)
    }

    /** 1381-1410: push the saved power and sync-speed settings to the Bluetooth client. */
    private fun applyPowerSaving(app: LhoopApplication) {
        val appContext = app.applicationContext
        val ble = app.ble
        val on = LhoopPrefs.powerSaving(appContext)
        ble.setConnectionPriorityManagement(
            enabled = LhoopPrefs.fastHistorySync(appContext),
            idleThrottleBatteryPct = LhoopPrefs.idleThrottleBatteryPct(appContext),
        )
        ble.setFastLinkPhy(LhoopPrefs.fastLinkPhy(appContext))
        ble.setLowRefreshMode(on && LhoopPrefs.lowRefresh(appContext))
        ble.setLowBatteryOffloadThrottle(if (on) LhoopPrefs.powerSavingBatteryPct(appContext) else 0)
        ble.setPauseCaptureOnPowerSave(
            on && LhoopPrefs.pauseHrvOnPowerSave(appContext),
            LhoopPrefs.powerSavingBatteryPct(appContext),
        )
    }

    /** 1453-1454: the "Continuous HRV capture" preference AND "Keep connected in the background". */
    private fun continuousHrvEffective(app: LhoopApplication): Boolean =
        LhoopPrefs.continuousHrv(app.applicationContext) && LhoopPrefs.backgroundConnection(app.applicationContext)

    // --- The app's own additions ---------------------------------------------------------------------

    /**
     * After a phone restart (BootReceiver): the launch sequence, with no Activity. Nothing runs when a
     * launch has already run it in this process, so a broadcast that arrives after the owner opened the
     * app cannot undo a Disconnect. Nothing runs either without a saved strap or with "Keep connected in
     * the background" off, and that is decided before the Bluetooth client is touched.
     *
     * When the app is opened later, the driver runs the same four steps again against the live client,
     * as it already does when the app is swiped away and reopened: the reconnect call returns at once
     * on a link that is up.
     */
    fun afterBoot(app: LhoopApplication) {
        if (launchSequenceRan) return
        val appContext = app.applicationContext
        if (LhoopPrefs.lastDevice(appContext) == null || !LhoopPrefs.backgroundConnection(appContext)) return
        reconcileSource(app)
        rearmDetailedCapture(app)
        pushLinkSettings(app)
        reconnectSaved(app)
        MorningWidget.watchSyncs(app)
    }

    /**
     * Every time the app comes to the front: bring the foreground service back if Android took it away
     * while the process lived on, which opening the app did not undo before
     * (fork/docs/08-runbook.md, "Phone settings"). It starts the service only. It never connects: a link
     * that is not deliberately down is either up or already being retried by the client, and a second
     * reconnect call would disturb that retry.
     */
    fun ensureService(app: LhoopApplication) {
        val appContext = app.applicationContext
        val start = shouldStartService(
            strapSaved = LhoopPrefs.lastDevice(appContext) != null,
            backgroundConnection = LhoopPrefs.backgroundConnection(appContext),
            intentionallyDisconnected = app.ble.intentionallyDisconnected,
            serviceInForeground = connectionServiceRunning(appContext),
        )
        if (start) WhoopConnectionService.start(appContext)
    }

    /**
     * The service is started on a resume only when all four hold. A Disconnect, from the Strap tab or
     * from the notification, leaves [intentionallyDisconnected] set until Connect, so it stays down.
     * A service already in the foreground is left alone: starting it again would re-post its
     * notification and restart its collectors on every resume.
     */
    fun shouldStartService(
        strapSaved: Boolean,
        backgroundConnection: Boolean,
        intentionallyDisconnected: Boolean,
        serviceInForeground: Boolean,
    ): Boolean = strapSaved && backgroundConnection && !intentionallyDisconnected && !serviceInForeground
}
