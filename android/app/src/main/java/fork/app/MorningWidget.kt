// Fork-owned. The home-screen widget: the newest night's recovery, hours asleep, deep, REM and HRV.
//
// It is a plain RemoteViews widget with an XML layout. Each update is one call that sets texts and
// colours, and the layout's own default texts are what shows if no code has run. It draws only from
// the small file NightsReader keeps (MorningNumbers.kt) and never touches the Bluetooth client or the
// database: Android can start the process just to draw it, with nothing else of the app running.
//
// What keeps it fresh: the reader stores the numbers every time the nights are worked out and redraws
// when they change; while a widget is placed, the nights are worked out again whenever a sync finishes
// (watchSyncs); and Android redraws it every half hour, which is when "nothing for last night yet"
// starts being said. Nothing is scheduled: with the process gone, nothing new arrives to work out.
package fork.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.lhoop.LhoopApplication
import com.lhoop.R
import com.lhoop.ui.appLaunchIntent
import com.lhoop.ui.logicalDayKeyNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the widget's texts say and in which colours. Pure, so the rules are tested without a phone.
 * Colours are ARGB, the app's own palette (Theme.kt `Ink`).
 */
internal data class MorningFace(
    val title: String,
    val note: String,
    val noteColor: Int,
    val recovery: String,
    val recoveryColor: Int,
    val word: String,
    val wordColor: Int,
    val asleep: String,
    val deep: String,
    val rem: String,
    val hrv: String,
    /** Time asleep and HRV: measured figures. */
    val figureColor: Int,
    /** Deep and REM: estimates, and drawn a step quieter to say so beside their "est." labels. */
    val estimateColor: Int,
) {
    companion object {
        const val TEXT = 0xFFEEF1F5.toInt()
        const val TEXT2 = 0xFFAEB6C4.toInt()
        const val TEXT3 = 0xFF8D97A8.toInt()
        const val GREEN = 0xFF46C486.toInt()
        const val AMBER = 0xFFE8B84A.toInt()
        const val RED = 0xFFF0726C.toInt()

        /** What shows before any night has been worked out. The layout's own default texts say the same. */
        val empty = MorningFace(
            title = "LHOOP", note = "", noteColor = TEXT3,
            recovery = Nights.DASH, recoveryColor = TEXT2, word = "No night yet", wordColor = TEXT2,
            asleep = Nights.DASH, deep = Nights.DASH, rem = Nights.DASH, hrv = Nights.DASH,
            figureColor = TEXT2, estimateColor = TEXT2,
        )

        /**
         * The face for [numbers] on the day whose key is [logicalToday] (the day rolls over at 04:00).
         *
         * The night is always named by its date. When it is not last night, or may not be over, every
         * figure goes grey and a note says which: the widget can be looked at long after the numbers
         * were worked out, and must not pass an old night off as this morning's.
         */
        fun of(numbers: MorningNumbers?, logicalToday: String, locale: Locale = Locale.getDefault()): MorningFace {
            if (numbers == null) return empty
            val stale = !Nights.isLastNight(numbers.day, logicalToday)
            val quiet = stale || numbers.ongoing
            val band = Nights.band(numbers.recoveryPct?.toDouble())
            val young = numbers.recoveryPct != null && numbers.baselineNights < fork.app.scoring.WhoopStyle.BASELINE_NIGHTS
            return MorningFace(
                title = Nights.date(numbers.day)?.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)) ?: numbers.day,
                note = when {
                    stale -> "Nothing for last night yet"
                    numbers.ongoing -> "May not be complete"
                    young -> "Based on ${numbers.baselineNights} night${if (numbers.baselineNights == 1) "" else "s"}"
                    else -> ""
                },
                noteColor = if (quiet) AMBER else TEXT3,
                recovery = numbers.recoveryPct?.let { "$it%" } ?: Nights.DASH,
                recoveryColor = when {
                    quiet -> TEXT3
                    band == RecoveryBand.GREEN -> GREEN
                    band == RecoveryBand.YELLOW -> AMBER
                    band == RecoveryBand.RED -> RED
                    else -> TEXT2
                },
                word = recoveryWord(band),
                wordColor = when {
                    quiet -> TEXT3
                    band == RecoveryBand.GREEN -> GREEN
                    band == RecoveryBand.YELLOW -> AMBER
                    band == RecoveryBand.RED -> RED
                    else -> TEXT2
                },
                asleep = Nights.duration(numbers.asleepMin.toDouble()),
                deep = numbers.deepMin?.let { Nights.duration(it.toDouble()) } ?: Nights.DASH,
                rem = numbers.remMin?.let { Nights.duration(it.toDouble()) } ?: Nights.DASH,
                hrv = numbers.hrvMs?.let { "$it ms" } ?: Nights.DASH,
                figureColor = if (quiet) TEXT3 else TEXT,
                estimateColor = if (quiet) TEXT3 else TEXT2,
            )
        }
    }
}

