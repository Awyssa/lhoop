// Fork-owned. The first real screen: last night's sleep and recovery, and the nights before it.
//
// The sleep itself (when, how long, HRV, resting heart rate) comes from the strap's own sleep flag, and
// the scores at the top are the app's own, built on that sleep. What the core worked out for the same day
// is in a card of its own. See Nights.kt for the rules.
package fork.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fork.app.scoring.SleepRecord
import fork.app.scoring.SleepVitalsCalc
import fork.app.scoring.WhoopStyle
import fork.app.scoring.WhoopStyleScore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun LastNightScreen(modifier: Modifier = Modifier, nights: NightsViewModel = viewModel()) {
    val state by nights.state.collectAsStateWithLifecycle()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val error = state.error
        val coreOnly = state.coreOnly
        when {
            !state.loaded -> Text("Loading…")
            error != null -> Message("Could not read the stored nights", "The app hit a problem ($error).")
            state.nights.isEmpty() -> {
                Message(
                    "No nights yet",
                    "A night shows here once the strap has synced one that it flagged as sleep itself. " +
                        "The Strap tab shows whether it is connected.",
                )
                NapsSince(state.napsSince)
                if (coreOnly != null) CoreCard(coreOnly.second, longDate(coreOnly.first))
            }
            else -> {
                val shown = state.nights.first()
                val score = state.whoopStyle[shown.day]
                Header(shown, state.isLastNight)
                RecoveryCard(shown, score)
                KeyNumbers(shown)
                SleepCard(shown, state.napsSince)
                ScoreCard(score, state.habitualNeedMin)
                shown.core?.let { CoreCard(it, null) }
                History(state.nights, state.whoopStyle)
                Text(
                    "Estimates computed on this phone from the strap's data. Not medical advice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Header(night: Night, isLastNight: Boolean) {
    Column {
        Text(
            if (isLastNight) "Last night" else "Latest night",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (isLastNight) longDate(night.day) else "${longDate(night.day)}. Nothing is recorded for last night yet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The app's own recovery for the night, or the reason there is none yet. */
@Composable
private fun RecoveryCard(night: Night, score: WhoopStyleScore?) {
    val recovery = score?.recovery
    val band = Nights.band(recovery)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Recovery", style = MaterialTheme.typography.titleMedium)
            Text(
                "The app's own score, experimental",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Nights.whole(recovery, "%"),
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = band?.let(::bandColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    band == RecoveryBand.GREEN -> "Well recovered"
                    band == RecoveryBand.YELLOW -> "Partly recovered"
                    band == RecoveryBand.RED -> "Not recovered"
                    night.hrvMs == null || night.restingHr == null ->
                        "No score: this night has no HRV or resting heart rate."
                    score != null && score.baselineNights < WhoopStyle.MIN_BASELINE_NIGHTS ->
                        "No score yet: it compares this night with at least ${WhoopStyle.MIN_BASELINE_NIGHTS} " +
                            "earlier nights that have HRV, and there are ${score.baselineNights}."
                    else -> "No score for this night."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun KeyNumbers(night: Night) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tile("Sleep", Nights.duration(night.asleepMin), "asleep", Modifier.weight(1f))
        Tile("HRV", Nights.whole(night.hrvMs), "ms", Modifier.weight(1f))
        Tile("Resting HR", night.restingHr?.toString() ?: Nights.DASH, "bpm", Modifier.weight(1f))
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The night as the strap itself flagged it, and the other sleeps around it. */
@Composable
private fun SleepCard(night: Night, napsSince: List<SleepRecord>) {
    val record = night.record
    val sleep = record.sleep
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sleep", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "from the strap's own sleep flag",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DetailRow("In bed", Nights.span(sleep.bedStartTs, sleep.endTs, record.offset))
            DetailRow("Asleep", Nights.durationSec(sleep.asleepSec))
            if (sleep.restlessSec >= 60) DetailRow("  of which restless", Nights.durationSec(sleep.restlessSec))
            if (sleep.awakeSec >= 60) DetailRow("Awake in between", Nights.durationSec(sleep.awakeSec))
            DetailRow("Efficiency", Nights.whole(sleep.efficiencyPct, "%"))
            DetailRow(
                "HRV, last ${SleepVitalsCalc.LATE_HOURS} hours of sleep",
                Nights.whole(record.vitals?.lateHrvMs, " ms"),
            )
            if (sleep.restlessSec >= 60) {
                Note("Restless is time the strap saw you move without calling you awake. It counts as sleep.")
            }
            if (record.ongoing) {
                Note(
                    "The strap had not called you awake where its data ends, at " +
                        "${Nights.clock(record.dataThroughTs, record.offset)}. This night may not be complete.",
                )
            } else if (sleep.upAfterSec / 60.0 >= Nights.RESTLESS_AFTER_NOTE_MIN) {
                Note(
                    "After ${Nights.clock(sleep.endTs, record.offset)} the strap saw " +
                        "${Nights.durationSec(sleep.upAfterSec)} more of restless time and its data then stops. " +
                        "That is not counted as sleep.",
                )
            }
            if (night.naps.isNotEmpty() || napsSince.isNotEmpty()) {
                HorizontalDivider()
                night.naps.forEach { DetailRow("Earlier sleep, ${Nights.napLabel(it)}", Nights.durationSec(it.sleep.asleepSec)) }
                napsSince.forEach { DetailRow("Sleep since, ${Nights.napLabel(it)}", Nights.durationSec(it.sleep.asleepSec)) }
                Note("Other sleeps are listed. They do not count towards the scores yet.")
            }
        }
    }
}

/** Sleeps found when there is no night to hang them on. */
@Composable
private fun NapsSince(naps: List<SleepRecord>) {
    if (naps.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sleeps the strap flagged", style = MaterialTheme.typography.titleMedium)
            naps.forEach { DetailRow(Nights.napLabel(it), Nights.durationSec(it.sleep.asleepSec)) }
        }
    }
}

/** What the app's sleep score and recovery were built from, and the one setting they depend on. */
@Composable
private fun ScoreCard(score: WhoopStyleScore?, habitualNeedMin: Int) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("How it was scored", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("experimental", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DetailRow("Sleep score", Nights.whole(score?.sleepScore))
            DetailRow("Sleep needed", score?.let { Nights.duration(it.needMin) } ?: Nights.DASH)
            if (score != null && score.debtInMin >= 1.0) {
                DetailRow("  of which debt from the night before", Nights.duration(score.debtInMin))
            }
            DetailRow("Slept, of what was needed", Nights.whole(score?.sufficiencyPct, "%"))
            DetailRow("Consistency with recent nights", Nights.whole(score?.consistencyPct))
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your usual sleep need", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalIconButton(
                    onClick = { AppSettings.setHabitualNeedMin(context, habitualNeedMin - AppSettings.HABITUAL_NEED_STEP_MIN) },
                    enabled = habitualNeedMin > AppSettings.MIN_HABITUAL_NEED_MIN,
                ) { Icon(Icons.Filled.Remove, contentDescription = "Less") }
                Text(
                    Nights.duration(habitualNeedMin.toDouble()),
                    modifier = Modifier.padding(horizontal = 10.dp),
                    fontWeight = FontWeight.Medium,
                )
                FilledTonalIconButton(
                    onClick = { AppSettings.setHabitualNeedMin(context, habitualNeedMin + AppSettings.HABITUAL_NEED_STEP_MIN) },
                    enabled = habitualNeedMin < AppSettings.MAX_HABITUAL_NEED_MIN,
                ) { Icon(Icons.Filled.Add, contentDescription = "More") }
            }
            Note("Fitted to how WHOOP scored 240 nights. Not yet checked against nights measured by this app.")
        }
    }
}

/**
 * What the core worked out for the day. [day] is given only when this card stands alone, with no night
 * from the strap above it.
 */
@Composable
private fun CoreCard(core: CoreDay, day: String?) {
    val zone = ZoneId.systemDefault()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (day == null) "The original engine's figures" else "The original engine's figures, $day", style = MaterialTheme.typography.titleMedium)
            Note("From the sleep detection this app was built on, which can pick a different stretch from the one above.")
            DetailRow("Recovery", Nights.whole(core.recoveryPct, "%"))
            DetailRow("Sleep score", Nights.whole(core.sleepScore))
            DetailRow(
                "Sleep it detected",
                if (core.bedStartTs == null || core.bedEndTs == null) Nights.DASH
                else Nights.span(core.bedStartTs, core.bedEndTs, zone.rules.getOffset(Instant.ofEpochSecond(core.bedEndTs))),
            )
            DetailRow("Asleep", Nights.duration(core.asleepMin))
            DetailRow("Efficiency", Nights.whole(core.efficiencyPct, "%"))
            DetailRow("HRV", Nights.whole(core.hrvMs, " ms"))
            DetailRow("Resting HR", core.restingHr?.let { "$it bpm" } ?: Nights.DASH)
            if (core.heartRateOnly) Note("The core read this night from heart rate alone, so it withheld HRV and resting heart rate.")
            HorizontalDivider()
            DetailRow("Deep", Nights.duration(core.deepMin))
            DetailRow("REM", Nights.duration(core.remMin))
            DetailRow("Light", Nights.duration(core.lightMin))
            DetailRow("Breathing rate", if (core.respRateBpm == null) Nights.DASH else "%.1f /min".format(core.respRateBpm))
            DetailRow("Skin temperature", Nights.signed1(core.skinTempDevC, " °C vs usual"))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun History(nights: List<Night>, scores: Map<String, WhoopStyleScore>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recent nights", style = MaterialTheme.typography.titleMedium)
            HistoryRow("Night", "Sleep", "HRV", "RHR", "Rec.", header = true)
            nights.take(Nights.HISTORY_NIGHTS).forEach { n ->
                val recovery = scores[n.day]?.recovery
                HistoryRow(
                    shortDate(n.day),
                    Nights.duration(n.asleepMin),
                    Nights.whole(n.hrvMs),
                    n.restingHr?.toString() ?: Nights.DASH,
                    Nights.whole(recovery, "%"),
                    recoveryColor = Nights.band(recovery)?.let(::bandColor),
                )
                if (n.naps.isNotEmpty()) {
                    Note("    plus ${Nights.durationSec(n.naps.sumOf { it.sleep.asleepSec })} of other sleep before it")
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    night: String,
    sleep: String,
    hrv: String,
    rhr: String,
    recovery: String,
    header: Boolean = false,
    recoveryColor: Color? = null,
) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(night, modifier = Modifier.weight(1.3f), style = style, color = if (header) muted else Color.Unspecified)
        Text(sleep, modifier = Modifier.weight(1.2f), style = style, color = if (header) muted else Color.Unspecified)
        Text(hrv, modifier = Modifier.weight(0.8f), style = style, color = if (header) muted else Color.Unspecified)
        Text(rhr, modifier = Modifier.weight(0.8f), style = style, color = if (header) muted else Color.Unspecified)
        Text(
            recovery,
            modifier = Modifier.weight(0.9f),
            style = style,
            textAlign = TextAlign.End,
            fontWeight = if (header) null else FontWeight.SemiBold,
            color = if (header) muted else recoveryColor ?: Color.Unspecified,
        )
    }
    if (header) Spacer(Modifier.height(2.dp))
}

/** Band colours chosen to stay readable on both light and dark surfaces. */
private fun bandColor(band: RecoveryBand): Color = when (band) {
    RecoveryBand.GREEN -> Color(0xFF2FA866)
    RecoveryBand.YELLOW -> Color(0xFFD6A013)
    RecoveryBand.RED -> Color(0xFFE0524D)
}

private fun longDate(day: String): String =
    Nights.date(day)?.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())) ?: day

private fun shortDate(day: String): String =
    Nights.date(day)?.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) ?: day
