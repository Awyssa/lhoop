package fork.app.scoring

import fork.app.scoring.StateScript.HOUR
import fork.app.scoring.StateScript.record
import fork.app.scoring.StateScript.sleep
import fork.app.scoring.StateScript.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class SleepDaysTest {

    private val utcOffset = ZoneOffset.UTC

    @Test
    fun aNightBelongsToTheDayItsWindowOverlapsMost() {
        // 23:30 to 07:00: wholly inside the window of the day it ends on.
        val night = SleepDays.nightDay(utc(2026, 3, 10, 23, 30), utc(2026, 3, 11, 7, 0) - 1, utcOffset)!!
        assertEquals(LocalDate.of(2026, 3, 11), night.first)
        assertEquals(7L * HOUR + HOUR / 2, night.second)

        // An early night, 20:00 to 23:00: the two hours after 21:00 make it the next day's night.
        val early = SleepDays.nightDay(utc(2026, 3, 10, 20, 0), utc(2026, 3, 10, 23, 0) - 1, utcOffset)!!
        assertEquals(LocalDate.of(2026, 3, 11), early.first)
        assertEquals(2L * HOUR, early.second)

        // A late riser, 09:00 to 13:00: three hours before noon.
        val late = SleepDays.nightDay(utc(2026, 3, 11, 9, 0), utc(2026, 3, 11, 13, 0) - 1, utcOffset)!!
        assertEquals(LocalDate.of(2026, 3, 11), late.first)
        assertEquals(3L * HOUR, late.second)
    }

    @Test
    fun aSleepBetweenNoonAndNineInTheEveningIsNeverANight() {
        assertNull(SleepDays.nightDay(utc(2026, 3, 10, 17, 0), utc(2026, 3, 10, 20, 30), utcOffset))
        assertNull(SleepDays.nightDay(utc(2026, 3, 10, 12, 0), utc(2026, 3, 10, 21, 0) - 1, utcOffset))
    }

    @Test
    fun theDayIsReadWhereTheSleepWasSlept() {
        // The same instants, nine hours east: 08:30 to 16:00 local, so only the morning part is night.
        val east = SleepDays.nightDay(utc(2026, 3, 10, 23, 30), utc(2026, 3, 11, 7, 0) - 1, ZoneOffset.ofHours(9))!!
        assertEquals(LocalDate.of(2026, 3, 11), east.first)
        assertEquals(3L * HOUR + HOUR / 2, east.second)
    }

    @Test
    fun anAfternoonSleepIsListedWithTheNightThatFollowsIt() {
        val first = record(sleep(utc(2026, 3, 10, 1, 0), utc(2026, 3, 10, 7, 0)))
        val nap = record(sleep(utc(2026, 3, 10, 14, 30), utc(2026, 3, 10, 16, 0)))
        val second = record(sleep(utc(2026, 3, 11, 2, 0), utc(2026, 3, 11, 6, 30)))
        val third = record(sleep(utc(2026, 3, 11, 22, 25), utc(2026, 3, 12, 6, 40), bedStartTs = utc(2026, 3, 11, 22, 10)))

        val assigned = SleepDays.assign(listOf(third, nap, first, second))
        assertEquals(
            listOf(
                SleepDay(LocalDate.of(2026, 3, 10), first, emptyList()),
                SleepDay(LocalDate.of(2026, 3, 11), second, listOf(nap)),
                SleepDay(LocalDate.of(2026, 3, 12), third, emptyList()),
            ),
            assigned.days,
        )
        assertEquals(emptyList<SleepRecord>(), assigned.napsSince)
    }

    @Test
    fun ofTwoSleepsInOneNightTheLongerInTheWindowIsTheNight() {
        // Asleep 23:00 to 03:00, awake two hours, asleep again 05:00 to 07:00.
        val main = record(sleep(utc(2026, 3, 10, 23, 0), utc(2026, 3, 11, 3, 0)))
        val again = record(sleep(utc(2026, 3, 11, 5, 0), utc(2026, 3, 11, 7, 0)))
        val assigned = SleepDays.assign(listOf(main, again))
        assertEquals(listOf(SleepDay(LocalDate.of(2026, 3, 11), main, emptyList())), assigned.days)
        // Until the next night exists, the second sleep is a sleep since the last night.
        assertEquals(listOf(again), assigned.napsSince)

        val next = record(sleep(utc(2026, 3, 11, 23, 0), utc(2026, 3, 12, 6, 0)))
        assertEquals(
            SleepDay(LocalDate.of(2026, 3, 12), next, listOf(again)),
            SleepDays.assign(listOf(main, again, next)).days.last(),
        )
    }

    @Test
    fun aSleepLongBeforeTheNextNightIsNotListedWithIt() {
        // No night on the 11th: the strap was off. The nap on the 10th is more than a day before the next night.
        val night = record(sleep(utc(2026, 3, 9, 23, 0), utc(2026, 3, 10, 7, 0)))
        val nap = record(sleep(utc(2026, 3, 10, 14, 0), utc(2026, 3, 10, 15, 0)))
        val later = record(sleep(utc(2026, 3, 11, 23, 30), utc(2026, 3, 12, 7, 0)))
        val assigned = SleepDays.assign(listOf(night, nap, later))
        assertEquals(listOf(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 12)), assigned.days.map { it.day })
        assertEquals(emptyList<SleepRecord>(), assigned.days.last().naps)
        assertEquals(emptyList<SleepRecord>(), assigned.napsSince)
    }

    @Test
    fun withNoNightEverySleepIsListedOnItsOwn() {
        val nap = record(sleep(utc(2026, 3, 10, 14, 0), utc(2026, 3, 10, 15, 0)))
        val assigned = SleepDays.assign(listOf(nap))
        assertEquals(emptyList<SleepDay>(), assigned.days)
        assertEquals(listOf(nap), assigned.napsSince)
        assertEquals(AssignedSleeps(emptyList(), emptyList()), SleepDays.assign(emptyList()))
    }
}
