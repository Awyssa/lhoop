// Fork-owned. One night looked at closely: recovery and sleep as two dials, the last 24 hours, the night
// itself with heart rate over the strap's states, where it sits against the nights before it, and the
// working-out folded away underneath. Opened from the home screen and from Trends.
package fork.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fork.app.scoring.SleepVitalsCalc
import fork.app.scoring.WhoopStyleScore
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun NightScreen(
    day: String,
    status: StrapStatus,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    nights: NightsViewModel = viewModel(),
) {
    val state by nights.state.collectAsStateWithLifecycle()
    val night = state.nights.firstOrNull { it.day == day }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (night == null) {
            ScreenHeader(shortDate(day), "Nothing is stored for this night", status, onBack)
            return@Column
        }
        val score = state.whoopStyle[night.day]
        val usual = state.usual[night.day] ?: Insights.usual(emptyList())
        val detail by produceState<NightDetail?>(initialValue = null, night.record.sleep) { value = nights.detail(night) }
        val newest = state.nights.firstOrNull()?.day == night.day

        ScreenHeader(shortDate(night.day), Nights.span(night.record.sleep.bedStartTs, night.record.sleep.endTs, night.record.offset), status, onBack)
        Dials(night, score)
        LastDay(night)
        NightChart(night, detail)
        Against(night, score, usual, remember(state.nights, state.whoopStyle) { Insights.trend(state.nights, state.whoopStyle) })
        Fold("How the sleep score was worked out") { Working(score, state.habitualNeedMin) }
        Fold("Figures from the original engine") {
            val core = night.core
            if (core == null) Caption("The original engine has nothing stored for this night.") else CoreFigures(core, null)
        }
        if (night.naps.isNotEmpty() || (newest && state.napsSince.isNotEmpty())) {
            Fold("Other sleeps") { OtherSleepRows(night.naps, if (newest) state.napsSince else emptyList()) }
        }
        Caption("Estimates computed on this phone from the strap's data. Not medical advice.")
    }
}

