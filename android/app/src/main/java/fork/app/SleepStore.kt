// Fork-owned. Where the app keeps the sleeps it worked out from the strap's state.
//
// It is a small JSON file in the app's own files folder, deliberately not in the core's database: that
// schema must stay the one upstream ships. Nothing here is the only copy of anything. Every record can
// be worked out again from the strap's rows in the core's database, and is whenever [RULES] changes.
package fork.app

import fork.app.scoring.SleepRecord
import fork.app.scoring.SleepVitals
import fork.app.scoring.StrapSleep
import fork.app.scoring.Stretch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class SleepStore(private val file: File) {

    /** Every stored record, or nothing when the file is missing, unreadable or written under other rules. */
    fun read(): List<SleepRecord> = runCatching {
        if (!file.isFile) return emptyList()
        val root = JSONObject(file.readText())
        if (root.optInt(KEY_RULES, -1) != RULES) return emptyList()
        val sleeps = root.getJSONArray(KEY_SLEEPS)
        List(sleeps.length()) { record(sleeps.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** Replaces the file in one step, so a crash part-way never leaves half a file behind. */
    fun write(records: List<SleepRecord>) {
        val root = JSONObject()
        root.put(KEY_RULES, RULES)
        root.put(KEY_SLEEPS, JSONArray().also { array -> records.forEach { array.put(json(it)) } })
        file.parentFile?.mkdirs()
        val pending = File(file.path + ".tmp")
        pending.writeText(root.toString())
        if (!pending.renameTo(file)) {
            file.delete()
            check(pending.renameTo(file)) { "could not replace ${file.name}" }
        }
    }

    private fun json(r: SleepRecord): JSONObject = JSONObject().apply {
        put("device", r.deviceId)
        put("bed", r.sleep.bedStartTs)
        put("start", r.sleep.startTs)
        put("end", r.sleep.endTs)
        put("asleep", r.sleep.asleepSec)
        put("restless", r.sleep.restlessSec)
        put("upAfter", r.sleep.upAfterSec)
        put("wakeConfirmed", r.sleep.wakeConfirmed)
        put("stretches", JSONArray().also { a ->
            r.sleep.stretches.forEach {
                a.put(JSONArray().put(it.startTs).put(it.endTs).put(it.asleepSec).put(it.restlessSec))
            }
        })
        put("offset", r.offsetSec)
        r.vitals?.let { v ->
            put("vitals", JSONObject().apply {
                v.hrvMs?.let { put("hrv", it) }
                v.lateHrvMs?.let { put("hrvLate", it) }
                put("hrvWindows", v.hrvWindows)
                v.restingHr?.let { put("rhr", it) }
            })
        }
        put("through", r.dataThroughTs)
    }

    private fun record(o: JSONObject): SleepRecord {
        val stretches = o.getJSONArray("stretches")
        return SleepRecord(
            deviceId = o.getString("device"),
            sleep = StrapSleep(
                bedStartTs = o.getLong("bed"),
                startTs = o.getLong("start"),
                endTs = o.getLong("end"),
                asleepSec = o.getLong("asleep"),
                restlessSec = o.getLong("restless"),
                upAfterSec = o.getLong("upAfter"),
                wakeConfirmed = o.getBoolean("wakeConfirmed"),
                stretches = List(stretches.length()) {
                    val s = stretches.getJSONArray(it)
                    Stretch(s.getLong(0), s.getLong(1), s.getLong(2), s.getLong(3))
                },
            ),
            offsetSec = o.getInt("offset"),
            vitals = o.optJSONObject("vitals")?.let { v ->
                SleepVitals(
                    hrvMs = if (v.has("hrv")) v.getDouble("hrv") else null,
                    lateHrvMs = if (v.has("hrvLate")) v.getDouble("hrvLate") else null,
                    hrvWindows = v.getInt("hrvWindows"),
                    restingHr = if (v.has("rhr")) v.getInt("rhr") else null,
                )
            },
            dataThroughTs = o.getLong("through"),
        )
    }

    companion object {
        /** Where the store lives, inside the app's own files folder. */
        fun fileIn(filesDir: File): File = File(filesDir, "fork/sleeps.json")

        /**
         * The version of the rules the records were worked out under: how sleeps are found
         * (scoring/StrapSleep.kt) and how their heart figures are computed (scoring/SleepVitalsCalc.kt).
         * Raise it whenever either changes. A file written under another version is ignored, and
         * everything is worked out again from the strap's rows.
         *
         * 1: only state 2 counted as asleep. 2: "up" counts too, except the strap's wake confirmation.
         */
        const val RULES = 2

        private const val KEY_RULES = "rules"
        private const val KEY_SLEEPS = "sleeps"
    }
}
