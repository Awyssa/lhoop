// Fork stand-in: NO-OP. Replaces location/GpsSession.kt (upstream commit
// f36b82d22a87f88e81532dc76016943f33764a0b). GPS route tracking is removed in this fork: the session is
// never active, so ble/WhoopConnectionService.kt never adds the location foreground-service type and
// never starts the location stream.
package com.lhoop.location

import com.lhoop.analytics.RouteMath.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object GpsSession {

    /** Only the field the connection service reads. Always inactive. */
    data class State(val active: Boolean = false)

    val state: StateFlow<State> = MutableStateFlow(State())

    /** Assigned by the connection service; never invoked here. */
    var workoutsLog: ((String) -> Unit)? = null

    fun append(pt: LatLng) {}
}
