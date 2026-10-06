// Fork stand-in: VERBATIM EXTRACTION of `enum class BuzzPattern` (upstream lines 88-94) and `object NotifPrefs` (upstream lines 144-222)
// from upstream ui/NotificationsSettingsScreen.kt
// at commit f36b82d22a87f88e81532dc76016943f33764a0b.
// The declaration body below is copied byte-for-byte by line range (see
// core-spike-tools/gen_extractions.py); only the package line and imports are written here.
//
// Members removed because they reference deleted code and nothing kept uses them:
//  - NotifPrefs.appPattern(ctx, app: NotifApp) (upstream lines 188-191): it takes the deleted UI
//    catalog type NotifApp / NotifCategory (Compose ImageVector icons) and no kept file calls it.
package com.lhoop.ui

import android.content.Context
import java.util.Calendar

/** Haptic pattern fired on the strap; only the repeat count varies. */
internal enum class BuzzPattern(val label: String, val loops: Int) {
    Single("Single", 1),
    Double("Double", 2),
    Triple("Triple", 3),
    Long("Long", 5),
}

/**
 * Plain-prefs store for wrist-alert settings (the AI key uses encrypted prefs; these are
 * non-secret toggles). Per-app prefs are flattened to `app.<id>.enabled` / `app.<id>.pattern`
 * keys so no JSON dependency is needed.
 */
internal object NotifPrefs {
    private const val FILE = "lhoop_notif_prefs"
    const val MASTER = "notif.masterEnabled"
    /** Catch-all: buzz for any app NOT in the curated catalog (Android can't enumerate installed
     *  apps, so this is how a user covers BeReal/etc. that aren't listed). Opt-in, default OFF. (#168) */
    const val ALL_OTHER = "notif.allOtherApps"
    const val WORN = "notif.onlyWhenWorn"
    const val QUIET = "notif.quietHoursEnabled"
    const val QUIET_START = "notif.quietStartMinutes"
    const val QUIET_END = "notif.quietEndMinutes"
    const val CALLS_MASTER = "notif.calls.masterEnabled"
    const val CALLS_PHONE = "notif.calls.phoneEnabled"
    const val CALLS_VOIP = "notif.calls.voipEnabled"
    const val CALLS_PATTERN = "notif.calls.pattern"
    /** Buzz the strap when the phone's native Clock fires a timer/alarm (CATEGORY_ALARM). Android-only
     *  (iOS can't observe another app's notifications). Default OFF. */
    const val ALARM_TIMER = "notif.alarmTimer"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getBool(ctx: Context, key: String, default: Boolean) =
        prefs(ctx).getBoolean(key, default)

    fun setBool(ctx: Context, key: String, value: Boolean) =
        prefs(ctx).edit().putBoolean(key, value).apply()

    fun getInt(ctx: Context, key: String, default: Int) =
        prefs(ctx).getInt(key, default)

    fun setInt(ctx: Context, key: String, value: Int) =
        prefs(ctx).edit().putInt(key, value).apply()

    fun appEnabled(ctx: Context, id: String): Boolean =
        prefs(ctx).getBoolean("app.$id.enabled", false) // opt-in, default OFF

    fun setAppEnabled(ctx: Context, id: String, value: Boolean) =
        prefs(ctx).edit().putBoolean("app.$id.enabled", value).apply()

    fun setAppPattern(ctx: Context, id: String, pattern: BuzzPattern) =
        prefs(ctx).edit().putString("app.$id.pattern", pattern.name).apply()

    /** Buzz loop-count for [pkg] (for the notification listener; no NotifApp needed). Defaults to
     *  Double if no per-app pattern was chosen. */
    fun appLoops(ctx: Context, pkg: String): Int {
        val name = prefs(ctx).getString("app.$pkg.pattern", null)
        return BuzzPattern.entries.firstOrNull { it.name == name }?.loops ?: BuzzPattern.Double.loops
    }

    fun callPattern(ctx: Context): BuzzPattern {
        val name = prefs(ctx).getString(CALLS_PATTERN, null)
        return BuzzPattern.entries.firstOrNull { it.name == name } ?: BuzzPattern.Triple
    }

    fun setCallPattern(ctx: Context, pattern: BuzzPattern) =
        prefs(ctx).edit().putString(CALLS_PATTERN, pattern.name).apply()

    fun callLoops(ctx: Context): Int = callPattern(ctx).loops

    fun inQuietHours(ctx: Context): Boolean {
        if (!getBool(ctx, QUIET, false)) return false
        val start = getInt(ctx, QUIET_START, 22 * 60)
        val end = getInt(ctx, QUIET_END, 7 * 60)
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Quiet window may wrap midnight (e.g. 22:00 -> 07:00).
        return if (start <= end) now in start until end else (now >= start || now < end)
    }
}
