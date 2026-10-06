// Fork stand-in: the top-level helper `appLaunchIntent`, EXTRACTED from upstream ui/MainActivity.kt
// (upstream lines 211-228) at upstream commit 6ce65730.
// The declaration body was copied by line range, by a one-off script that is not in the repository; only
// the package line and imports were written here. Since the app was renamed on 2026-10-06 it differs from
// upstream's in the app's name: identifiers, comments and, in the settings code, the names of the
// preference file and its keys. Defaults and logic are unchanged.
// No member was removed.
package com.lhoop.ui

import android.content.Context
import android.content.Intent

internal fun appLaunchIntent(context: Context): Intent =
    context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        }
        ?: Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        }
