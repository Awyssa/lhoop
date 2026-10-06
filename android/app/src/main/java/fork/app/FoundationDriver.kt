// Fork-owned. Starts and drives the kept foundation (com.lhoop.ble / data / analytics) for a WHOOP strap,
// reproducing what upstream's deleted UI layer did as a consequence of the app being opened or running.
//
// Source of every block: upstream ui/AppViewModel.kt at commit
// 6ce65730 (line numbers below are in that file unless a file is named).
// Same order, same conditions, same arguments, same preference accessors. Nothing here is new behaviour.
//
// Scope: an already-onboarded install with one paired WHOOP. Deliberately NOT reproduced (upstream lines):
//   Oura / other sources' UI state (200-215, 389-404), demo seeding, importers and Health Connect
//   (1350-1353, 2537-2644), AI coach, widgets (1070-1122), phone and strap alarms (888, 904-906, 971-988,
//   2692-3039), notifications (1008-1050), workouts and GPS (1502-1849, 2567-2575), HR broadcast to gym
//   kit (2494-2535), HR-zone haptic coaching and the double-tap action (901-902, 3063-3174), and every
//   display-only flow (smoothed bpm, today's row, streaks, v5 signals).
//
// It is an Activity-scoped ViewModel because upstream's driver was one: it starts when the first screen
// composes, survives rotation, and is cleared with the Activity. Auto-start at boot does not exist
// upstream and is not added here.
package fork.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lhoop.BuildConfig
import com.lhoop.LhoopApplication
import com.lhoop.ble.WhoopConnectionService
import com.lhoop.ble.WhoopModel
import com.lhoop.data.AppVersionEvent
import com.lhoop.data.WhoopDatabase
import com.lhoop.data.WhoopSerialIdentity
import com.lhoop.ui.LhoopPrefs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class FoundationDriver(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext               // 121
    private val lhoopApp = app as LhoopApplication                  // 125
    private val repository = lhoopApp.repository                   // 128
    val ble = lhoopApp.ble                                         // 132

    /** The active strap source id, resolved once per driver exactly as upstream did (476). */
    private val deviceId = lhoopApp.activeDeviceId

    /**
     * Which strap family a scan-based connect looks for (555-557). Upstream held this in a picker that
     * the user could change; with no picker it stays at the value upstream seeded on every process
     * start: the family service discovery recorded, else the remembered last-device pair, else WHOOP4.
     */
    private val selectedModel: WhoopModel =
        resolveSelectedModel(lhoopApp.persistedWhoopModelOrNull(), LhoopPrefs.lastDevice(appContext)?.second)

    private val scoring = ScoringPass(lhoopApp, deviceId)

    /** Wakes the scoring loop early on an app resume (842). Conflated: a kick sent mid-pass is kept. */
    private val analyzeKick = Channel<Unit>(Channel.CONFLATED)

    /** Every activity resume: the bond-loop salvage probe, then the scoring kick (852-865). */
    private val resumeCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            ble.salvageProbeIfBondLoopPaused()
            analyzeKick.trySend(Unit)
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    init {
        // 872: reconcile the live source against the registry's active device. For a WHOOP this pins the
        // connection to the row's saved address and points sample storage at the row's id.
        lhoopApp.sourceCoordinator.start()
        // 876: record an APP_VERSION_CHANGED event on the first launch after an update.
        viewModelScope.launch { recordAppVersionChange() }
        // 880: re-arm the opt-in rolling strap-log file after a process restart.
        if (LhoopPrefs.detailedCapture(appContext)) ble.setDetailedCapture(true)
        // 882
        lhoopApp.registerActivityLifecycleCallbacks(resumeCallbacks)
        // 893-920, the bond edge only: remember the strap that bonded so the next launch can reconnect to
        // it directly. Address and model both come from the link that actually bonded (#2068).
        viewModelScope.launch {
            var lastBonded = false
            ble.state.collect { state ->
                if (state.bonded && !lastBonded) {
                    val establishedModel = ble.establishedModel
                    val address = ble.lastDeviceAddress
                    if (establishedModel != null && address != null) {
                        LhoopPrefs.setLastDevice(appContext, address, establishedModel)
                    }
                }
                lastBonded = state.bonded
            }
        }
        // 927-930: feed the connected strap's address to the coordinator (identity adoption, last seen).
        viewModelScope.launch {
            ble.connectedPeripheralAddress
                .collect { addr -> lhoopApp.sourceCoordinator.connectedPeripheralChanged(addr) }
        }
        // 942-970: re-point the pairing onto a stable whoop-<serial> id when the strap reports its serial.
        ble.onSerial = { serial -> viewModelScope.launch { adoptSerialIdentity(serial) } }
        // 1131-1359: one-shot repairs after a grace period, then the fingerprint-gated backstop scoring
        // pass every 30 minutes, woken early by a resume.
        viewModelScope.launch {
            delay(FIRST_OFFLOAD_GRACE_MS)
            scoring.healTimestampsOnUpgrade()
            scoring.repairHistoryOnce()
            while (isActive) {
                scoring.healTimestampsIfFlagged()
                scoring.rescoreIfInputsChanged()
                withTimeoutOrNull(ANALYZE_INTERVAL_MS) { analyzeKick.receive() }
            }
        }
        // 1365: pushed BEFORE the launch reconnect so that reconnect arms the stream once bonded.
        ble.setKeepStreamForData(continuousHrvEffective())
        // 1368
        applyPowerSaving()
        // 1372
        autoReconnectOnLaunch()
    }

    /** 240-262. `"lhoop-app"` is upstream's synthetic, non-strap device id for app-level events. */
    private suspend fun recordAppVersionChange() {
        val prefs = appContext.getSharedPreferences("lhoop_provenance", android.content.Context.MODE_PRIVATE)
        val current = BuildConfig.VERSION_NAME
        val last = prefs.getString("lastSeenVersion", null)
        if (AppVersionEvent.shouldRecord(last, current)) {
            val recorded = runCatching {
                repository.recordEvent(
                    deviceId = "lhoop-app",
                    ts = System.currentTimeMillis() / 1000,
                    kind = AppVersionEvent.KIND,
                    payloadJSON = AppVersionEvent.payloadJson(
                        from = last!!, to = current, schemaVersion = WhoopDatabase.SCHEMA_VERSION,
                    ),
                )
            }.isSuccess
            if (recorded) prefs.edit().putString("lastSeenVersion", current).apply()
        } else {
            prefs.edit().putString("lastSeenVersion", current).apply()
        }
    }

    /** 943-969. */
    private suspend fun adoptSerialIdentity(serial: String) {
        val deviceRegistry = lhoopApp.deviceRegistry
        val serialId = WhoopSerialIdentity.adoptedId(serial)
        val currentId = deviceRegistry.activeDeviceId()
        if (serialId != null && currentId != null && currentId != serialId &&
            WhoopSerialIdentity.mayAdopt(currentId) &&
            deviceRegistry.adoptSerialIdentity(currentId, serialId)
        ) {
            // Prefix only. `serialId` embeds the full serial, which must never reach a shared log.
            ble.logIdentity(
                "WHOOP: adopted stable serial identity (serialPrefix=" +
                    WhoopSerialIdentity.logSafe(serial) +
                    ") - history re-pointed off the transient pairing id (#1303)",
            )
            deviceRegistry.setActive(serialId)
            lhoopApp.onActiveDeviceAdopted(serialId)
            lhoopApp.sourceCoordinator.onActiveDeviceChanged(serialId)
        }
    }

    /** 1381-1410: push the saved power and sync-speed settings to the Bluetooth client. */
    private fun applyPowerSaving() {
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
    private fun continuousHrvEffective(): Boolean =
        LhoopPrefs.continuousHrv(appContext) && LhoopPrefs.backgroundConnection(appContext)

    /** 1462-1486: reconnect directly to the strap last bonded to, and re-promote the service. */
    private fun autoReconnectOnLaunch() {
        val saved = LhoopPrefs.lastDevice(appContext) ?: return
        if (!LhoopPrefs.backgroundConnection(appContext)) return
        WhoopConnectionService.start(appContext)
        ble.reconnectToAddress(saved.first, saved.second)
    }

    /**
     * The Connect button (2313-2329). Upstream took a `promoteService` flag that only onboarding passed
     * as false; an onboarded install always promoted, so the flag is gone.
     */
    fun connect() {
        ble.resetReconnectBackoff()
        ble.clearPairingHintForUserConnect()
        ble.connect(selectedModel)
        if (LhoopPrefs.backgroundConnection(appContext)) {
            WhoopConnectionService.start(appContext)
        }
    }

    /** The Disconnect button (2340-2346, without the display-only bpm reset). */
    fun disconnect() {
        WhoopConnectionService.stop(appContext)
        ble.disconnect()
    }

    /** "Sync now" (2677). */
    fun syncNow() = ble.syncNow()

    /** The "Debug logging" switch (2464-2467). */
    fun setDebugLogging(enabled: Boolean) {
        LhoopPrefs.setDebugLogging(appContext, enabled)
        ble.debugLogcat = enabled
    }

    /** 3176-3193, without the HR-broadcast stop. */
    override fun onCleared() {
        super.onCleared()
        lhoopApp.unregisterActivityLifecycleCallbacks(resumeCallbacks)
        if (!LhoopPrefs.backgroundConnection(appContext)) {
            ble.disconnect()
        }
    }

    private companion object {
        /** Grace before the first scoring pass, letting the first offload land (3197). */
        const val FIRST_OFFLOAD_GRACE_MS = 6_000L
        /** Backstop scoring cadence (3210). */
        const val ANALYZE_INTERVAL_MS = 30 * 60 * 1_000L
    }
}

/**
 * Which strap family a scan-based connect starts on (3403-3404, copied unchanged). [recorded] is what
 * service discovery saw on a link; [remembered] is the family half of the saved last-device pair.
 */
internal fun resolveSelectedModel(recorded: WhoopModel?, remembered: WhoopModel?): WhoopModel =
    recorded ?: remembered ?: WhoopModel.WHOOP4
