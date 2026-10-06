// Fork-owned. Brings the app's list of sleeps up to date with what the strap has reported since.
//
// A sleep is worked out again each time until it is settled: the strap's data runs three hours past the
// sleep and any "up" that followed it, so nothing later can still be joined to it. From then on the
// stored record stands. That keeps a refresh to one read of the state since the last settled sleep, plus
// the heart figures of any sleep that changed. Refreshing step by step ends where one read of
// everything would.
//
// Pure Kotlin: the caller supplies the reads.
package fork.app.scoring

object SleepLog {

    /**
     * [stored] are one device's records in any order; the result is that device's records oldest first.
     *
     * @param floor nothing before this unix second is scanned
     * @param through the newest second of strap state there is
     * @param minutesFrom the strap's state from a unix second onwards (see [StateMinute])
     * @param vitalsOf the heart figures of a sleep, or null when its rows could not be read
     * @param offsetAt seconds east of UTC on the phone at a unix second
     */
    suspend fun refresh(
        deviceId: String,
        stored: List<SleepRecord>,
        floor: Long,
        through: Long,
        minutesFrom: suspend (Long) -> List<StateMinute>,
        vitalsOf: suspend (StrapSleep) -> SleepVitals?,
        offsetAt: (Long) -> Int,
    ): List<SleepRecord> {
        val mine = stored.sortedBy { it.sleep.startTs }
        // Everything before the first sleep that is not settled is kept as stored.
        val kept = mine.takeWhile { it.settled }
        val scanFrom = maxOf(floor, (kept.lastOrNull()?.sleep?.endTs ?: Long.MIN_VALUE) + 1)
        val before = mine.drop(kept.size).associateBy { it.sleep }
        val fresh = StrapSleeps.find(minutesFrom(scanFrom)).map { sleep ->
            val same = before[sleep]
            SleepRecord(
                deviceId = deviceId,
                sleep = sleep,
                offsetSec = same?.offsetSec ?: offsetAt(sleep.endTs),
                vitals = same?.vitals ?: vitalsOf(sleep),
                dataThroughTs = through,
            )
        }
        return kept + fresh
    }
}
