// Fork-owned. The hours the strap reported itself off the wrist.
//
// The strap writes a WRIST_OFF event when it is taken off and WRIST_ON when it goes back on, and
// between the two it stores no heart rate, motion or sleep state at all
// (fork/docs/03-whoop5-status.md). Without this those hours are a silent hole. The events are read from
// the core's `event` table; nothing is written. `fork/tools/night_report.py` pairs them the same way.
//
// The live `worn` flag on the Bluetooth client is not used: it starts out true and follows live events
// only, so it says nothing about hours the app was not connected for.
package fork.app

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** One of the strap's two wrist events. */
internal data class WristEvent(val ts: Long, val on: Boolean)

/** A stretch off the wrist. [toTs] is null while the strap has not come back on. */
internal data class OffWristSpan(val fromTs: Long, val toTs: Long?) {
    /** Where the span ends for now: [toTs], or [throughTs] while it is still off. */
    fun end(throughTs: Long): Long = (toTs ?: throughTs).coerceAtLeast(fromTs)
}

internal object OffWrist {

    /**
     * `event.kind` is the strap's own name for the event with its code, such as "WRIST_OFF(10)"
     * (data/Entities.kt `EventRow`, protocol/Enums.kt). Arguments: deviceId, from, to.
     */
    const val EVENTS_SQL =
        "SELECT ts, kind FROM event WHERE deviceId = ? AND ts >= ? AND ts <= ? " +
            "AND (kind LIKE 'WRIST_OFF%' OR kind LIKE 'WRIST_ON%') ORDER BY ts"

    /** The newest event of any kind the strap has delivered: as far as an open stretch is known to run. Arguments: deviceId, before. */
    const val NEWEST_EVENT_SQL = "SELECT MAX(ts) FROM event WHERE deviceId = ? AND ts <= ?"

    /** The first second of strap state in a window: the strap was back on the wrist by then. Arguments: deviceId, after, before. */
    const val FIRST_STATE_SQL = "SELECT MIN(ts) FROM sleepStateSample WHERE deviceId = ? AND ts > ? AND ts < ?"

    /** A stretch shorter than this is the strap being adjusted, and is not mentioned anywhere. */
    const val MIN_SPAN_SEC = 10 * 60L

    /** The samples stop within seconds of the off event, not on it. A row this soon after it is still the wrist. */
    const val SAMPLE_GRACE_SEC = 60L

    /** A day's or a night's stretches off the wrist are mentioned in words once they add up to this. */
    const val MENTION_SEC = 3600L

    /** The event a stored row stands for, or null for any other kind. */
    fun event(ts: Long, kind: String): WristEvent? = when {
        kind.startsWith("WRIST_OFF") -> WristEvent(ts, on = false)
        kind.startsWith("WRIST_ON") -> WristEvent(ts, on = true)
        else -> null
    }

    /**
     * The stretches off the wrist in [events], oldest first.
     *
     * An off runs to the next on. It also ends at the first strap sample stamped after it, which
     * [firstSampleAfter] finds (given a second, the first sample later than it, or null): samples mean
     * the strap was worn, so a lost on event cannot weld two stretches and the worn hours between them
     * into one. An off inside a stretch already open changes nothing. An on with no off before it is
     * ignored: the events may simply start after the off. An off with neither an on nor a sample after
     * it is still off.
     */
    fun spans(events: List<WristEvent>, firstSampleAfter: (Long) -> Long?): List<OffWristSpan> {
        val sorted = events.sortedBy { it.ts }
        val spans = ArrayList<OffWristSpan>()
        var coveredTo = Long.MIN_VALUE
        for ((i, event) in sorted.withIndex()) {
            if (event.on || event.ts < coveredTo) continue
            val nextOn = sorted.drop(i + 1).firstOrNull { it.on }?.ts
            val worn = firstSampleAfter(event.ts + SAMPLE_GRACE_SEC)
            val end = listOfNotNull(nextOn, worn).minOrNull()
            spans += OffWristSpan(event.ts, end)
            coveredTo = end ?: Long.MAX_VALUE
        }
        return spans
    }

    /** The stretches long enough to mention, an open one counted as far as [throughTs]. */
    fun mentionable(spans: List<OffWristSpan>, throughTs: Long): List<OffWristSpan> =
        spans.filter { it.end(throughTs) - it.fromTs >= MIN_SPAN_SEC }

    /** The parts of [spans] inside [fromTs] (included) to [toTs] (not included), cut at its edges. */
    fun within(spans: List<OffWristSpan>, fromTs: Long, toTs: Long, throughTs: Long): List<Pair<Long, Long>> =
        spans.mapNotNull { span ->
            val a = maxOf(span.fromTs, fromTs)
            val b = minOf(span.end(throughTs), toTs)
            if (b > a) a to b else null
        }

    fun secondsWithin(spans: List<OffWristSpan>, fromTs: Long, toTs: Long, throughTs: Long): Long =
        within(spans, fromTs, toTs, throughTs).sumOf { it.second - it.first }

    /** The stretch that has not ended, if the newest one has not. */
    fun offNow(spans: List<OffWristSpan>): OffWristSpan? = spans.lastOrNull()?.takeIf { it.toTs == null }

    /**
     * A day here is noon to noon and is named by the date it ends on: the frame of the night screen's
     * 24-hour bar, so the bar and every sentence about a day agree. A stretch across noon is split
     * between two days.
     */
    fun dayWindow(day: LocalDate, offset: ZoneOffset): Pair<Long, Long> {
        val from = day.minusDays(1).atTime(LocalTime.NOON).toEpochSecond(offset)
        return from to from + DAY_SEC
    }

    /** Seconds off the wrist in [day]'s noon-to-noon window. */
    fun secondsOn(day: LocalDate, offset: ZoneOffset, spans: List<OffWristSpan>, throughTs: Long): Long {
        val (from, to) = dayWindow(day, offset)
        return secondsWithin(spans, from, to, throughTs)
    }

    private const val DAY_SEC = 86_400L
}
