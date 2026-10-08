// Fork-owned. The home screen: the morning's answers at a glance. Recovery and what drove it, the three
// figures it was built from, sleep against need with the stage split and the night as a strip, and the
// last seven nights. Tapping the recovery or the sleep card opens the night (NightScreen.kt).
package fork.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fork.app.scoring.SleepRecord
import fork.app.scoring.WhoopStyleScore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun TodayScreen(
    status: StrapStatus,
    onOpenNight: (String) -> Unit,
    onOpenTrends: () -> Unit,
    modifier: Modifier = Modifier,
    nights: NightsViewModel = viewModel(),
) {
    val state by nights.state.collectAsStateWithLifecycle()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val shown = state.nights.firstOrNull()
        val error = state.error
        when {
            !state.loaded -> {
                ScreenHeader("Last night", "Loading", status)
            }
            error != null -> {
                ScreenHeader("Last night", "Could not read the stored nights", status)
                Panel { Caption("The app hit a problem ($error).", color = Ink.text2) }
            }
            shown == null -> {
                ScreenHeader("Last night", "No nights yet", status)
                Panel {
                    Caption(
                        "A night shows here once the strap has synced one that it flagged as sleep itself. " +
                            "The Strap tab shows whether it is connected.",
                        color = Ink.text2,
                    )
                }
                OtherSleeps(emptyList(), state.napsSince)
                state.coreOnly?.let { (day, core) -> Panel { CoreFigures(core, longDate(day)) } }
            }
            else -> {
                val score = state.whoopStyle[shown.day]
                val usual = state.usual[shown.day] ?: Insights.usual(emptyList())
                val detail by produceState<NightDetail?>(initialValue = null, shown.record.sleep) {
                    value = nights.detail(shown)
                }
                ScreenHeader(
                    title = if (state.isLastNight) "Last night" else "Latest night",
                    subtitle = if (state.isLastNight) longDate(shown.day) else "${longDate(shown.day)}. Nothing for last night yet.",
                    status = status,
                )
                RecoveryHero(shown, score, usual, onClick = { onOpenNight(shown.day) })
                InputTiles(shown, score, usual)
                SleepPanel(shown, score, state.habitualNeedMin, detail, state.napsSince, onClick = { onOpenNight(shown.day) })
                WeekPanel(state, onOpenTrends)
                Caption("Estimates computed on this phone from the strap's data. Not medical advice.")
            }
        }
    }
}

/** The recovery score as a ring, the verdict in a word, and a line on what drove it or why there is none. */
@Composable
private fun RecoveryHero(night: Night, score: WhoopStyleScore?, usual: Usual, onClick: () -> Unit) {
    val recovery = score?.recovery
    val band = Nights.band(recovery)
    Panel(onClick = onClick, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Ring(fraction = recovery?.let { (it / 100.0).toFloat() }, color = bandColor(band), diameter = 120.dp, stroke = 10.dp) {
                if (recovery != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(Nights.whole(recovery), fontSize = 42.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
                        Text("%", fontSize = 17.sp, color = Ink.text2, modifier = Modifier.padding(start = 2.dp, bottom = 7.dp))
                    }
                } else {
                    Text(Nights.DASH, fontSize = 30.sp, color = Ink.text3)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Recovery", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink.text2)
                Text(
                    recoveryWord(band),
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (band == null) Ink.text else bandColor(band),
                )
                Text(
                    Insights.reason(score, night.hrvMs, night.restingHr, usual),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Ink.text2,
                )
            }
        }
    }
}

internal fun recoveryWord(band: RecoveryBand?): String = when (band) {
    RecoveryBand.GREEN -> "Well recovered"
    RecoveryBand.YELLOW -> "Partly recovered"
    RecoveryBand.RED -> "Not recovered"
    null -> "No score yet"
}

