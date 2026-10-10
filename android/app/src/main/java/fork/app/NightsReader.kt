// Fork-owned. Works the nights out of the strap's rows and scores them.
//
// There is one of these for the process. The screens (NightsViewModel) and the home-screen widget
// (MorningWidget.kt) both read through it, so they see the same sleeps and the stored sleeps are never
// written from two places at once. It reads the core's database and never writes to it.
package fork.app

import android.app.Application
import com.lhoop.LhoopApplication
import com.lhoop.data.DailyMetric
import com.lhoop.data.SleepSession
import com.lhoop.data.WhoopDatabase
import com.lhoop.ui.logicalDayKeyNow
import fork.app.scoring.SleepDays
import fork.app.scoring.StrapSleep
import fork.app.scoring.WhoopStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

internal class NightsReader private constructor(app: LhoopApplication) {

    private val appContext = app.applicationContext
    private val db = WhoopDatabase.get(app)
    private val repository = app.repository
    private val sleeps = StrapSleepLoader(db, repository, SleepStore(SleepStore.fileIn(app.filesDir)))

    /** The strap's state and heart rate through one sleep, minute by minute. */
    suspend fun detail(deviceId: String, sleep: StrapSleep): NightDetail = sleeps.detail(deviceId, sleep)

    /**
     * Everything the screens show for [deviceId]: its nights, their scores and what each was compared
     * with. [days] are the core's stored days, which only feed the folded-away card of its own figures.
     */
    suspend fun load(deviceId: String, days: List<DailyMetric>, habitualNeedMin: Int): NightsViewModel.State {
        val zone = ZoneId.systemDefault()
        val nowSec = System.currentTimeMillis() / 1000L
        val assigned = SleepDays.assign(sleeps.load(deviceId, nowSec, zone))
        val newestFirst = assigned.days.asReversed()
        val stored = days.filter(Nights::hasNight).associateBy { it.day }
        val wrist = wrist(deviceId, nowSec)
        val logicalToday = logicalDayKeyNow(zone)

        val today = LocalDate.now(zone)
        if (newestFirst.isEmpty()) {
            val newest = stored.keys.filter { it <= today.toString() }.maxOrNull()
            return NightsViewModel.State(
                loaded = true,
                napsSince = assigned.napsSince,
                habitualNeedMin = habitualNeedMin,
                coreOnly = newest?.let { it to coreDays(deviceId, listOf(it), stored, zone).getValue(it) },
                today = today,
                wrist = wrist,
                logicalToday = logicalToday,
            ).also { publish(it) }
        }

        // The core's figures are fetched only for the nights on screen. Every night feeds the scores.
        val shownKeys = newestFirst.take(Nights.HISTORY_NIGHTS).map { it.day.toString() }
        val core = coreDays(deviceId, shownKeys.filter { it in stored }, stored, zone)
        val all = newestFirst.map { Night(it.day.toString(), it.night, it.naps, core[it.day.toString()]) }
        val inputs = all.mapNotNull(Nights::input)
        return NightsViewModel.State(
            loaded = true,
            nights = all,
            isLastNight = Nights.isLastNight(all.first().day, logicalToday),
            napsSince = assigned.napsSince,
            whoopStyle = WhoopStyle.score(inputs, habitualNeedMin.toDouble()).associateBy { it.day.toString() },
            usual = inputs.associate { it.day.toString() to Insights.usual(WhoopStyle.baselineNights(it.day, inputs)) },
            habitualNeedMin = habitualNeedMin,
            today = today,
            wrist = wrist,
            logicalToday = logicalToday,
        ).also { publish(it) }
    }

    /**
     * The widget draws from what was last worked out here, whoever asked for it. Off the main thread: it
     * reads and may write a small file. A failure to store it costs the widget, never the screen.
     */
    private suspend fun publish(state: NightsViewModel.State) = withContext(Dispatchers.IO) {
        runCatching { MorningWidget.publish(appContext, state) }
        Unit
    }

    /**
     * What the strap's events say about the wrist, as far back as the nights go. A failure here reads
     * as no events seen; it never takes the screen down.
     */
    private suspend fun wrist(deviceId: String, nowSec: Long): NightsViewModel.Wrist = withContext(Dispatchers.IO) {
        runCatching {
            val from = nowSec - StrapSleepLoader.HISTORY_DAYS * StrapSleepLoader.DAY_SEC
            val before = nowSec + StrapSleepLoader.FUTURE_MARGIN_SEC
            val events = db.query(OffWrist.EVENTS_SQL, arrayOf<Any?>(deviceId, from, before)).use { c ->
                buildList { while (c.moveToNext()) OffWrist.event(c.getLong(0), c.getString(1))?.let(::add) }
            }
            val through = db.query(OffWrist.NEWEST_EVENT_SQL, arrayOf<Any?>(deviceId, before)).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            }
            val spans = OffWrist.spans(events) { after ->
                db.query(OffWrist.FIRST_STATE_SQL, arrayOf<Any?>(deviceId, after, before)).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
            }
            NightsViewModel.Wrist(OffWrist.mentionable(spans, through), through, eventsSeen = events.isNotEmpty())
        }.getOrDefault(NightsViewModel.Wrist())
    }

    /** What the core stored for [keys]. A failure here leaves those nights without the core's figures; it never takes the screen down. */
    private suspend fun coreDays(
        deviceId: String,
        keys: List<String>,
        stored: Map<String, DailyMetric>,
        zone: ZoneId,
    ): Map<String, CoreDay> {
        if (keys.isEmpty()) return emptyMap()
        val first = keys.min()
        val last = keys.max()
        val scores = runCatching { sleepScores(deviceId, first, last) }.getOrDefault(emptyMap())
        val sessions = runCatching {
            val from = Nights.date(first) ?: return@runCatching emptyList<SleepSession>()
            val to = Nights.date(last) ?: from
            repository.sleepSessionsMerged(
                deviceId,
                from.minusDays(1).atStartOfDay(zone).toEpochSecond(),
                to.plusDays(1).atStartOfDay(zone).toEpochSecond(),
            )
        }.getOrDefault(emptyList())
        return keys.associateWith { key ->
            Nights.core(stored.getValue(key), scores[key], Nights.sessionsOn(key, sessions, zone))
        }
    }

    /** The core's sleep score per day. An imported value wins over a computed one, as in upstream's screens. */
    private suspend fun sleepScores(deviceId: String, fromDay: String, toDay: String): Map<String, Double> {
        val computed = repository.metricSeriesComputedUnion(deviceId, Nights.SLEEP_SCORE_KEY, fromDay, toDay)
        val imported = repository.metricSeries(deviceId, Nights.SLEEP_SCORE_KEY, fromDay, toDay)
        return computed.associate { it.day to it.value } + imported.associate { it.day to it.value }
    }

    companion object {
        @Volatile private var instance: NightsReader? = null

        fun of(app: Application): NightsReader =
            instance ?: synchronized(this) { instance ?: NightsReader(app as LhoopApplication).also { instance = it } }
    }
}
