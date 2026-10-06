// Fork stand-in for a data type from the deleted oura package (upstream oura/OuraWear.kt,
// commit 6ce65730). Oura support is removed in this fork;
// ble/SourceCoordinator.kt still exposes a StateFlow<OuraWearState?>, which stays null. The enum is
// copied from upstream; the OuraWear / OuraWearTracker logic beside it is omitted.
package com.lhoop.oura

enum class OuraWearState {
    WORN,
    CHARGING,
    OFF,
    UNKNOWN,
}
