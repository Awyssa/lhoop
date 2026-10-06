// Fork stand-in for upstream ui/MainActivity.kt (commit f36b82d22a87f88e81532dc76016943f33764a0b).
// It keeps upstream's class name because the manifest's launcher aliases and the extracted
// appLaunchIntent both name com.lhoop.ui.MainActivity. Everything it shows lives in fork.app.
//
// What it keeps of upstream's onCreate (lines 71-159), in upstream's order:
//   1. demo flavor only: seed the synthetic dataset (lines 94-99), so the UI can be run in an emulator,
//      then the fork's own synthetic strap rows (fork.app.DemoStrapNights);
//   2. request the runtime permissions (lines 108-114 and 162-178), without waiting for the answer;
//   3. set the content, whose first composition creates the driver (line 154 → LhoopRoot line 1584).
// Upstream asked for the permissions at launch only once onboarding had finished ("lhoop.onboarded").
// This build has no onboarding and assumes an already-onboarded install, so it always asks.
//
// Left out: the crash-recovery screen that replaced the app until dismissed (lines 78-91), the demo
// build's second paired device, the scheduled debug export, AI coach brief, auto-backup and self-hosted push reschedules (lines
// 116-138), the terms and onboarding gates, the update check, and every appearance preference.
package com.lhoop.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.lhoop.BuildConfig
import com.lhoop.data.DemoSeeder
import com.lhoop.data.WhoopRepository
import fork.app.AppRoot
import fork.app.AppTheme
import fork.app.DemoStrapNights
import fork.app.launchPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // As upstream: results flow back into the Bluetooth client's own runtime checks.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (BuildConfig.ENABLE_DEMO) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { DemoSeeder.seedIfEmpty(WhoopRepository.from(applicationContext)) }
                // Fork addition: the raw strap rows the "Last night" screen reads, which upstream's seeder does not write.
                runCatching { DemoStrapNights.seedIfEmpty(applicationContext) }
            }
        }
        val needed = launchPermissions()
        if (needed.isNotEmpty()) permissionLauncher.launch(needed)
        setContent {
            AppTheme {
                AppRoot()
            }
        }
    }
}
