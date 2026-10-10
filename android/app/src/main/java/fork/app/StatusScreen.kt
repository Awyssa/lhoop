// Fork-owned. The "Strap" tab: the foundation's own state and row counts, with Connect, Disconnect, Sync
// now, Export backup, Import backup and a Debug logging switch. Each control calls what upstream's UI
// called (commit 6ce65730); the call sites are cited where they are used.
package fork.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.repeatOnLifecycle
import com.lhoop.LhoopApplication
import com.lhoop.ble.LiveState
import com.lhoop.ble.WhoopConnectionService
import com.lhoop.data.DataBackup
import com.lhoop.data.WhoopDatabase
import com.lhoop.protocol.DeviceFamily
import com.lhoop.ui.LhoopPrefs
import fork.app.backup.ServerBackupSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.time.ZoneId

/** The driver is created by [AppRoot], above the tabs, so it runs whichever tab is showing. */
@Composable
fun StatusScreen(driver: FoundationDriver, modifier: Modifier = Modifier) {
    // The same view model the other tabs read: the strap's wrist events are worked out in one place.
    val nights: NightsViewModel = viewModel()
    val wrist = nights.state.collectAsStateWithLifecycle().value.wrist
    val context = LocalContext.current
    val lhoopApp = context.applicationContext as LhoopApplication
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val live by driver.ble.state.collectAsStateWithLifecycle()
    // The registry's active strap id, the same expression upstream's screens read (AppViewModel.kt 138).
    val coordinatorId by lhoopApp.sourceCoordinator.activeDeviceId.collectAsStateWithLifecycle()
    val activeId = coordinatorId ?: lhoopApp.activeDeviceId

    var strapModel by remember { mutableStateOf("…") }
    var counts by remember { mutableStateOf<Result<List<SignalCount>>?>(null) }
    var serviceRunning by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var oversize by remember { mutableStateOf<Pair<Uri, Long>?>(null) }
    var debugLogging by remember { mutableStateOf(LhoopPrefs.debugLogging(context)) }

    // Row counts and the registry model: on entering the foreground, when a sync starts or ends, when the
    // bond or the active id changes, and once a minute while the screen is visible.
    LaunchedEffect(lifecycleOwner, activeId, live.bonded, live.backfilling, live.lastSyncAt) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                strapModel = attempt { registryModel(lhoopApp, activeId) }.getOrDefault("unreadable")
                counts = attempt { SignalCounts.load(WhoopDatabase.get(context), activeId) }
                delay(60_000L)
            }
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                serviceRunning = connectionServiceRunning(context)
                delay(2_000L)
            }
        }
    }

    // The Connect gate upstream every scan entry point shared (ui/BlePermissions.kt 40-53): request the
    // Bluetooth permission first when it is missing, then connect whatever the answer was, so a denial
    // surfaces the client's own explanatory status note instead of a dead button.
    val connectPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { driver.connect() }
    val requestConnect = {
        val perms = blePermissions()
        val granted = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) driver.connect() else connectPermissionLauncher.launch(perms)
    }

    // Settings → Backup & restore → Export, upstream ui/SettingsScreen.kt 797-827 (the launcher and the
    // DataBackup.exportTo call) and 3672-3680 (the file name the button proposed).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { DataBackup.exportTo(context, uri) }
                }
                exportMessage = result.fold(
                    onSuccess = { outcome ->
                        if (outcome.overRestoreCeiling) {
                            "Backup exported. The backup archive is too large to restore safely — " +
                                "restoring it will ask you to confirm."
                        } else {
                            "Backup exported."
                        }
                    },
                    onFailure = { e -> "Backup problem: ${e.message}" },
                )
            }
        }
    }

    // Settings → Backup & restore → Import: the core's own restore, `DataBackup.importFrom`. It checks
    // the file, swaps the database and then needs the process restarted, because every open handle points
    // at the old one. The sleeps the app worked out are cleared with it: they are rebuilt from the
    // restored rows. A backup written by this app under an earlier name restores too.
    //
    // The core stops at a database over its ceiling (2 GiB) unless told to go on, and hands back TooLarge
    // with nothing changed (upstream #1807). A strap worn round the clock passes that in about ten weeks,
    // so the owner is asked, and a yes runs the same restore with `allowOversize`.
    fun restore(uri: Uri, allowOversize: Boolean) {
        scope.launch {
            importing = true
            importMessage = "Restoring the backup…"
            val result = withContext(Dispatchers.IO) {
                runCatching { DataBackup.importFrom(context, uri, allowOversize = allowOversize) }
            }
            val restored = result.getOrNull() is DataBackup.ImportResult.NeedsRestart
            importMessage = result.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is DataBackup.ImportResult.NeedsRestart ->
                            "Backup restored. The app is closing so it can start on the restored data. Open it again."
                        is DataBackup.ImportResult.Failed -> "Nothing was restored: ${outcome.message}"
                        is DataBackup.ImportResult.TooLarge -> {
                            oversize = uri to outcome.limitBytes
                            null
                        }
                    }
                },
                onFailure = { e -> "Nothing was restored: ${e.message}" },
            )
            if (restored) {
                withContext(Dispatchers.IO) {
                    runCatching { SleepStore.fileIn(context.filesDir).delete() }
                    // The widget's numbers came from the data just replaced.
                    runCatching { MorningWidget.clear(context) }
                }
                delay(RESTART_NOTICE_MS)
                closeForRestart(context)
            }
            importing = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) restore(uri, allowOversize = false)
    }
    oversize?.let { (uri, limitBytes) ->
        AlertDialog(
            onDismissRequest = { oversize = null; importMessage = NOTHING_RESTORED },
            title = { Text("Restore a very large backup?") },
            text = { Text(oversizeBackupQuestion(limitBytes)) },
            confirmButton = {
                TextButton(onClick = { oversize = null; restore(uri, allowOversize = true) }) { Text("Restore anyway") }
            },
            dismissButton = {
                TextButton(onClick = { oversize = null; importMessage = NOTHING_RESTORED }) { Text("Cancel") }
            },
        )
    }
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Import a backup?") },
            text = {
                Text(
                    "This replaces everything the app has stored on this phone with what is in the backup. " +
                        "The app then closes; open it again afterwards.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmImport = false; importLauncher.launch(arrayOf("*/*")) }) {
                    Text("Choose the file")
                }
            },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Strap", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

        Line("Connection", connectionText(live))
        Line("Bonded", bondedText(live))
        Line("Strap model", strapModel)
        Line("Firmware", live.strapFirmware ?: "not reported")
        Line("Battery", batteryText(live))
        Line("Last sync", lastSyncText(live))
        Line("Background service", if (serviceRunning) "running" else "not running")
        Line("Worn", Insights.worn(wrist, System.currentTimeMillis() / 1000L, ZoneId.systemDefault()))

        Text(
            "Rows for the active strap, by sample time",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        CountRow("Signal", "24 h", "7 d")
        val loaded = counts
        when {
            loaded == null -> Text("Counting…")
            loaded.isFailure -> Text("Counts unavailable: ${loaded.exceptionOrNull()?.javaClass?.simpleName}")
            else -> loaded.getOrThrow().forEach {
                CountRow(it.label, it.last24h.toString(), it.last7d.toString())
            }
        }
        // Not a count of rows: the hours the strap reported itself off the wrist, when it stores none.
        val nowSec = System.currentTimeMillis() / 1000L
        CountRow(
            "Off the wrist",
            Insights.offWristLast(SignalCounts.DAY_SECONDS, wrist, nowSec),
            Insights.offWristLast(7 * SignalCounts.DAY_SECONDS, wrist, nowSec),
        )

        // Enabled states follow upstream's Live screen (ui/LiveScreen.kt 588-690): Connect while no
        // scan is running, Disconnect while connected, Sync now once the strap can hand over history
        // and no offload is in flight.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Button(onClick = { requestConnect() }, enabled = !live.scanning) { Text("Connect") }
            Button(onClick = { driver.disconnect() }, enabled = live.connected) { Text("Disconnect") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { driver.syncNow() },
                enabled = live.bonded && live.historyReady && !live.backfilling,
            ) { Text("Sync now") }
            Button(
                onClick = {
                    exportMessage = null
                    exportLauncher.launch("lhoop-backup-${java.time.LocalDate.now()}.lhoopbak")
                },
            ) { Text("Export backup") }
        }
        exportMessage?.let { Text(it) }
        // A restore swaps the database under the running app, so the strap link has to be down first.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { importMessage = null; confirmImport = true },
                enabled = !live.connected && !live.scanning && !importing,
            ) { Text("Import backup") }
        }
        if (live.connected) Text("Disconnect before importing a backup.", style = MaterialTheme.typography.bodySmall)
        importMessage?.let { Text(it) }

        ServerBackupSection()

        // Test Centre → "Debug logging", upstream ui/TestCentreScreen.kt 886-890.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Debug logging", modifier = Modifier.weight(1f))
            Switch(
                checked = debugLogging,
                onCheckedChange = { debugLogging = it; driver.setDebugLogging(it) },
            )
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(0.5f).padding(end = 12.dp))
        Text(value, modifier = Modifier.weight(0.5f))
    }
}

@Composable
private fun CountRow(label: String, day: String, week: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(0.56f))
        Text(day, modifier = Modifier.weight(0.22f))
        Text(week, modifier = Modifier.weight(0.22f))
    }
}

/** How long the "restored, closing" line stays up before the app closes. */
private const val RESTART_NOTICE_MS = 2_500L

private const val NOTHING_RESTORED = "Nothing was restored."

/** What the owner is asked when a backup's database is over the core's ceiling. Nothing has been changed when it shows. */
internal fun oversizeBackupQuestion(limitBytes: Long): String =
    "The database in this backup is over ${limitBytes shr 30} GB. Restoring it needs about twice its size free " +
        "on this phone while it works, and takes a few minutes. Nothing has been changed yet."

/**
 * Ends the process after a restore. The database file was replaced under the running app, so nothing in
 * this process may go on using it; the next launch opens the restored one. The connection service is
 * stopped first so Android has no reason to bring the old process state back.
 */
private fun closeForRestart(context: Context) {
    runCatching { WhoopConnectionService.stop(context.applicationContext) }
    (context as? Activity)?.finishAndRemoveTask()
    android.os.Process.killProcess(android.os.Process.myPid())
}

/** `runCatching` that lets coroutine cancellation through, so a restarted refresh never reports a failure. */
private inline fun <T> attempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

private fun connectionText(s: LiveState): String {
    val base = when {
        s.connected && s.backfilling -> "connected, syncing history (${s.syncChunksThisSession} chunks)"
        s.connected -> "connected"
        s.scanning -> "searching"
        else -> "not connected"
    }
    return listOfNotNull(base, s.statusNote).joinToString("\n")
}

/** `bonded` can be true on live heart rate alone; `encryptedBond` is the genuine handshake. */
private fun bondedText(s: LiveState): String = when {
    s.bonded && s.encryptedBond -> "yes"
    s.bonded -> "yes (live heart rate only, no encrypted bond)"
    else -> "no"
}

private fun batteryText(s: LiveState): String {
    val pct = s.batteryPct ?: return "not reported"
    return "%.0f%%".format(pct) + if (s.charging == true) " (charging)" else ""
}

private fun lastSyncText(s: LiveState): String {
    val at = s.lastSyncAt
    val whenText = if (at == null) {
        "none recorded"
    } else {
        val minutes = (System.currentTimeMillis() / 1000L - at) / 60L
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at * 1000L)) +
            " ($minutes min ago)"
    }
    return listOfNotNull(whenText, s.lastSyncError).joinToString("\n")
}

