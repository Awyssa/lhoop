// Fork-owned. The bodies of the periodic scoring loop upstream ran from the UI layer. [FoundationDriver]
// owns the loop itself (grace delay, 30-minute cadence, resume kick); this holds what each step does.
//
// Source: upstream ui/AppViewModel.kt lines 1131-1359 at commit
// f36b82d22a87f88e81532dc76016943f33764a0b. Conditions, arguments and log lines are copied unchanged.
// Left out: the Health Connect writeback that followed each pass (1350-1353).
package fork.app

import com.lhoop.LhoopApplication
import com.lhoop.analytics.Baselines
import com.lhoop.analytics.IntelligenceEngine
import com.lhoop.analytics.IntelligencePersistence
import com.lhoop.analytics.RegistryDayOwnerSource
import com.lhoop.ble.PuffinExperiment
import com.lhoop.testcentre.TestCentre
import com.lhoop.testcentre.TestDomain
import com.lhoop.ui.HrvWindow
import com.lhoop.ui.LhoopPrefs
import com.lhoop.ui.ProfileStore
import com.lhoop.ui.UnitPrefs
import kotlin.coroutines.cancellation.CancellationException

internal class ScoringPass(private val lhoopApp: LhoopApplication, private val deviceId: String) {

    private val appContext = lhoopApp.applicationContext
    private val repository = lhoopApp.repository
    private val ble = lhoopApp.ble
    private val profileStore = ProfileStore.from(appContext)

    /**
     * 1139-1154: the one-shot #547 purge of rows with an implausible timestamp (bad strap clock), run
     * when it has never run or when a sync flagged a re-heal.
     */
    suspend fun healTimestampsOnUpgrade() {
        runCatching {
            if (!LhoopPrefs.tsHealDone(appContext) || LhoopPrefs.tsHealPending(appContext)) {
                val purged = repository.healImplausibleTimestamps()
                if (purged > 0) {
                    ble.externalLog(
                        "Heal #547: purged $purged row(s) with an implausible timestamp " +
                            "(bad strap clock - far-past or future-dated); rescoring clean days.",
                    )
                }
                LhoopPrefs.setTsHealDone(appContext)
                LhoopPrefs.setTsHealPending(appContext, false)
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * 1159-1180: the one-shot full-history Effort and sleep-wear repair. Either pending flag triggers
     * one pass; both flags are set only after it returns.
     */
    suspend fun repairHistoryOnce() {
        runCatching {
            IntelligenceEngine.runEffortRescoreIfNeeded(
                repo = repository,
                profile = profileStore.toUserProfile(),
                importedDeviceId = deviceId,
                maxHROverride = profileStore.hrMaxOverride.takeIf { it > 0 }?.toDouble(),
                flagGet = {
                    !IntelligencePersistence.historyRepairIsPending(
                        effortDone = LhoopPrefs.effortRescoreDone(appContext),
                        sleepWearDone = LhoopPrefs.sleepWearRescoreDone(appContext),
                    )
                },
                flagSet = {
                    LhoopPrefs.setEffortRescoreDone(appContext)
                    LhoopPrefs.setSleepWearRescoreDone(appContext)
                },
                ownerSource = RegistryDayOwnerSource(lhoopApp.deviceRegistry),
                preserveUnscoredHistory = true,
            )
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** 1185-1196: re-run the purge when a sync since the last tick flagged bad-clock records. */
    suspend fun healTimestampsIfFlagged() {
        runCatching {
            if (LhoopPrefs.tsHealPending(appContext)) {
                val purged = repository.healImplausibleTimestamps()
                if (purged > 0) {
                    ble.externalLog(
                        "Heal #547: purged $purged row(s) with an implausible timestamp " +
                            "(bad strap clock detected this sync); rescoring clean days.",
                    )
                }
                LhoopPrefs.setTsHealPending(appContext, false)
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * 1202-1345: score the last 21 days, but only when the raw-input fingerprint moved since the last
     * completed pass. The watermark advances only on success.
     */
    suspend fun rescoreIfInputsChanged() {
        val analyzeFp = repository.analysisFingerprint()
        val analyzeHasNewData = analyzeFp != LhoopPrefs.analyzeWatermark(appContext)
        if (analyzeHasNewData) ble.externalLog("re-score: trigger=idle newData=yes")
        if (analyzeHasNewData) {
            runCatching {
                IntelligenceEngine.stepsHasMotionSink = { hasMotion ->
                    profileStore.stepsHasBankedMotion = hasMotion
                }
                IntelligenceEngine.analyzeRecent(
                    repo = repository,
                    profile = profileStore.toUserProfile(),
                    importedDeviceId = deviceId,
                    maxHROverride = profileStore.hrMaxOverride
                        .takeIf { it > 0 }?.toDouble(),
                    ownerSource = RegistryDayOwnerSource(lhoopApp.deviceRegistry),
                    manualStepCoefficient = profileStore.stepsManualOverride,
                    persistStepsCalibration = { cal ->
                        profileStore.stepsCalibrationCoefficient = cal.coefficient
                        profileStore.stepsCalibrationSampleDays = cal.sampleDays
                        profileStore.stepsCalibrationConfidence = cal.confidence
                        profileStore.stepsCalibrationManual = cal.manual
                    },
                    stepsMotionCacheGet = { LhoopPrefs.stepsMotionCache(appContext) },
                    stepsMotionCacheSet = { LhoopPrefs.setStepsMotionCache(appContext, it) },
                    baselineEpoch = LhoopPrefs.of(appContext)
                        .getLong(Baselines.hrvBaselineEpochKey, 0L).toDouble(),
                    recoveryEpoch = LhoopPrefs.of(appContext)
                        .getLong(Baselines.recoveryBaselineEpochKey, 0L).toDouble(),
                    diag = { line -> ble.externalLog(line) },
                    useExperimentalSleepV2 = PuffinExperiment.from(appContext).experimentalSleepV2,
                    useMotionAwareWake = PuffinExperiment.from(appContext).motionAwareWake,
                    sleepTraceSink = traceSink(TestDomain.SLEEP),
                    recoveryTraceSink = traceSink(TestDomain.RECOVERY),
                    stepsTraceSink = traceSink(TestDomain.STEPS),
                    universalSink = traceSink(TestDomain.UNIVERSAL),
                    workoutsTraceSink = traceSink(TestDomain.WORKOUTS),
                    hrvTraceSink = traceSink(TestDomain.HRV),
                    deepHrvWindow = UnitPrefs.hrvWindow(appContext) == HrvWindow.DEEP_SLEEP,
                    spo2CandidateDisplay = LhoopPrefs.spo2CandidateDisplay(appContext),
                    effortMethod = LhoopPrefs.effortMethod(appContext),
                    dayCycleMode = LhoopPrefs.dayCycleMode(appContext),
                )
            }.onSuccess {
                LhoopPrefs.setAnalyzeWatermark(appContext, analyzeFp)
                runCatching {
                    LhoopPrefs.of(appContext).edit()
                        .putLong("score.lastPassAt", System.currentTimeMillis() / 1000).apply()
                }
            }.onFailure { if (it is CancellationException) throw it }
        }
        IntelligenceEngine.stepsHasMotionSink = null
    }

    /**
     * The six Test Centre trace sinks upstream spelled out one by one (1268-1319): non-null only while
     * that domain's test mode is on, routing each line to the tagged strap log.
     */
    private fun traceSink(domain: TestDomain): ((String) -> Unit)? {
        if (!TestCentre.from(appContext).active(domain)) return null
        return { line -> ble.externalLog(line, domain) }
    }
}
