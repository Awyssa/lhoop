// Fork-owned. The app's own settings, kept apart from upstream's preference files so nothing the core
// reads is ever changed from here.
package fork.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object AppSettings {

    private const val FILE = "fork_app"
    private const val KEY_HABITUAL_NEED_MIN = "habitualNeedMin"

    /** The usual sleep need the WHOOP-style scores start from, before debt. Eight hours until it is set. */
    const val DEFAULT_HABITUAL_NEED_MIN = 480
    const val MIN_HABITUAL_NEED_MIN = 300
    const val MAX_HABITUAL_NEED_MIN = 660
    const val HABITUAL_NEED_STEP_MIN = 15

    private var habitualNeed: MutableStateFlow<Int>? = null

    fun habitualNeedMin(context: Context): StateFlow<Int> = flow(context).asStateFlow()

    fun setHabitualNeedMin(context: Context, minutes: Int) {
        val clamped = minutes.coerceIn(MIN_HABITUAL_NEED_MIN, MAX_HABITUAL_NEED_MIN)
        prefs(context).edit().putInt(KEY_HABITUAL_NEED_MIN, clamped).apply()
        flow(context).value = clamped
    }

    @Synchronized
    private fun flow(context: Context): MutableStateFlow<Int> =
        habitualNeed ?: MutableStateFlow(
            prefs(context).getInt(KEY_HABITUAL_NEED_MIN, DEFAULT_HABITUAL_NEED_MIN),
        ).also { habitualNeed = it }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
