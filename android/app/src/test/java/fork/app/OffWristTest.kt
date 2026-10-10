package fork.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Made-up events. Times are hours from an arbitrary midnight, in UTC so a day is plain arithmetic. */
class OffWristTest {

    private val day = LocalDate.of(2031, 3, 12)
    private val midnight = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    private fun at(hour: Int, minute: Int = 0) = midnight + hour * 3600L + minute * 60L
    private fun off(hour: Int, minute: Int = 0) = WristEvent(at(hour, minute), on = false)
    private fun on(hour: Int, minute: Int = 0) = WristEvent(at(hour, minute), on = true)

    /** No strap samples anywhere: only the events decide. */
    private val noSamples: (Long) -> Long? = { null }

    /** Strap samples from each of [starts] onwards, as the strap stores them while it is worn. */
    private fun samplesFrom(vararg starts: Long): (Long) -> Long? = { after -> starts.filter { it > after }.minOrNull() }

    @Test
    fun aStoredRowIsReadByItsName() {
        assertEquals(WristEvent(5L, on = false), OffWrist.event(5L, "WRIST_OFF(10)"))
        assertEquals(WristEvent(6L, on = true), OffWrist.event(6L, "WRIST_ON(9)"))
        assertNull(OffWrist.event(7L, "DOUBLE_TAP(14)"))
    }

    @Test
    fun anOffRunsToTheNextOn() {
        assertEquals(listOf(OffWristSpan(at(9), at(17, 30))), OffWrist.spans(listOf(off(9), on(17, 30)), noSamples))
    }

    @Test
    fun eventsOutOfOrderArePairedByTime() {
        assertEquals(listOf(OffWristSpan(at(9), at(10))), OffWrist.spans(listOf(on(10), off(9)), noSamples))
    }

    @Test
    fun aSecondOffInsideAnOpenStretchChangesNothing() {
        assertEquals(listOf(OffWristSpan(at(9), at(12))), OffWrist.spans(listOf(off(9), off(10), on(12)), noSamples))
    }

    @Test
    fun anOnWithNoOffBeforeItIsIgnored() {
        assertEquals(listOf(OffWristSpan(at(13), at(14))), OffWrist.spans(listOf(on(8), off(13), on(14), on(15)), noSamples))
    }

    @Test
    fun anOffWithNeitherAnOnNorASampleAfterItIsStillOff() {
        val spans = OffWrist.spans(listOf(off(9), on(10), off(20)), noSamples)
        assertEquals(listOf(OffWristSpan(at(9), at(10)), OffWristSpan(at(20), null)), spans)
        assertEquals(at(22), spans.last().end(throughTs = at(22)))
        assertEquals(OffWristSpan(at(20), null), OffWrist.offNow(spans))
        assertNull(OffWrist.offNow(spans.dropLast(1)))
    }

    @Test
    fun samplesEndAStretchWhoseOnEventWasLost() {
        // Off at 09:00 and worn again from 11:00 with no on event; off again at 15:00 until 16:00.
        val spans = OffWrist.spans(listOf(off(9), off(15), on(16)), samplesFrom(at(11), at(16)))
        assertEquals(listOf(OffWristSpan(at(9), at(11)), OffWristSpan(at(15), at(16))), spans)
    }

    @Test
    fun aSampleStraightAfterTheOffEventIsStillTheWrist() {
        // The last sample lands two seconds after the off event; the next is when the strap goes back on.
        val spans = OffWrist.spans(listOf(off(9), on(12)), samplesFrom(at(9) + 2, at(12)))
        assertEquals(listOf(OffWristSpan(at(9), at(12))), spans)
    }

    @Test
    fun aStretchStillOpenEndsWhereSamplesStartAgain() {
        assertEquals(listOf(OffWristSpan(at(20), at(21))), OffWrist.spans(listOf(off(20)), samplesFrom(at(21))))
    }

    @Test
    fun onlyStretchesOfTenMinutesAreMentionedAnOpenOneCountedAsFarAsTheEventsReach() {
        val spans = listOf(OffWristSpan(at(9), at(9, 4)), OffWristSpan(at(12), at(12, 10)), OffWristSpan(at(20), null))
        assertEquals(spans.drop(1), OffWrist.mentionable(spans, throughTs = at(21)))
        assertEquals(listOf(spans[1]), OffWrist.mentionable(spans, throughTs = at(20, 9)))
    }

    @Test
    fun aWindowCutsAStretchAtItsEdges() {
        val spans = listOf(OffWristSpan(at(9), at(17)))
        assertEquals(listOf(at(12) to at(17)), OffWrist.within(spans, at(12), at(24), throughTs = at(23)))
        assertEquals(5 * 3600L, OffWrist.secondsWithin(spans, at(12), at(24), throughTs = at(23)))
        assertEquals(0L, OffWrist.secondsWithin(spans, at(18), at(24), throughTs = at(23)))
    }

    @Test
    fun aDayIsNoonToNoonNamedByTheDateItEndsOn() {
        assertEquals(at(-12) to at(12), OffWrist.dayWindow(day, ZoneOffset.UTC))
        // 10:00 to 15:00 straddles noon: two hours belong to this day and three to the next.
        val spans = listOf(OffWristSpan(at(10), at(15)))
        assertEquals(2 * 3600L, OffWrist.secondsOn(day, ZoneOffset.UTC, spans, throughTs = at(23)))
        assertEquals(3 * 3600L, OffWrist.secondsOn(day.plusDays(1), ZoneOffset.UTC, spans, throughTs = at(23)))
        assertEquals(0L, OffWrist.secondsOn(day.minusDays(1), ZoneOffset.UTC, spans, throughTs = at(23)))
    }

    @Test
    fun anOpenStretchCountsOnlyAsFarAsTheStrapHasReported() {
        val spans = listOf(OffWristSpan(at(13), null))
        assertEquals(3600L, OffWrist.secondsOn(day.plusDays(1), ZoneOffset.UTC, spans, throughTs = at(14)))
        assertEquals(0L, OffWrist.secondsOn(day.plusDays(1), ZoneOffset.UTC, spans, throughTs = at(12)))
    }

    @Test
    fun aDayInAnotherOffsetStartsAtItsOwnNoon() {
        val east = ZoneOffset.ofHours(2)
        assertEquals(at(-14) to at(10), OffWrist.dayWindow(day, east))
    }
}