/**
 * The active strap's model, read from the registry row for the ACTIVE id and resolved through the
 * registry's one canonical resolver. `forRegistryDevice` is the brand-aware entry to
 * `DeviceFamily.forRegistryModel`: it returns null for a row whose brand is not WHOOP instead of letting
 * it fall through to WHOOP5. The stored label is shown beside the family because the legacy "WHOOP"
 * label carries no family and resolves to WHOOP5 only by the resolver's documented fallback.
 */
private suspend fun registryModel(lhoopApp: LhoopApplication, activeId: String): String {
    val row = lhoopApp.deviceRegistry.all().firstOrNull { it.id == activeId }
        ?: return "no registry row for the active id"
    val family = DeviceFamily.forRegistryDevice(row.model, row.brand)
        ?: return "not a WHOOP (${row.brand} ${row.model})"
    return "${family.name} (resolved from registry label \"${row.model}\")"
}

/**
 * Whether [WhoopConnectionService] is running in the foreground, which is what keeps the link up while
 * the app is in the background. The service publishes no flag of its own, so this asks the OS:
 * `getRunningServices` is deprecated for other apps' services but still returns the caller's.
 *
 * In the foreground, not merely running: on 2026-10-06 Android took the service out of the foreground
 * a minute before it stopped it, and a demoted service protects nothing. The pill, the Strap tab and
 * StrapStartup.ensureService all read this one answer.
 */
@Suppress("DEPRECATION")
internal fun connectionServiceRunning(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return runCatching {
        manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == WhoopConnectionService::class.java.name && it.foreground }
    }.getOrDefault(false)
}
