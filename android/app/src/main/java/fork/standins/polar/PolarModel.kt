// Fork stand-in: NO-OP. Replaces polar/PolarModel.kt (upstream commit
// 6ce65730). Polar model identification is removed in this fork.
// ble/StandardHrSource.kt uses it only for an optional debug log line ("Polar H10 identified ...");
// null is upstream's own "not a Polar device, emit nothing" value. A Polar strap still works as a
// standard heart-rate strap.
package com.lhoop.polar

enum class PolarModel {
    UNKNOWN;

    companion object {
        fun debugIdentification(name: String?): String? = null
    }
}
