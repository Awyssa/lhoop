// Fork-owned. The pieces the screens are built from: the card, the header with the strap's status, the
// ring, the bars, the strip of a night and the small charts. They draw what they are given and decide
// nothing; the rules are in Insights.kt.
package fork.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

// --- Frame --------------------------------------------------------------------------------------------

/** A card. With [onClick] the whole card is one button. */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** The title of a card, with an optional note at the right. */
@Composable
internal fun PanelTitle(title: String, note: String? = null, noteColor: Color = Ink.text2) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.text, modifier = Modifier.weight(1f))
        if (note != null) Text(note, fontSize = 13.sp, color = noteColor)
    }
}

@Composable
internal fun Caption(text: String, color: Color = Ink.text3, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = color, modifier = modifier)
}

/**
 * The strap's status as the pill says it, checked again every few seconds while a screen is showing.
 * [offWrist] is whether the strap has reported itself off the wrist and not back on.
 */
@Composable
internal fun rememberStrapStatus(driver: FoundationDriver, offWrist: Boolean): StrapStatus {
    val context = LocalContext.current
    val live by driver.ble.state.collectAsStateWithLifecycle()
    var serviceRunning by remember { mutableStateOf(true) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            serviceRunning = connectionServiceRunning(context)
            now = System.currentTimeMillis() / 1000L
            delay(5_000L)
        }
    }
    return Insights.strapStatus(live.connected, serviceRunning, live.lastSyncAt, now, ZoneId.systemDefault(), offWrist = offWrist)
}

@Composable
internal fun StatusPill(status: StrapStatus) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Ink.card)
            .border(1.dp, Ink.hairline, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (status.ok) Ink.green else Ink.amber))
        Text(status.text, fontSize = 13.sp, color = if (status.ok) Ink.text2 else Ink.text)
    }
}

@Composable
internal fun ScreenHeader(title: String, subtitle: String, status: StrapStatus, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink.text)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
            Text(subtitle, fontSize = 14.sp, lineHeight = 18.sp, color = Ink.text2)
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(status)
    }
}

// --- Figures ------------------------------------------------------------------------------------------

/** A ring filled to [fraction] of its way round. Null draws the empty ring only. */
@Composable
internal fun Ring(
    fraction: Float?,
    color: Color,
    diameter: Dp,
    stroke: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val topLeft = Offset(width / 2, width / 2)
            val arc = Size(size.width - width, size.height - width)
            drawArc(Ink.hairline, 0f, 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(width))
            if (fraction != null && fraction > 0f) {
                drawArc(
                    color, -90f, 360f * fraction.coerceIn(0f, 1f), useCenter = false,
                    topLeft = topLeft, size = arc, style = Stroke(width, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** A bar filled to [fraction] of its length. */
@Composable
internal fun MeterBar(fraction: Float, color: Color = Ink.sleep, height: Dp = 12.dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(Ink.hairline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
    }
}

/** A figure over its label. */
@Composable
internal fun Stat(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = Ink.text) {
    Column(modifier) {
        Text(value, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = Ink.text2)
    }
}

@Composable
internal fun Legend(color: Color, text: String, wide: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = if (wide) 16.dp else 10.dp, height = 10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(text, fontSize = 13.sp, color = Ink.text2)
    }
}

/** A label with a value at the right, for the folded-away detail. */
@Composable
internal fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 14.sp, color = Ink.text2, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink.text)
    }
}

// --- The night ----------------------------------------------------------------------------------------

/**
 * A night as a strip: asleep at full height, restless lower, awake lowest, so the three are told apart
 * by height as well as colour.
 */
@Composable
internal fun StateStrip(runs: List<StripRun>, modifier: Modifier = Modifier, height: Dp = 28.dp) {
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(4.dp))) {
        val total = runs.sumOf { it.minutes }.coerceAtLeast(1)
        var x = 0f
        for (run in runs) {
            val width = size.width * run.minutes / total
            val (color, share) = when (run.state) {
                StripState.ASLEEP -> Ink.sleep to 1f
                StripState.RESTLESS -> Ink.restless to 0.64f
                StripState.AWAKE -> Ink.awake to 0.3f
            }
            val h = size.height * share
            drawRect(color, topLeft = Offset(x, size.height - h), size = Size(max(width, 1f), h))
            x += width
        }
    }
}

/** Deep, REM and light as one bar in proportion. */
@Composable
internal fun StageBar(deepMin: Double, remMin: Double, lightMin: Double) {
    Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(deepMin to Ink.deep, remMin to Ink.rem, lightMin to Ink.light).forEach { (minutes, color) ->
            if (minutes >= 1.0) Box(Modifier.weight(minutes.toFloat()).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
        }
    }
}

/**
 * Sleeps between noon and noon as blocks on one bar, each given as (from, to) on a scale of 0 to 1.
 * The stretches [offWrist] are drawn over them as a thin grey band on the bar's own ground: the strap
 * recorded nothing then, which is not a fault and not sleep.
 */
