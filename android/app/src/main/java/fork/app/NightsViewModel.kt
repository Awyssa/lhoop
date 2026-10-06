// Fork-owned. Loads what the "Last night" screen shows: the nights found in the strap's own state, the
// app's scores for them, and the core's own figures for the same days.
package fork.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lhoop.LhoopApplication
import com.lhoop.data.DailyMetric
import com.lhoop.data.SleepSession
import com.lhoop.data.WhoopDatabase
import com.lhoop.ui.logicalDayKeyNow
import fork.app.scoring.SleepDays
import fork.app.scoring.SleepRecord
import fork.app.scoring.WhoopStyle
import fork.app.scoring.WhoopStyleScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

internal class NightsViewModel(app: Application) : AndroidViewModel(app) {

    data class State(
        val loaded: Boolean = false,
        /** Set when the nights could not be read; the screen says so instead of looking empty. */
        val error: String? = null,
        /** The nights found in the strap's state, newest first. */
        val nights: List<Night> = emptyList(),
        /** Whether the newest night is last night, or an older one because last night is not in yet. */
        val isLastNight: Boolean = false,
        /** Sleeps since the newest night. */
        val napsSince: List<SleepRecord> = emptyList(),
        /** The app's own WHOOP-style scores, by [Night.day]. */
        val whoopStyle: Map<String, WhoopStyleScore> = emptyMap(),
        /** The usual sleep need those scores started from. */
        val habitualNeedMin: Int = AppSettings.DEFAULT_HABITUAL_NEED_MIN,
        /** The core's newest day and its figures, for when the strap's state gave no night at all. */
        val coreOnly: Pair<String, CoreDay>? = null,
    )

    private val lhoopApp = app as LhoopApplication
    private val repository = lhoopApp.repository
    private val sleeps = StrapSleepLoader(
        WhoopDatabase.get(app),
        repository,
        SleepStore(SleepStore.fileIn(app.filesDir)),
    )

    /** The registry's active strap id: the same expression upstream's screens read. */
    private val activeId: Flow<String> =
        lhoopApp.sourceCoordinator.activeDeviceId.map { it ?: lhoopApp.activeDeviceId }.distinctUntilChanged()

    /** New strap data does not always change a stored day, and "today" moves with the clock, so look again each minute. */
    private val minuteTick: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000L)
        }
    }

    private data class Inputs(val deviceId: String, val days: List<DailyMetric>, val habitualNeedMin: Int)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<State> = activeId
        .flatMapLatest { id ->
            combine(
                repository.recentDaysMergedFlow(id),
                AppSettings.habitualNeedMin(app),
                minuteTick,
            ) { days, need, _ -> Inputs(id, days, need) }
        }
        .mapLatest {
            try {
                load(it.deviceId, it.days, it.habitualNeedMin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State(loaded = true, error = e.javaClass.simpleName, habitualNeedMin = it.habitualNeedMin)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), State())

    private suspend fun load(deviceId: String, days: List<DailyMetric>, habitualNeedMin: Int): State {
        val zone = ZoneId.systemDefault()
        val assigned = SleepDays.assign(sleeps.load(deviceId, System.currentTimeMillis() / 1000L, zone))
        val newestFirst = assigned.days.asReversed()
        val stored = days.filter(Nights::hasNight).associateBy { it.day }

        if (newestFirst.isEmpty()) {
            val newest = stored.keys.filter { it <= LocalDate.now(zone).toString() }.maxOrNull()
            return State(
                loaded = true,
                napsSince = assigned.napsSince,
                habitualNeedMin = habitualNeedMin,
                coreOnly = newest?.let { it to coreDays(deviceId, listOf(it), stored, zone).getValue(it) },
            )
        }

        // The core's figures are fetched only for the nights on screen. Every night feeds the scores.
        val shownKeys = newestFirst.take(Nights.HISTORY_NIGHTS).map { it.day.toString() }
        val core = coreDays(deviceId, shownKeys.filter { it in stored }, stored, zone)
        val all = newestFirst.map { Night(it.day.toString(), it.night, it.naps, core[it.day.toString()]) }
        return State(
            loaded = true,
            nights = all.take(Nights.HISTORY_NIGHTS),
            isLastNight = Nights.isLastNight(all.first().day, logicalDayKeyNow(zone)),
            napsSince = assigned.napsSince,
            whoopStyle = WhoopStyle.score(all.mapNotNull(Nights::input), habitualNeedMin.toDouble())
                .associateBy { it.day.toString() },
            habitualNeedMin = habitualNeedMin,
        )
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
}
