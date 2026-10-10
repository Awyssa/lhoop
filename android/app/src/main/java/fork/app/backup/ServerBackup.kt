// Fork-owned. The server backup as the rest of the app sees it: where it is set up, when it runs, and
// what the Strap tab shows about it.
//
// It runs once a day while the phone charges on Wi-Fi, and when the owner presses Back up now. A run
// looks at the last WINDOW_DAYS days, which is all the strap can still change, and once a week at
// everything the phone holds. Nothing here talks to the strap.
//
// The whole design is in fork/docs/12-server-backup.md.
package fork.app.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lhoop.BuildConfig
import com.lhoop.data.BackupSettingsBridge
import com.lhoop.data.WhoopDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What the owner set and what the last runs came to. A file of its own, so the token is never part of
 * an exported backup's settings, and Android's own backup is off for the whole app.
 */
internal class BackupPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lhoop_server_backup", Context.MODE_PRIVATE)

    val address: String? get() = prefs.getString(ADDRESS, null)
    val token: String? get() = prefs.getString(TOKEN, null)
    val setUp: Boolean get() = !address.isNullOrEmpty() && !token.isNullOrEmpty()

    /** When the server last confirmed it held everything a run looked at, in epoch milliseconds. 0 for never. */
    var lastOkAt: Long
        get() = prefs.getLong(LAST_OK, 0L)
        set(value) = prefs.edit().putLong(LAST_OK, value).apply()

    /** When a run last covered every hour the phone holds. */
    var lastFullAt: Long
        get() = prefs.getLong(LAST_FULL, 0L)
        set(value) = prefs.edit().putLong(LAST_FULL, value).apply()

    /** Why the last run did not finish, in words for the screen. Null after a run that did. */
    var problem: String?
        get() = prefs.getString(PROBLEM, null)
        set(value) = prefs.edit().putString(PROBLEM, value).apply()

    fun save(address: String, token: String) {
        // Another server or another token starts from nothing: the next run looks at everything.
        prefs.edit().clear().putString(ADDRESS, address).putString(TOKEN, token).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val ADDRESS = "address"
        const val TOKEN = "token"
        const val LAST_OK = "last_ok_at"
        const val LAST_FULL = "last_full_at"
        const val PROBLEM = "problem"
    }
}

internal object ServerBackup {

    enum class Outcome { DONE, NOT_SET_UP, TRY_LATER, FAILED }

    /** What the Strap tab shows. */
    data class Status(val address: String?, val running: Boolean, val lastOkAt: Long, val problem: String?) {
        val setUp: Boolean get() = address != null
    }

    private const val DAILY = "server-backup-daily"
    private const val NOW = "server-backup-now"
    private const val WINDOW_DAYS = 15
    private const val FULL_EVERY_MS = 7 * 86_400_000L

    /** A debug build may reach the machine the emulator runs on over plain HTTP: the server under test. */
    private val PLAIN_HTTP_HOSTS: Set<String> = if (BuildConfig.DEBUG) setOf("10.0.2.2") else emptySet()

    private val mutex = Mutex()
    private val state = MutableStateFlow(Status(null, false, 0L, null))

    fun status(context: Context): StateFlow<Status> {
        publish(context, running = state.value.running)
        return state
    }

    /** Why [typed] cannot be the server's address, or null when it can. */
    fun addressProblem(typed: String): String? = BackupAddress.problem(typed, PLAIN_HTTP_HOSTS)

    /** Keeps the server's address and token and puts the daily run on the schedule. */
    fun setUp(context: Context, address: String, token: String) {
        val clean = requireNotNull(BackupAddress.clean(address, PLAIN_HTTP_HOSTS)) { "the address was not checked" }
        BackupPrefs(context).save(clean, token.trim())
        schedule(context)
        publish(context, running = false)
    }

    fun forget(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(DAILY)
        work.cancelUniqueWork(NOW)
        BackupPrefs(context).clear()
        publish(context, running = false)
    }

    /** Puts the daily run on the schedule if it is not there. Safe to call at every start. */
    fun schedule(context: Context) {
        if (!BackupPrefs(context).setUp) return
        val daily = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, daily)
    }

    /**
     * The owner asked for a run now. It needs a connection of any kind, since he is the one asking.
     *
     * REPLACE, not KEEP: after a try that failed, the earlier request sits waiting out its retry delay,
     * and a second press must not wait behind it. The button is off while a run is going, so this
     * never cuts a run short.
     */
    fun runSoon(context: Context) {
        val once = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, once)
    }

    suspend fun run(context: Context): Outcome = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = BackupPrefs(app)
        val address = prefs.address
        val token = prefs.token
        if (address.isNullOrEmpty() || token.isNullOrEmpty()) return@withContext Outcome.NOT_SET_UP
        if (!mutex.tryLock()) return@withContext Outcome.DONE   // a run is already at it
        val work = File(app.cacheDir, "server-backup")
        try {
            publish(app, running = true)
            work.deleteRecursively()   // what a run that was killed left behind
            val now = System.currentTimeMillis()
            val full = now - prefs.lastFullAt >= FULL_EVERY_MS
            val meta = buildMap {
                put("app_build", BuildConfig.VERSION_CODE.toString())
                put("app_version", BuildConfig.VERSION_NAME)
                runCatching { BackupSettingsBridge.snapshotJson(app) }.getOrNull()?.let { put("settings_json", it) }
            }
            val run = BackupRun(RoomReader(WhoopDatabase.get(app)), HttpBackupServer(address, token), work) { AndroidDeltaFile(it) }
            val report = run.run(if (full) null else WINDOW_DAYS, meta)
            if (report.verified) {
                prefs.lastOkAt = now
                if (full) prefs.lastFullAt = now
                prefs.problem = null
                Outcome.DONE
            } else {
                prefs.problem = "The server did not confirm everything that was sent."
                Outcome.TRY_LATER
            }
        } catch (e: ServerSaidNo) {
            prefs.problem = BackupWords.refusal(e.status, e.message.orEmpty())
            if (e.status >= 500) Outcome.TRY_LATER else Outcome.FAILED
        } catch (e: IOException) {
            prefs.problem = "The server could not be reached."
            Outcome.TRY_LATER
        } catch (e: Exception) {
            prefs.problem = "The backup stopped: ${e.javaClass.simpleName}."
            Outcome.FAILED
        } finally {
            work.deleteRecursively()
            mutex.unlock()
            publish(app, running = false)
        }
    }

    private fun publish(context: Context, running: Boolean) {
        val prefs = BackupPrefs(context)
        state.value = Status(prefs.address.takeIf { prefs.setUp }, running, prefs.lastOkAt, prefs.problem)
    }
}

/** The run WorkManager starts: daily, or when the owner asks. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (ServerBackup.run(applicationContext)) {
        ServerBackup.Outcome.TRY_LATER -> Result.retry()
        ServerBackup.Outcome.FAILED -> Result.failure()
        ServerBackup.Outcome.DONE, ServerBackup.Outcome.NOT_SET_UP -> Result.success()
    }
}