/** The three figures recovery is built from, each with where it sits against the usual. */
@Composable
private fun InputTiles(night: Night, score: WhoopStyleScore?, usual: Usual) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("HRV", Nights.whole(night.hrvMs), "ms", Insights.againstUsual(night.hrvMs, usual.hrvMs), Modifier.weight(1f))
        Tile(
            "Resting HR", night.restingHr?.toString() ?: Nights.DASH, "bpm",
            Insights.againstUsual(night.restingHr?.toDouble(), usual.restingHr), Modifier.weight(1f),
        )
        Tile("Sleep score", Nights.whole(score?.sleepScore), "", "of 100", Modifier.weight(1f))
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, note: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).background(Ink.card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, fontSize = 13.sp, color = Ink.text2, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
            if (unit.isNotEmpty()) Text(unit, fontSize = 13.sp, color = Ink.text2, modifier = Modifier.padding(bottom = 5.dp))
        }
        Text(note ?: " ", fontSize = 13.sp, color = Ink.text2, maxLines = 1)
    }
}

/** Hours against need, the stage split, the night as a strip, and any other sleeps around it. */
@Composable
private fun SleepPanel(
    night: Night,
    score: WhoopStyleScore?,
    habitualNeedMin: Int,
    detail: NightDetail?,
    napsSince: List<SleepRecord>,
    onClick: () -> Unit,
) {
    val record = night.record
    val sleep = record.sleep
    Panel(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("Sleep", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.text, modifier = Modifier.weight(1f))
            Text(Nights.duration(night.asleepMin), fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
            Text(" asleep", fontSize = 14.sp, color = Ink.text2, modifier = Modifier.padding(bottom = 3.dp))
        }
        val pct = score?.sufficiencyPct
        if (score != null && pct != null) {
            Spacer(Modifier.height(10.dp))
            MeterBar((pct / 100.0).toFloat())
            Spacer(Modifier.height(8.dp))
            Caption("${Nights.whole(pct)}% of the ${Nights.duration(score.needMin)} you needed", color = Ink.text2)
            Caption(Insights.needSum(habitualNeedMin, score))
        }

        Spacer(Modifier.height(14.dp))
        Stages(night)

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Nights.clock(sleep.bedStartTs, record.offset), fontSize = 13.sp, color = Ink.text2)
            Text("In bed ${Nights.durationSec(sleep.inBedSec)}", fontSize = 13.sp, color = Ink.text2)
            Text(Nights.clock(sleep.endTs, record.offset), fontSize = 13.sp, color = Ink.text2)
        }
        Spacer(Modifier.height(6.dp))
        NightStrip(night, detail)
        Spacer(Modifier.height(8.dp))
        StripLegend(night)
        NightNotes(night)

        if (night.naps.isNotEmpty() || napsSince.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Ink.hairline)
            Spacer(Modifier.height(8.dp))
            OtherSleepRows(night.naps, napsSince)
        }
    }
}

/** Deep, REM and light, by the original engine's stager run over this night: estimates, and said to be. */
@Composable
internal fun Stages(night: Night) {
    val deep = night.deepMin
    val rem = night.remMin
    val light = night.lightMin
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Stages", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.text, modifier = Modifier.weight(1f))
        Text(if (deep == null) "estimate" else "estimate, REM reads high", fontSize = 13.sp, color = Ink.text3)
    }
    Spacer(Modifier.height(8.dp))
    if (deep == null || rem == null || light == null) {
        Caption("No stage split for this night.")
        return
    }
    StageBar(deep, rem, light)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        StageStat(Ink.deep, Nights.duration(deep), "Deep", Modifier.weight(1f))
        StageStat(Ink.rem, Nights.duration(rem), "REM", Modifier.weight(1f))
        StageStat(Ink.light, Nights.duration(light), "Light", Modifier.weight(1f))
    }
}

@Composable
private fun StageStat(color: Color, value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(label, fontSize = 13.sp, color = Ink.text2)
        }
    }
}

/** The night's strip, or an empty track of the same height while it is being read. */
@Composable
internal fun NightStrip(night: Night, detail: NightDetail?, modifier: Modifier = Modifier) {
    if (detail == null || detail.minutes.isEmpty()) {
        Box(modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(4.dp)).background(Ink.inner))
    } else {
        StateStrip(remember(night.record.sleep, detail) { Insights.strip(night.record.sleep, detail.minutes) }, modifier)
    }
}

