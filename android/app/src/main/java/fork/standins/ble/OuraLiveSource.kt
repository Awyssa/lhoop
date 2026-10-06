// Fork stand-in: NO-OP. Replaces ble/OuraLiveSource.kt (upstream commit
// f36b82d22a87f88e81532dc76016943f33764a0b, 2,730 lines). Oura ring support is removed in this fork.
//
// ble/SourceCoordinator.kt (kept byte-identical) still constructs this class when the ACTIVE registry
// device has sourceKind "oura", then calls connect/scan on it. Here every call does nothing: no scan,
// no GATT, no key use, no callback is ever invoked, and every published flow stays at its idle value.
// Note the coordinator pauses the WHOOP link before it starts any non-WHOOP source, so an install whose
// active device is an Oura row would sit connected to nothing until a WHOOP is made active again.
package com.lhoop.ble

import android.content.Context
import com.lhoop.data.StreamBatch
import com.lhoop.oura.OuraRingGen
import com.lhoop.oura.OuraSleepSession
import com.lhoop.oura.OuraWearState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Constructor parameters mirror upstream's names, types and defaults (the coordinator passes them by
 *  name). Upstream's trailing randomKey parameter is omitted: no kept code passes it. */
class OuraLiveSource(
    context: Context,
    deviceId: String,
    ringGen: OuraRingGen,
    liveSink: (hr: Int, rr: List<Int>) -> Unit,
    authKey: () -> IntArray?,
    persist: (StreamBatch, String) -> Unit = { _, _ -> },
    persistSleepSession: (OuraSleepSession, String) -> Unit = { _, _ -> },
    onsetKeying: () -> Boolean = { false },
    notifyMaskFull: () -> Boolean = { false },
    log: (String) -> Unit = {},
    onBattery: (Int) -> Unit = {},
    onModel: (String) -> Unit = {},
    onSerial: (String) -> Unit = {},
) : LiveHrSource {

    /** Same constants as upstream; only Idle is ever published. */
    enum class AdoptPhase { Idle, InstallingKey, Streaming, Failed }

    /** Same constants as upstream; only DISCONNECTED is ever published. */
    enum class LinkPhase { DISCONNECTED, CONNECTING, AUTHENTICATING, AUTHENTICATED }

    val batteryPct: StateFlow<Int?> = MutableStateFlow(null)
    val needsPairing: StateFlow<String?> = MutableStateFlow(null)
    val adoptPhase: StateFlow<AdoptPhase> = MutableStateFlow(AdoptPhase.Idle)
    val linkPhase: StateFlow<LinkPhase> = MutableStateFlow(LinkPhase.DISCONNECTED)
    val ouraWearState: StateFlow<OuraWearState?> = MutableStateFlow(null)

    fun setAdoptIntent(intent: Boolean) {}

    fun reconnect() {}

    override fun scan() {}

    override fun connect(address: String) {}

    override fun stop() {}
}
