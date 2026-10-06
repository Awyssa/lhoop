// Fork stand-in: NO-OP. Replaces alarm/SleepWindowWatcher.kt (upstream commit
// f36b82d22a87f88e81532dc76016943f33764a0b). The phone smart alarm is removed in this fork: the
// detector never fires and reports nothing seen.
package com.lhoop.alarm

class SleepWindowWatcher {
    val trough: Int? get() = null
    val samples: Int get() = 0
    val hasFired: Boolean get() = false

    fun reset() {}

    fun shouldWake(bpm: Int): Boolean = false
}
