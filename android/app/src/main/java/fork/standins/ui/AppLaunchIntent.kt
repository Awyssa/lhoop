// Fork stand-in: VERBATIM EXTRACTION of the top-level helper `appLaunchIntent` (upstream lines 211-228)
// from upstream ui/MainActivity.kt
// at commit f36b82d22a87f88e81532dc76016943f33764a0b.
// The declaration body below is copied byte-for-byte by line range (see
// core-spike-tools/gen_extractions.py); only the package line and imports are written here.
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
