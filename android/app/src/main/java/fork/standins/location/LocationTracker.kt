// Fork stand-in: NO-OP. Replaces location/LocationTracker.kt (upstream commit
// 6ce65730). GPS route tracking is removed in this fork: the stream
// ends immediately with no fixes and LocationManager is never touched.
package com.lhoop.location

import android.content.Context
import com.lhoop.analytics.RouteMath.LatLng
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class LocationTracker(context: Context) {
    fun stream(minIntervalMs: Long = 2000, minDistanceM: Float = 0f): Flow<LatLng> = emptyFlow()
}
