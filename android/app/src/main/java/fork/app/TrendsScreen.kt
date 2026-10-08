// Fork-owned. The nights over time: the week as a strip to pick a day from, the last week against the
// four before it, sleep against need, HRV and resting heart rate against the usual range, and bed and
// wake times. A picked day opens as a night (NightScreen.kt).
package fork.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun TrendsScreen(
    status: StrapStatus,
    onOpenNight: (String) -> Unit,
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
        val today = state.today
        if (!state.loaded || today == null) {
            ScreenHeader("Trends", "Loading", status)
            return@Column
        }
        val points = remember(state.nights, state.whoopStyle) { Insights.trend(state.nights, state.whoopStyle) }
        val week = remember(points, today) { Insights.week(points, today) }
        val days = week.map { it.first }
        // The day picked, kept as text so it survives the screen being rebuilt. The newest night to begin with.
        var picked by rememberSaveable { mutableStateOf<String?>(null) }
        val pickedDay = picked?.let(Nights::date)?.takeIf { it in days }
            ?: week.lastOrNull { it.second != null }?.first
            ?: today

        ScreenHeader("Trends", "${dayMonth(days.first())} to ${dayMonth(days.last())}", status)
        if (points.isEmpty()) {
            Panel { Caption("Trends show here once the strap has synced a night.", color = Ink.text2) }
            return@Column
        }
        WeekStrip(week, pickedDay, onPick = { picked = it.toString() })
        DaySummary(pickedDay, week.firstOrNull { it.first == pickedDay }?.second, onOpenNight)
        ProgressPanel(remember(points, today) { Insights.progress(points, today) })

        Panel {
            val met = week.mapNotNull { it.second?.sufficiencyPct }
            PanelTitle("Sleep against need", if (met.isEmpty()) null else "Average ${met.average().roundToInt()}% of need")
            Spacer(Modifier.height(12.dp))
            SleepNeedBars(week)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(ChartGutter))
                WeekLetters(days, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(Ink.restless, "Asleep")
                Legend(Ink.text, "Needed")
            }
        }

        Panel {
            val newest = state.nights.firstOrNull()
            val usual = newest?.let { state.usual[it.day] }
            PanelTitle("HRV and resting heart rate")
            Spacer(Modifier.height(12.dp))
            SeriesTitle("HRV", week.lastOrNull { it.second?.hrvMs != null }?.second?.hrvMs?.let { "${it.roundToInt()} ms" })
            LineUsual(week.map { it.second?.hrvMs }, usual?.hrvMs)
            Spacer(Modifier.height(12.dp))
            SeriesTitle("Resting heart rate", week.lastOrNull { it.second?.restingHr != null }?.second?.restingHr?.let { "${it.roundToInt()} bpm" })
            LineUsual(week.map { it.second?.restingHr }, usual?.restingHr)
            Spacer(Modifier.height(8.dp))
            WeekLetters(days)
            Spacer(Modifier.height(8.dp))
            Legend(Ink.range, "Your usual", wide = true)
        }

        Panel {
            val consistency = state.nights.firstOrNull()?.let { state.whoopStyle[it.day]?.consistencyPct }
            PanelTitle("Bed and wake times", consistency?.let { "Consistency ${it.roundToInt()}" })
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(28.dp))
                Ticks(listOf("21:00", "00:00", "03:00", "06:00", "09:00"), Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEachIndexed { i, (day, point) ->
                    BedWakeRow(day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), point?.bedMinute, point?.wakeMinute, last = i == week.lastIndex)
                }
            }
            Spacer(Modifier.height(10.dp))
            Caption("Consistency is how much of each day matches the three before it. Other sleeps count.")
        }

        Panel {
            PanelTitle("The numbers")
            Spacer(Modifier.height(8.dp))
            NumbersRow("Night", "Sleep", "HRV", "RHR", "Rec.", header = true)
            week.asReversed().forEach { (day, point) ->
                if (point == null) return@forEach
                NumbersRow(
                    shortDate(day.toString()), Nights.duration(point.asleepMin), Nights.whole(point.hrvMs), Nights.whole(point.restingHr),
                    Nights.whole(point.recovery, "%"), recoveryColor = Nights.band(point.recovery)?.let(::bandColor),
                )
            }
        }
        Caption("Estimates computed on this phone from the strap's data. Not medical advice.")
    }
}

