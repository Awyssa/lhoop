// Fork stand-in for a data type from the deleted oura package (upstream oura/OuraWear.kt,
// commit f36b82d22a87f88e81532dc76016943f33764a0b). Oura support is removed in this fork;
// ble/SourceCoordinator.kt still exposes a StateFlow<OuraWearState?>, which stays null. The enum is
// copied from upstream; the OuraWear / OuraWearTracker logic beside it is omitted.
package com.lhoop.oura

enum class OuraWearState {
    WORN,
    CHARGING,
    OFF,
    UNKNOWN,
}