/** Recovery and the sleep score side by side, with equal weight. */
@Composable
private fun Dials(night: Night, score: WhoopStyleScore?) {
    val recovery = score?.recovery
    val band = Nights.band(recovery)
    Panel(padding = 14.dp) {
        Row(Modifier.fillMaxWidth()) {
            Dial(
                value = recovery, color = bandColor(band), title = "Recovery",
                note = recoveryWord(band), noteColor = if (band == null) Ink.text2 else bandColor(band), modifier = Modifier.weight(1f),
            )
            Dial(
                value = score?.sleepScore, color = Ink.sleep, title = "Sleep",
                note = score?.let { "${Nights.duration(night.asleepMin)} of ${Nights.duration(it.needMin)}" } ?: Nights.duration(night.asleepMin),
                noteColor = Ink.text2, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Dial(value: Double?, color: androidx.compose.ui.graphics.Color, title: String, note: String, noteColor: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Ring(fraction = value?.let { (it / 100.0).toFloat() }, color = color, diameter = 92.dp, stroke = 9.dp) {
            Text(Nights.whole(value), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = if (value == null) Ink.text3 else Ink.text)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
        Text(note, fontSize = 13.sp, color = noteColor)
    }
}

/** The night and any sleep before it on one bar from noon to noon. */
@Composable
private fun LastDay(night: Night) {
    val spans = remember(night) { Insights.lastDay(night) }
    if (spans.isEmpty()) return
    val earlier = night.naps.sumOf { it.sleep.asleepSec }
    Panel(padding = 14.dp) {
        PanelTitle(
            "Noon to noon",
            if (earlier > 0) "Earlier ${Nights.durationSec(earlier)}, night ${Nights.duration(night.asleepMin)}" else "Night ${Nights.duration(night.asleepMin)}",
        )
        Spacer(Modifier.height(10.dp))
        DayStrip(spans)
        Spacer(Modifier.height(6.dp))
        Ticks(listOf("12:00", "18:00", "00:00", "06:00", "12:00"))
    }
}

/** Heart rate through the night over the strap's states, then the totals and the stage split. */
@Composable
private fun NightChart(night: Night, detail: NightDetail?) {
    val record = night.record
    val sleep = record.sleep
    Panel {
        PanelTitle("The night", "Heart rate, beats a minute")
        Spacer(Modifier.height(12.dp))
        val curve = remember(sleep, detail) { detail?.let { Insights.heartCurve(sleep, it.heart) }.orEmpty() }
        if (curve.size >= 2) {
            HeartChart(curve)
        } else {
            Caption(if (detail == null) "Reading the night…" else "No heart rate is stored for this night.")
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(ChartGutter))
            NightStrip(night, detail, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(ChartGutter))
            Ticks(
                (0..4).map { i -> Nights.clock(sleep.bedStartTs + (sleep.endTs - sleep.bedStartTs) * i / 4, record.offset) },
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        StripLegend(night)

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat(Nights.durationSec(sleep.asleepSec), "Asleep", Modifier.weight(1f))
            Stat(Nights.durationSec(sleep.inBedSec), "In bed", Modifier.weight(1f))
            Stat(Nights.whole(sleep.efficiencyPct, "%"), "Efficiency", Modifier.weight(1f))
            Stat(
                Nights.whole(record.vitals?.lateHrvMs, " ms"),
                "HRV, last ${SleepVitalsCalc.LATE_HOURS} h", Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Ink.hairline)
        Spacer(Modifier.height(12.dp))
        Stages(night)
        Spacer(Modifier.height(8.dp))
        Caption(
            "The stage split is the original engine's stager run over this night. Its deep sleep has been close to a " +
                "second device's; its REM has read about an hour and a half more. " +
                "Restless is time the strap saw you move without calling you awake; it counts as sleep.",
        )
        NightNotes(night)
    }
}

/** Where the night's HRV, resting heart rate and sleep sit among the nights before it. */
@Composable
private fun Against(night: Night, score: WhoopStyleScore?, usual: Usual, points: List<TrendPoint>) {
    val recovery = score?.recovery
    Panel {
        PanelTitle(
            if (recovery != null) "Why ${Nights.whole(recovery)}%" else "Against your recent nights",
            if (usual.nights > 0) "against your last ${usual.nights} night${if (usual.nights == 1) "" else "s"}" else null,
        )
        Spacer(Modifier.height(14.dp))
        if (usual.nights == 0) {
            Caption("There are no earlier nights to compare this one with yet.")
            return@Panel
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val hrv = night.hrvMs
            if (hrv != null && usual.hrvMs != null && usual.hrvLow != null && usual.hrvHigh != null) {
                AgainstRow("HRV", "${Nights.whole(hrv)} ms", Insights.againstUsual(hrv, usual.hrvMs), usual.hrvLow, usual.hrvHigh, usual.hrvMs, hrv)
            }
            val rhr = night.restingHr?.toDouble()
            if (rhr != null && usual.restingHr != null && usual.rhrLow != null && usual.rhrHigh != null) {
                AgainstRow("Resting heart rate", "${Nights.whole(rhr)} bpm", Insights.againstUsual(rhr, usual.restingHr), usual.rhrLow, usual.rhrHigh, usual.restingHr, rhr)
            }
            val pct = score?.sufficiencyPct
            val sleepUsual = Nights.date(night.day)?.let { Insights.sleepUsual(it, points) }
            if (pct != null && sleepUsual != null) {
                AgainstRow("Sleep against need", "${Nights.whole(pct)}%", Insights.againstUsual(pct, sleepUsual.third), sleepUsual.first, sleepUsual.second, sleepUsual.third, pct)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(Ink.sleep, "This night")
            Legend(Ink.text2, "Your usual")
            Legend(Ink.range, "Recent range", wide = true)
        }
        Spacer(Modifier.height(8.dp))
        Caption("For resting heart rate, lower is better.")
    }
}

@Composable
private fun AgainstRow(label: String, value: String, note: String?, low: Double, high: Double, usual: Double, tonight: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink.text, modifier = Modifier.weight(1f))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
            if (note != null) Text("  $note", fontSize = 13.sp, color = Ink.text2)
        }
        RangeTrack(low, high, usual, tonight)
    }
}

/** A card that opens to show its detail. */
@Composable
private fun Fold(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Panel(padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { open = !open }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 15.sp, color = Ink.text, modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "Close" else "Open",
                tint = Ink.text3,
            )
        }
        if (open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
        }
    }
}

/** How the sleep need and the sleep score came about, and the one setting they start from. */
@Composable
private fun Working(score: WhoopStyleScore?, habitualNeedMin: Int) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Your usual sleep need", fontSize = 14.sp, color = Ink.text2, modifier = Modifier.weight(1f))
        FilledTonalIconButton(
            onClick = { AppSettings.setHabitualNeedMin(context, habitualNeedMin - AppSettings.HABITUAL_NEED_STEP_MIN) },
            enabled = habitualNeedMin > AppSettings.MIN_HABITUAL_NEED_MIN,
        ) { Icon(Icons.Filled.Remove, contentDescription = "Less") }
        Text(
            Nights.duration(habitualNeedMin.toDouble()), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink.text,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        FilledTonalIconButton(
            onClick = { AppSettings.setHabitualNeedMin(context, habitualNeedMin + AppSettings.HABITUAL_NEED_STEP_MIN) },
            enabled = habitualNeedMin < AppSettings.MAX_HABITUAL_NEED_MIN,
        ) { Icon(Icons.Filled.Add, contentDescription = "More") }
    }
    if (score == null) {
        Caption("This night has no scores.")
        return
    }
    if (score.debtInMin >= 1.0) DetailRow("Debt from the night before", "+ ${Nights.duration(score.debtInMin)}")
    if (score.napCreditMin >= 1.0) DetailRow("Earlier sleep taken off", "− ${Nights.duration(score.napCreditMin)}")
    DetailRow("Sleep needed", Nights.duration(score.needMin))
    DetailRow("Slept, of what was needed", Nights.whole(score.sufficiencyPct, "%"))
    DetailRow("Consistency with recent nights", Nights.whole(score.consistencyPct))
    DetailRow("Sleep score", Nights.whole(score.sleepScore))
    Spacer(Modifier.height(6.dp))
    Caption("Fitted to how WHOOP scored 240 nights. Not yet checked against nights measured by this app.")
}

/**
 * What the original engine worked out for the day, from its own sleep detection. [day] is given only
 * when these stand alone, with no night from the strap above them.
 */
@Composable
internal fun CoreFigures(core: CoreDay, day: String?) {
    val zone = ZoneId.systemDefault()
    if (day != null) {
        PanelTitle("The original engine's figures, $day")
        Spacer(Modifier.height(8.dp))
    }
    Caption("From the sleep detection this app was built on, which can pick a different stretch from the strap's own.")
    Spacer(Modifier.height(6.dp))
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
    if (core.heartRateOnly) Caption("The engine read this night from heart rate alone, so it withheld HRV and resting heart rate.")
    DetailRow("Deep", Nights.duration(core.deepMin))
    DetailRow("REM", Nights.duration(core.remMin))
    DetailRow("Light", Nights.duration(core.lightMin))
    DetailRow("Breathing rate", if (core.respRateBpm == null) Nights.DASH else "%.1f /min".format(core.respRateBpm))
    DetailRow("Skin temperature", Nights.signed1(core.skinTempDevC, " °C vs usual"))
}