/** Seven days to pick from, each with the colour of its recovery. */
@Composable
private fun WeekStrip(week: List<Pair<LocalDate, TrendPoint?>>, picked: LocalDate, onPick: (LocalDate) -> Unit) {
    Panel(padding = 8.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            week.forEach { (day, point) ->
                val selected = day == picked
                val shape = RoundedCornerShape(14.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp)
                        .clip(shape)
                        .background(if (selected) Ink.inner else Color.Transparent)
                        .border(1.5.dp, if (selected) Ink.sleep else Color.Transparent, shape)
                        .clickable { onPick(day) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), fontSize = 12.sp, color = if (selected) Ink.text2 else Ink.text3)
                    Text(day.dayOfMonth.toString(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (point == null) Ink.text3 else Ink.text)
                    val band = Nights.band(point?.recovery)
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(
                            when {
                                band != null -> bandColor(band)
                                point != null -> Ink.hairline
                                else -> Color.Transparent
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** The picked day's headline figures, and the way into its night. */
@Composable
private fun DaySummary(day: LocalDate, point: TrendPoint?, onOpenNight: (String) -> Unit) {
    Panel {
        val band = Nights.band(point?.recovery)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(longDate(day.toString()), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.text, modifier = Modifier.weight(1f))
            if (point != null && band != null) Text(recoveryWord(band), fontSize = 13.sp, color = bandColor(band))
        }
        if (point == null) {
            Spacer(Modifier.height(8.dp))
            Caption("No night is recorded for this day.")
            return@Panel
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Headline(Nights.whole(point.recovery, "%"), "Recovery", if (band == null) Ink.text else bandColor(band), Modifier.weight(1f))
            Headline(Nights.whole(point.sleepScore), "Sleep score", Ink.text, Modifier.weight(1f))
            Headline(Nights.duration(point.asleepMin), "Asleep", Ink.text, Modifier.weight(1.3f))
        }
        TextButton(onClick = { onOpenNight(day.toString()) }, modifier = Modifier.padding(top = 2.dp)) {
            Text("Open this night", fontSize = 14.sp, color = Ink.sleep)
        }
    }
}

@Composable
private fun Headline(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        Text(label, fontSize = 13.sp, color = Ink.text2)
    }
}

/** The last week against the four before it: each figure's mean, and how far it moved. */
@Composable
private fun ProgressPanel(progress: Progress) {
    Panel {
        PanelTitle(
            "Progress",
            when (progress.direction) {
                Direction.UP -> "Up on the four weeks before"
                Direction.DOWN -> "Down on the four weeks before"
                Direction.STEADY -> "Steady"
                null -> null
            },
            noteColor = when (progress.direction) {
                Direction.UP -> Ink.green
                Direction.DOWN -> Ink.amber
                else -> Ink.text2
            },
        )
        Spacer(Modifier.height(8.dp))
        if (!progress.ready) {
            Caption(
                "This compares the last 7 days with the 28 before them. It needs ${Insights.PROGRESS_MIN_RECENT} nights in the week " +
                    "and ${Insights.PROGRESS_MIN_EARLIER} before it. So far there are ${progress.recentNights} and ${progress.earlierNights}.",
                color = Ink.text2,
            )
            return@Panel
        }
        ProgressRow("Recovery", progress.recovery, "%", { it.roundToInt().toString() }, higherIsBetter = true)
        ProgressRow("Sleep score", progress.sleepScore, "", { it.roundToInt().toString() }, higherIsBetter = true)
        ProgressRow("Asleep", progress.asleepMin, "", { Nights.duration(it) }, higherIsBetter = true, deltaText = { Nights.duration(abs(it)) })
        ProgressRow("HRV", progress.hrvMs, " ms", { it.roundToInt().toString() }, higherIsBetter = true)
        ProgressRow("Resting heart rate", progress.restingHr, " bpm", { it.roundToInt().toString() }, higherIsBetter = false)
        Spacer(Modifier.height(6.dp))
        Caption("The mean of the last 7 days, and how it differs from the mean of the 28 days before.")
    }
}

@Composable
private fun ProgressRow(
    label: String,
    change: Change?,
    unit: String,
    text: (Double) -> String,
    higherIsBetter: Boolean,
    deltaText: (Double) -> String = { abs(it).roundToInt().toString() },
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = Ink.text2, modifier = Modifier.weight(1f))
        if (change == null) {
            Text(Nights.DASH, fontSize = 14.sp, color = Ink.text3)
            return@Row
        }
        Text(text(change.recent) + unit, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
        val moved = deltaText(change.delta)
        val flat = moved == deltaText(0.0)
        val better = (change.delta > 0) == higherIsBetter
        Text(
            when {
                flat -> "no change"
                change.delta > 0 -> "up $moved"
                else -> "down $moved"
            },
            fontSize = 13.sp,
            color = when {
                flat -> Ink.text2
                better -> Ink.green
                else -> Ink.amber
            },
            modifier = Modifier.width(96.dp).padding(start = 10.dp),
        )
    }
}

@Composable
private fun SeriesTitle(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(label, fontSize = 13.sp, color = Ink.text2, modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
    }
}

@Composable
private fun NumbersRow(
    night: String,
    sleep: String,
    hrv: String,
    rhr: String,
    recovery: String,
    header: Boolean = false,
    recoveryColor: Color? = null,
) {
    val color = if (header) Ink.text3 else Ink.text
    val size = if (header) 12.sp else 14.sp
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(night, fontSize = size, color = if (header) Ink.text3 else Ink.text2, modifier = Modifier.weight(1.4f))
        Text(sleep, fontSize = size, color = color, modifier = Modifier.weight(1.2f))
        Text(hrv, fontSize = size, color = color, modifier = Modifier.weight(0.7f))
        Text(rhr, fontSize = size, color = color, modifier = Modifier.weight(0.7f))
        Text(
            recovery, fontSize = size, fontWeight = if (header) null else FontWeight.SemiBold,
            color = if (header) Ink.text3 else recoveryColor ?: Ink.text, modifier = Modifier.weight(0.8f),
        )
    }
}

private fun dayMonth(day: LocalDate): String = day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