@Composable
internal fun StripLegend(night: Night) {
    val sleep = night.record.sleep
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Legend(Ink.sleep, "Asleep")
        if (sleep.restlessSec >= 60) Legend(Ink.restless, "Restless ${Nights.durationSec(sleep.restlessSec)}")
        if (sleep.awakeSec >= 60) Legend(Ink.awake, "Awake ${Nights.durationSec(sleep.awakeSec)}")
    }
}

/** What the screen must say when a night may not be over, or when uncounted restless time followed it. */
@Composable
internal fun NightNotes(night: Night) {
    val record = night.record
    val sleep = record.sleep
    if (record.ongoing) {
        Spacer(Modifier.height(8.dp))
        Caption(
            "The strap had not called you awake where its data ends, at " +
                "${Nights.clock(record.dataThroughTs, record.offset)}. This night may not be complete.",
        )
    } else if (sleep.upAfterSec / 60.0 >= Nights.RESTLESS_AFTER_NOTE_MIN) {
        Spacer(Modifier.height(8.dp))
        Caption(
            "After ${Nights.clock(sleep.endTs, record.offset)} the strap saw " +
                "${Nights.durationSec(sleep.upAfterSec)} more of restless time and its data then stops. " +
                "That is not counted as sleep.",
        )
    }
}

@Composable
internal fun OtherSleepRows(earlier: List<SleepRecord>, since: List<SleepRecord>) {
    earlier.forEach { DetailRow("Earlier sleep, ${Nights.napLabel(it)}", Nights.durationSec(it.sleep.asleepSec)) }
    since.forEach { DetailRow("Sleep since, ${Nights.napLabel(it)}", Nights.durationSec(it.sleep.asleepSec)) }
    Caption(Nights.otherSleepsNote(earlier = earlier.isNotEmpty(), since = since.isNotEmpty()))
}

/** Sleeps found when there is no night to hang them on. */
@Composable
private fun OtherSleeps(earlier: List<SleepRecord>, since: List<SleepRecord>) {
    if (earlier.isEmpty() && since.isEmpty()) return
    Panel {
        PanelTitle("Sleeps the strap flagged")
        Spacer(Modifier.height(8.dp))
        (earlier + since).forEach { DetailRow(Nights.napLabel(it), Nights.durationSec(it.sleep.asleepSec)) }
    }
}

/** The last seven days: how much of the need each night met, and the colour of its recovery. */
@Composable
private fun WeekPanel(state: NightsViewModel.State, onOpenTrends: () -> Unit) {
    val today = state.today ?: return
    val week = remember(state.nights, state.whoopStyle, today) {
        Insights.week(Insights.trend(state.nights, state.whoopStyle), today)
    }
    Panel(padding = 14.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Last 7 days", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.text, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenTrends) { Text("Trends", fontSize = 14.sp, color = Ink.sleep) }
        }
        Row(Modifier.fillMaxWidth()) {
            week.forEachIndexed { i, (day, point) -> WeekColumn(day, point, last = i == week.lastIndex, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun WeekColumn(day: LocalDate, point: TrendPoint?, last: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val band = Nights.band(point?.recovery)
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (band == null) Color.Transparent else bandColor(band)))
        Box(Modifier.height(44.dp).width(22.dp), contentAlignment = Alignment.BottomCenter) {
            val share = point?.sufficiencyPct?.let { (it / 100.0).toFloat().coerceIn(0.06f, 1f) }
            if (share != null) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(share).clip(RoundedCornerShape(6.dp)).background(if (last) Ink.sleep else Ink.restless))
            } else {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(Ink.hairline))
            }
        }
        Text(
            day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
            fontSize = 13.sp,
            fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
            color = if (last) Ink.text else Ink.text2,
        )
    }
}

internal fun longDate(day: String): String =
    Nights.date(day)?.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())) ?: day

internal fun shortDate(day: String): String =
    Nights.date(day)?.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) ?: day
