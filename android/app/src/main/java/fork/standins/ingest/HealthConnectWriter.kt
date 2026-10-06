// Fork stand-in: NO-OP. Replaces ingest/HealthConnectWriter.kt (upstream commit
// f36b82d22a87f88e81532dc76016943f33764a0b). Writing LHOOP's computed metrics into Health Connect is
// removed in this fork: nothing is written and no Health Connect client is created.
package com.lhoop.ingest

import android.content.Context
import com.lhoop.data.WhoopRepository

/** Declaration copied from upstream; kept code prints these in a log line. */
enum class WritebackFailure { PERMISSION_DENIED, REMOTE_ERROR }

/** Upstream's result type, reduced to what ble/WhoopBleClient.kt reads (written, ok, failures). */
data class WritebackResult(val written: Int, val failures: List<WritebackFailure>) {
    val ok: Boolean get() = failures.isEmpty()

    companion object {
        /** Upstream's own value for "Health Connect unavailable / nothing attempted — benign". */
        val UNAVAILABLE = WritebackResult(0, emptyList())
    }
}

object HealthConnectWriter {
    suspend fun write(context: Context, repo: WhoopRepository, deviceId: String): WritebackResult =
        WritebackResult.UNAVAILABLE
}