@Composable
internal fun DayStrip(spans: List<Pair<Float, Float>>, offWrist: List<Pair<Float, Float>> = emptyList()) {
    Canvas(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp)).background(Ink.inner)) {
        for ((from, to) in spans) {
            drawRoundRect(
                Ink.sleep,
                topLeft = Offset(size.width * from, 0f),
                size = Size(max(size.width * (to - from), 3.dp.toPx()), size.height),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
        val band = 6.dp.toPx()
        for ((from, to) in offWrist) {
            val width = max(size.width * (to - from), 3.dp.toPx())
            drawRect(Ink.inner, topLeft = Offset(size.width * from, 0f), size = Size(width, size.height))
            drawRect(Ink.text3, topLeft = Offset(size.width * from, (size.height - band) / 2), size = Size(width, band))
        }
    }
}

/** Labels spread evenly under a chart. */
@Composable
internal fun Ticks(labels: List<String>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, fontSize = 12.sp, color = Ink.text3) }
    }
}

/** How wide the chart's own labels are, so what sits under it can start where its plot does. */
internal val ChartGutter = 26.dp

/**
 * Heart rate through the night as a line, with the lowest point marked. [curve] is (how far through
 * the time in bed, 0 to 1; beats a minute).
 */
@Composable
internal fun HeartChart(curve: List<Pair<Float, Double>>, modifier: Modifier = Modifier) {
    val plotHeight = 118.dp
    val low = curve.minOf { it.second }
    val high = curve.maxOf { it.second }
    val step = if (high - low > 45) 20.0 else 10.0
    val bottom = floor((low - 2) / step) * step
    val top = max(ceil((high + 2) / step) * step, bottom + step)
    val lines = generateSequence(bottom + step) { it + step }.takeWhile { it < top }.toList()
    Row(modifier.fillMaxWidth().height(plotHeight)) {
        Box(Modifier.width(ChartGutter).fillMaxHeight()) {
            lines.forEach { line ->
                val fromTop = 1.0 - (line - bottom) / (top - bottom)
                Text(
                    "%.0f".format(line), fontSize = 12.sp, lineHeight = 16.sp, color = Ink.text3,
                    modifier = Modifier.offset(y = plotHeight * fromTop.toFloat() - 8.dp),
                )
            }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            fun y(bpm: Double): Float = (size.height * (1.0 - (bpm - bottom) / (top - bottom))).toFloat()
            lines.forEach { drawLine(Ink.hairline, Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 1.dp.toPx()) }
            val path = Path()
            curve.forEachIndexed { i, (x, bpm) ->
                if (i == 0) path.moveTo(x * size.width, y(bpm)) else path.lineTo(x * size.width, y(bpm))
            }
            drawPath(path, Ink.text, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val lowest = curve.minBy { it.second }
            val at = Offset(lowest.first * size.width, y(lowest.second))
            drawCircle(Ink.card, radius = 6.5.dp.toPx(), center = at)
            drawCircle(Ink.sleep, radius = 4.5.dp.toPx(), center = at)
        }
    }
}

/**
 * Where a night sits among the earlier ones: the track is the scale, the pill their range, the tick
 * their usual and the dot the night itself.
 */
@Composable
internal fun RangeTrack(low: Double, high: Double, usual: Double, value: Double, modifier: Modifier = Modifier) {
    val least = minOf(low, usual, value)
    val most = maxOf(high, usual, value)
    val pad = max((most - least) * 0.25, 1.0)
    val from = least - pad
    val span = (most + pad) - from
    Canvas(modifier.fillMaxWidth().height(20.dp)) {
        fun x(v: Double): Float = (size.width * (v - from) / span).toFloat()
        val middle = size.height / 2
        drawLine(Ink.hairline, Offset(0f, middle), Offset(size.width, middle), strokeWidth = 2.dp.toPx())
        drawRoundRect(
            Ink.range,
            topLeft = Offset(x(low), middle - 6.dp.toPx()),
            size = Size(max(x(high) - x(low), 6.dp.toPx()), 12.dp.toPx()),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
        drawLine(Ink.text2, Offset(x(usual), middle - 8.dp.toPx()), Offset(x(usual), middle + 8.dp.toPx()), strokeWidth = 2.dp.toPx())
        drawCircle(Ink.card, radius = 8.dp.toPx(), center = Offset(x(value), middle))
        drawCircle(Ink.sleep, radius = 6.dp.toPx(), center = Offset(x(value), middle))
    }
}

// --- The week -----------------------------------------------------------------------------------------

/** The first letter of each weekday under a seven-column chart, the last one picked out. */
@Composable
internal fun WeekLetters(days: List<LocalDate>, modifier: Modifier = Modifier, locale: Locale = Locale.getDefault()) {
    Row(modifier.fillMaxWidth()) {
        days.forEachIndexed { i, day ->
            val last = i == days.lastIndex
            Text(
                day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                fontSize = 13.sp,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) Ink.text else Ink.text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Seven nights as bars of time asleep, each with a line where that night's need was. Hours are marked
 * at 4 and 8. A day with no night is left empty.
 */
@Composable
internal fun SleepNeedBars(week: List<Pair<LocalDate, TrendPoint?>>, modifier: Modifier = Modifier) {
    val plotHeight = 150.dp
    val most = week.mapNotNull { it.second }.maxOfOrNull { max(it.asleepMin, it.needMin ?: 0.0) } ?: 0.0
    val scaleMin = max(600.0, ceil((most + 30) / 60) * 60)
    Row(modifier.fillMaxWidth().height(plotHeight)) {
        Box(Modifier.width(ChartGutter).fillMaxHeight()) {
            listOf(8, 4).forEach { hours ->
                Text(
                    "${hours}h", fontSize = 12.sp, lineHeight = 16.sp, color = Ink.text3,
                    modifier = Modifier.offset(y = plotHeight * (1f - (hours * 60 / scaleMin).toFloat()) - 8.dp),
                )
            }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            fun h(minutes: Double): Float = (size.height * minutes / scaleMin).toFloat()
            listOf(240.0, 480.0).forEach {
                drawLine(Ink.hairline, Offset(0f, size.height - h(it)), Offset(size.width, size.height - h(it)), strokeWidth = 1.dp.toPx())
            }
            val column = size.width / week.size
            val barWidth = 24.dp.toPx().coerceAtMost(column * 0.7f)
            week.forEachIndexed { i, (_, point) ->
                if (point == null) return@forEachIndexed
                val centre = column * (i + 0.5f)
                val bar = h(point.asleepMin)
                drawRoundRect(
                    if (i == week.lastIndex) Ink.sleep else Ink.restless,
                    topLeft = Offset(centre - barWidth / 2, size.height - bar),
                    size = Size(barWidth, bar),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                )
                point.needMin?.let { need ->
                    val y = size.height - h(need)
                    drawLine(Ink.text, Offset(centre - barWidth / 2 - 4.dp.toPx(), y), Offset(centre + barWidth / 2 + 4.dp.toPx(), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
    }
}

/**
 * Seven values as a line, with a level line across for the usual. A missing value breaks the line. The
 * last value is picked out.
 */
@Composable
internal fun LineUsual(values: List<Double?>, usual: Double?, modifier: Modifier = Modifier) {
    val present = values.filterNotNull()
    if (present.isEmpty()) {
        Caption("Nothing recorded yet.", modifier = modifier)
        return
    }
    val least = minOf(present.min(), usual ?: present.min())
    val most = maxOf(present.max(), usual ?: present.max())
    val pad = max((most - least) * 0.2, 1.0)
    val from = least - pad
    val span = (most + pad) - from
    Canvas(modifier.fillMaxWidth().height(54.dp)) {
        fun y(v: Double): Float = (size.height * (1.0 - (v - from) / span)).toFloat()
        if (usual != null) {
            drawLine(Ink.range, Offset(0f, y(usual)), Offset(size.width, y(usual)), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        val column = size.width / values.size
        var previous: Offset? = null
        values.forEachIndexed { i, value ->
            val at = value?.let { Offset(column * (i + 0.5f), y(it)) }
            if (at != null && previous != null) drawLine(Ink.text2, previous!!, at, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            previous = at
        }
        values.forEachIndexed { i, value ->
            if (value == null) return@forEachIndexed
            val at = Offset(column * (i + 0.5f), y(value))
            if (i == values.lastIndex) {
                drawCircle(Ink.card, radius = 7.dp.toPx(), center = at)
                drawCircle(Ink.sleep, radius = 5.dp.toPx(), center = at)
            } else {
                drawCircle(Ink.text2, radius = 3.dp.toPx(), center = at)
            }
        }
    }
}

/** One night's time in bed on a clock from 21:00 to 09:00. [bedMinute] and [wakeMinute] are from the day's midnight. */
@Composable
internal fun BedWakeRow(letter: String, bedMinute: Double?, wakeMinute: Double?, last: Boolean) {
    Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            letter, fontSize = 13.sp, color = if (last) Ink.text else Ink.text2,
            fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.width(28.dp),
        )
        Canvas(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(50)).background(Ink.inner)) {
            if (bedMinute == null || wakeMinute == null) return@Canvas
            val from = ((bedMinute - BED_AXIS_FROM_MIN) / BED_AXIS_SPAN_MIN).coerceIn(0.0, 1.0).toFloat()
            val to = ((wakeMinute - BED_AXIS_FROM_MIN) / BED_AXIS_SPAN_MIN).coerceIn(0.0, 1.0).toFloat()
            if (to <= from) return@Canvas
            drawRoundRect(
                if (last) Ink.sleep else Ink.restless,
                topLeft = Offset(size.width * from, 0f),
                size = Size(size.width * (to - from), size.height),
                cornerRadius = CornerRadius(size.height / 2),
            )
        }
    }
}

/** The bed-and-wake chart runs from 21:00 the evening before to 09:00: minutes from the day's midnight. */
internal const val BED_AXIS_FROM_MIN = -180.0
internal const val BED_AXIS_SPAN_MIN = 720.0