class MorningWidgetReceiver : AppWidgetProvider() {
    /** Placed, resized, or Android's half-hourly call: draw what is stored. Nothing here may fail the process. */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runCatching { MorningWidget.draw(context, manager, ids) }
    }
}

internal object MorningWidget {

    private fun store(context: Context) = MorningStore(MorningStore.fileIn(context.filesDir))

    private fun ids(context: Context, manager: AppWidgetManager): IntArray =
        manager.getAppWidgetIds(ComponentName(context, MorningWidgetReceiver::class.java))

    /** Whether the owner has put the widget on a home screen. */
    fun placed(context: Context): Boolean =
        runCatching { ids(context, AppWidgetManager.getInstance(context)).isNotEmpty() }.getOrDefault(false)

    /**
     * Keeps the numbers of [state] for the widget and redraws it when they changed. A state that could
     * not be read changes nothing: the widget keeps the last night it knew of.
     */
    fun publish(context: Context, state: NightsViewModel.State) {
        if (!state.loaded || state.error != null) return
        if (store(context).write(MorningNumbers.from(state))) redraw(context)
    }

    /** Forgets the stored numbers, when the data they came from has been replaced. */
    fun clear(context: Context) {
        store(context).write(null)
        redraw(context)
    }

    fun redraw(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = ids(context, manager)
        if (ids.isNotEmpty()) draw(context, manager, ids)
    }

    fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val face = MorningFace.of(store(context).read(), logicalDayKeyNow())
        val views = RemoteViews(context.packageName, R.layout.morning_widget).apply {
            setTextViewText(R.id.morning_title, face.title)
            setTextViewText(R.id.morning_note, face.note)
            setTextColor(R.id.morning_note, face.noteColor)
            setTextViewText(R.id.morning_recovery, face.recovery)
            setTextColor(R.id.morning_recovery, face.recoveryColor)
            setTextViewText(R.id.morning_word, face.word)
            setTextColor(R.id.morning_word, face.wordColor)
            setTextViewText(R.id.morning_asleep, face.asleep)
            setTextColor(R.id.morning_asleep, face.figureColor)
            setTextViewText(R.id.morning_deep, face.deep)
            setTextColor(R.id.morning_deep, face.estimateColor)
            setTextViewText(R.id.morning_rem, face.rem)
            setTextColor(R.id.morning_rem, face.estimateColor)
            setTextViewText(R.id.morning_hrv, face.hrv)
            setTextColor(R.id.morning_hrv, face.figureColor)
            // A tap opens the app, which also asks the strap for a sync as it comes to the front.
            setOnClickPendingIntent(
                R.id.morning_root,
                PendingIntent.getActivity(
                    context, 0, appLaunchIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private val watching = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * From the first call on, for the life of the process: whenever a sync finishes and a widget is
     * placed, work the nights out again, which stores and redraws the numbers (NightsReader). With no
     * widget placed it does nothing. Called where the strap link is started: by the driver, and after a
     * phone restart, so the widget is kept up without the app being opened.
     */
    fun watchSyncs(app: LhoopApplication) {
        if (!watching.compareAndSet(false, true)) return
        scope.launch {
            app.ble.state.map { it.lastSyncAt }.distinctUntilChanged().collect {
                if (!placed(app)) return@collect
                runCatching {
                    val deviceId = app.sourceCoordinator.activeDeviceId.value ?: app.activeDeviceId
                    // The core's own stored days only feed a card on the night screen; the widget needs none.
                    NightsReader.of(app).load(deviceId, emptyList(), AppSettings.habitualNeedMin(app).value)
                }
            }
        }
    }
}
