// Fork stand-in for a data type from the deleted oura package (upstream
// oura/OuraSleepSessionMapping.kt, commit f36b82d22a87f88e81532dc76016943f33764a0b). Oura
// support is removed in this fork; ble/SourceCoordinator.kt still names this type in the persist
// callback it hands the (no-op) Oura source, which never invokes it. The data class is copied from
// upstream; the OuraSleepSessionMapping object beside it is omitted.
package com.lhoop.oura

data class OuraSleepSession(
    val startTs: Long,
    val endTs: Long,
    val efficiency: Double?,
    val stagesJson: String,
)
