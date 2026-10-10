// Fork-owned. Loads what the screens show: the nights found in the strap's own state, the app's scores
// for them, what each was compared with, and the core's own figures for the newest days.
package fork.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lhoop.LhoopApplication
import com.lhoop.data.DailyMetric
import fork.app.scoring.SleepRecord
import fork.app.scoring.StrapSleep
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

internal class NightsViewModel(app: Application) : AndroidViewModel(app) {

    data class State(
        val loaded: Boolean = false,
        /** Set when the nights could not be read; the screen says so instead of looking empty. */
        val error: String? = null,
        /** Every night found in the strap's state, newest first. Only the newest [Nights.HISTORY_NIGHTS] carry the core's figures. */
        val nights: List<Night> = emptyList(),
        /** Whether the newest night is last night, or an older one because last night is not in yet. */
        val isLastNight: Boolean = false,
        /** Sleeps since the newest night. */
        val napsSince: List<SleepRecord> = emptyList(),
        /** The app's own WHOOP-style scores, by [Night.day]. */
        val whoopStyle: Map<String, WhoopStyleScore> = emptyMap(),
        /** What each night's HRV and resting heart rate were compared with, by [Night.day]. */
        val usual: Map<String, Usual> = emptyMap(),
        /** Today's date where the phone is, for the charts that end on it. */
        val today: LocalDate? = null,
        /** The usual sleep need those scores started from. */
        val habitualNeedMin: Int = AppSettings.DEFAULT_HABITUAL_NEED_MIN,
        /** The core's newest day and its figures, for when the strap's state gave no night at all. */
        val coreOnly: Pair<String, CoreDay>? = null,
        /** What the strap's own events say about the hours it was off the wrist. */
        val wrist: Wrist = Wrist(),
        /** The core's key for today, which rolls over at 04:00: the day whose night "last night" is. */
        val logicalToday: String? = null,
    )

    /**
     * [off] holds the stretches off the wrist long enough to mention, oldest first; the last may still
     * be open. An open stretch is known to run as far as [throughTs], the newest event of any kind the
     * strap has delivered, and no further. [eventsSeen] is false when the strap has reported no wrist
     * event at all, which is not the same as having been worn throughout.
     */
    data class Wrist(val off: List<OffWristSpan> = emptyList(), val throughTs: Long = 0L, val eventsSeen: Boolean = false) {
        val offNow: OffWristSpan? get() = OffWrist.offNow(off)
    }

    private val lhoopApp = app as LhoopApplication
    private val repository = lhoopApp.repository
    private val reader = NightsReader.of(app)

    private val details = HashMap<StrapSleep, NightDetail>()

    /** The strap's state and heart rate through one night, minute by minute, for the strip and the chart. Kept once read. */
    suspend fun detail(night: Night): NightDetail {
        val sleep = night.record.sleep
        synchronized(details) { details[sleep] }?.let { return it }
        return reader.detail(night.record.deviceId, sleep).also { loaded ->
            // An unsettled night can still grow, so only a night nothing can change is kept.
            if (night.record.settled) synchronized(details) { details[sleep] = loaded }
        }
    }

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
                reader.load(it.deviceId, it.days, it.habitualNeedMin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State(loaded = true, error = e.javaClass.simpleName, habitualNeedMin = it.habitualNeedMin)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), State())
}
