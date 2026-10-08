package fork.app

import com.lhoop.data.GravitySample
import com.lhoop.data.HrSample
import com.lhoop.data.RrInterval
import com.lhoop.data.WHOOP5_RR_INTERVALS_SQL
import fork.app.scoring.SleepDays
import fork.app.scoring.SleepRecord
import fork.app.scoring.SleepStagesCalc
import fork.app.scoring.SleepVitalsCalc
import fork.app.scoring.StateMinute
import fork.app.scoring.StrapSleeps
import fork.app.scoring.WhoopStyle
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Replays the app's sleep finding over real backups pulled off the phone, with the same SQL and the same
 * Kotlin the app runs, and prints each night for comparison with `fork/tools/night_report.py`. It then
 * scores those nights as the screen does, at the default usual sleep need.
 *
 * The backups are private health data and never in git, so this test is SKIPPED wherever
 * `whoop-data/lhoop-backups/` holds none (CI, any other machine). Its output is health data too.
 */
class StrapSleepBackupCheckTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val clock = DateTimeFormatter.ofPattern("EEE d HH:mm:ss")

    private fun at(ts: Long) = clock.format(Instant.ofEpochSecond(ts).atZone(zone))

    private fun hm(sec: Long) = "${sec / 3600}h ${"%02d".format(sec % 3600 / 60)}m"

    private fun newestBackup(): File? =
        File("../../whoop-data/lhoop-backups").listFiles().orEmpty()
            // Any database in an unpacked backup: the file's name depends on what the app was called when it wrote it.
            .mapNotNull { dir -> dir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.endsWith(".sqlite") } }
            .maxByOrNull { it.lastModified() }

    private fun Connection.one(sql: String, vararg args: Any): Any? =
        prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) else null }
        }

    private fun Connection.minutes(deviceId: String, from: Long, before: Long): List<StateMinute> =
        prepareStatement(StrapSleepLoader.MINUTES_SQL).use { st ->
            st.setString(1, deviceId); st.setLong(2, from); st.setLong(3, before)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        fun longOrNull(i: Int) = rs.getLong(i).takeIf { !rs.wasNull() }
                        add(
                            StateMinute(
                                rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                                longOrNull(6), longOrNull(7), longOrNull(8), longOrNull(9),
                            ),
                        )
                    }
                }
            }
        }

    private fun Connection.heartRate(deviceId: String, from: Long, to: Long): List<HrSample> =
        prepareStatement("SELECT ts, bpm FROM hrSample WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts").use { st ->
            st.setString(1, deviceId); st.setLong(2, from); st.setLong(3, to)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(HrSample(deviceId, rs.getLong(1), rs.getInt(2))) } }
        }

    private fun Connection.gravity(deviceId: String, from: Long, to: Long): List<GravitySample> =
        prepareStatement("SELECT ts, x, y, z FROM gravitySample WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts").use { st ->
            st.setString(1, deviceId); st.setLong(2, from); st.setLong(3, to)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(GravitySample(deviceId, rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4))) }
            }
        }

    /** The repository's own scoring read for a WHOOP 5: the same SQL constant the DAO runs. */
    private fun Connection.beats(deviceId: String, from: Long, to: Long): List<RrInterval> =
        prepareStatement(WHOOP5_RR_INTERVALS_SQL).use { st ->
            st.setString(1, deviceId); st.setLong(2, from); st.setLong(3, to); st.setInt(4, StrapSleepLoader.RR_ROW_CAP)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(RrInterval(deviceId = deviceId, ts = rs.getLong("ts"), rrMs = rs.getInt("rrMs"), seq = rs.getInt("seq")))
                    }
                }
            }
        }

    /** As the phone does it: a refresh every [stepSec] from the first row to the last, each seeing only what had arrived. */
    private fun steppedRefresh(db: Connection, deviceId: String, first: Long, last: Long, stepSec: Long): List<SleepRecord> {
        var stored = emptyList<SleepRecord>()
        var now = first + stepSec
        while (true) {
            val seenTo = minOf(now, last)
            val through = (db.one("SELECT MAX(ts) FROM sleepStateSample WHERE deviceId = ? AND ts <= ?", deviceId, seenTo) as Number).toLong()
            stored = kotlinx.coroutines.runBlocking {
                fork.app.scoring.SleepLog.refresh(
                    deviceId = deviceId,
                    stored = stored,
                    floor = 0L,
                    through = through,
                    minutesFrom = { from -> db.minutes(deviceId, from, seenTo + 1) },
                    vitalsOf = { sleep ->
                        SleepVitalsCalc.compute(sleep, db.heartRate(deviceId, sleep.startTs, sleep.endTs), db.beats(deviceId, sleep.startTs, sleep.endTs))
                    },
                    offsetAt = { ts -> zone.rules.getOffset(Instant.ofEpochSecond(ts)).totalSeconds },
                )
            }
            if (seenTo == last) return stored
            now += stepSec
        }
    }

    @Test
    fun refreshingStepByStepEndsWhereOneReadOfEverythingDoes() {
        val backup = newestBackup()
        assumeTrue("no private backup on this machine", backup != null)
        DriverManager.getConnection("jdbc:sqlite:${backup!!.path}").use { db ->
            val deviceId = db.one("SELECT deviceId FROM sleepStateSample GROUP BY deviceId ORDER BY COUNT(*) DESC LIMIT 1") as String
            val first = (db.one("SELECT MIN(ts) FROM sleepStateSample WHERE deviceId = ?", deviceId) as Number).toLong()
            val last = (db.one(StrapSleepLoader.NEWEST_SQL, deviceId, Long.MAX_VALUE) as Number).toLong()
            val oneRead = steppedRefresh(db, deviceId, first, last, stepSec = last - first)
            assertTrue(oneRead.isNotEmpty())
            // Every 15 minutes is how often the strap syncs; the odd steps land at different points in each night.
            for (stepSec in listOf(15 * 60L, 47 * 60L, 3 * 3600L + 11 * 60, 7 * 3600L)) {
                val stepped = steppedRefresh(db, deviceId, first, last, stepSec)
                assertTrue(
                    "a refresh every ${stepSec / 60} minutes ends with different sleeps than one read",
                    stepped.map { it.sleep to it.vitals } == oneRead.map { it.sleep to it.vitals },
                )
            }
        }
    }

    @Test
    fun theNewestBackupReplaysIntoSleepsThatHoldTogether() {
        val backup = newestBackup()
        assumeTrue("no private backup on this machine", backup != null)
        DriverManager.getConnection("jdbc:sqlite:${backup!!.path}").use { db ->
            val deviceId = db.one("SELECT deviceId FROM sleepStateSample GROUP BY deviceId ORDER BY COUNT(*) DESC LIMIT 1") as String
            val through = (db.one(StrapSleepLoader.NEWEST_SQL, deviceId, Long.MAX_VALUE) as Number).toLong()
            val sleeps = StrapSleeps.find(db.minutes(deviceId, 0L, Long.MAX_VALUE))
            assertTrue("no sleeps found in ${backup.parentFile.name}", sleeps.isNotEmpty())

            val records = sleeps.map { sleep ->
                val vitals = SleepVitalsCalc.compute(
                    sleep,
                    db.heartRate(deviceId, sleep.startTs, sleep.endTs),
                    db.beats(deviceId, sleep.startTs, sleep.endTs),
                )
                // As the loader does it: the stager is given the sleep with its padding on either side.
                val from = sleep.startTs - SleepStagesCalc.PAD_BEFORE_SEC
                val to = sleep.endTs + SleepStagesCalc.PAD_AFTER_SEC
                val stages = SleepStagesCalc.compute(sleep, db.gravity(deviceId, from, to), db.heartRate(deviceId, from, to), db.beats(deviceId, from, to))
                SleepRecord(
                    deviceId, sleep, zone.rules.getOffset(Instant.ofEpochSecond(sleep.endTs)).totalSeconds,
                    vitals.copy(deepSec = stages?.deepSec, remSec = stages?.remSec), through,
                )
            }
            val assigned = SleepDays.assign(records)

            println("Backup ${backup.parentFile.name}, strap data through ${at(through)}")
            fun stagesOf(r: SleepRecord): String {
                val deep = r.vitals?.deepSec ?: return "no stage split"
                val rem = r.vitals?.remSec ?: return "no stage split"
                val asleep = r.sleep.asleepSec.coerceAtLeast(1)
                return "deep %s (%.0f%%), REM %s (%.0f%%), light %s".format(
                    hm(deep), 100.0 * deep / asleep, hm(rem), 100.0 * rem / asleep, hm(asleep - deep - rem),
                )
            }
            fun line(label: String, r: SleepRecord) = println(
                "  %-16s in bed %s -> %s | asleep %s (restless %s), awake inside %s, up after %s | efficiency %.0f%% | HRV %s (last 3 h %s, %d windows) | RHR %s | %s%s".format(
                    label, at(r.sleep.bedStartTs), at(r.sleep.endTs), hm(r.sleep.asleepSec), hm(r.sleep.restlessSec), hm(r.sleep.awakeSec), hm(r.sleep.upAfterSec),
                    r.sleep.efficiencyPct, r.vitals?.hrvMs?.let { "%.1f".format(it) } ?: "-",
                    r.vitals?.lateHrvMs?.let { "%.1f".format(it) } ?: "-", r.vitals?.hrvWindows ?: 0,
                    r.vitals?.restingHr ?: "-", stagesOf(r),
                    (if (r.sleep.wakeConfirmed) "" else " | no waking seen") + (if (r.ongoing) " | the data ends here" else ""),
                ),
            )
            assigned.days.forEach { day ->
                day.naps.forEach { line("  nap before", it) }
                line("${day.day} night", day.night)
            }
            assigned.napsSince.forEach { line("  nap since", it) }

            // The scores the screen shows for these nights, and the same with the other sleeps left out.
            val inputs = assigned.days.mapNotNull { Nights.input(Night(it.day.toString(), it.night, it.naps, core = null)) }
            val usualNeed = AppSettings.DEFAULT_HABITUAL_NEED_MIN.toDouble()
            val scored = WhoopStyle.score(inputs, usualNeed)
            val withoutNaps = WhoopStyle.score(inputs.map { it.copy(naps = emptyList()) }, usualNeed).associateBy { it.day }
            fun whole(v: Double?) = v?.let { "%.0f".format(it) } ?: "-"
            println("Scores at a usual need of ${Nights.duration(usualNeed)}")
            scored.forEach { s ->
                val plain = withoutNaps.getValue(s.day)
                println(
                    "  %s need %s (debt in %s, earlier sleep off %s) | slept %s%% of it | consistency %s | sleep score %s | recovery %s || other sleeps left out: need %s, sleep score %s, recovery %s".format(
                        s.day, Nights.duration(s.needMin), Nights.duration(s.debtInMin), Nights.duration(s.napCreditMin), whole(s.sufficiencyPct),
                        whole(s.consistencyPct), whole(s.sleepScore), whole(s.recovery),
                        Nights.duration(plain.needMin), whole(plain.sleepScore), whole(plain.recovery),
                    ),
                )
            }
            assertTrue(scored.size == assigned.days.size)
            scored.zip(assigned.days).forEach { (s, day) ->
                assertTrue(s.napCreditMin == day.naps.sumOf { it.sleep.asleepSec } / 60.0)
                assertTrue(s.needMin >= WhoopStyle.MIN_NEED_MIN && s.needMin <= usualNeed + WhoopStyle.DEBT_CAP_MIN)
            }

            // What must hold whatever the night looked like.
            sleeps.zipWithNext().forEach { (a, b) -> assertTrue("sleeps overlap", a.endTs < b.bedStartTs) }
            sleeps.forEach { s ->
                assertTrue(s.bedStartTs <= s.startTs && s.startTs < s.endTs)
                assertTrue(s.asleepSec in StrapSleeps.MIN_STRETCH_ASLEEP_SEC..(s.endTs - s.startTs + 1))
                assertTrue(s.asleepSec == s.stretches.sumOf { it.asleepSec })
                assertTrue(s.restlessSec in 0..s.asleepSec && s.restlessSec == s.stretches.sumOf { it.restlessSec })
                assertTrue(s.efficiencyPct in 0.0..100.0)
            }
            records.forEach { r ->
                val deep = r.vitals?.deepSec ?: return@forEach
                val rem = r.vitals?.remSec ?: return@forEach
                assertTrue("deep and REM exceed the time asleep", deep >= 0 && rem >= 0 && deep + rem <= r.sleep.asleepSec)
            }
            assertTrue(assigned.days.map { it.day } == assigned.days.map { it.day }.distinct())
            assertTrue(assigned.days.sumOf { 1 + it.naps.size } + assigned.napsSince.size <= records.size)
        }
    }
}
