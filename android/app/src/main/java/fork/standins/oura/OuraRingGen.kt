// Fork stand-in for a data type from the deleted oura package (upstream oura/RingGen.kt,
// commit f36b82d22a87f88e81532dc76016943f33764a0b). Oura support is removed in this fork;
// ble/SourceCoordinator.kt still names this type when it builds the (no-op) Oura source.
// The three constants and from(model) are copied from upstream; every other member (MTU, capabilities,
// name / hardware-id recognition) is omitted because no kept code reads it.
package com.lhoop.oura

enum class OuraRingGen(val raw: String) {
    GEN3("gen3"),
    GEN4("gen4"),
    GEN5("gen5");

    companion object {
        fun from(model: String): OuraRingGen {
            val m = model.lowercase()
            if (m.contains("5")) return GEN5
            if (m.contains("4")) return GEN4
            if (m.contains("3")) return GEN3
            return GEN3
        }
    }
}
