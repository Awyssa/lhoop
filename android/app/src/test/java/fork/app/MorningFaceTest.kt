package fork.app

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** What the home-screen widget says, which cannot be looked at on the Mac. Every figure here is made up. */
class MorningFaceTest {

    private val day = "2031-03-12"   // a Wednesday

    private fun numbers(recovery: Int? = 63, baseline: Int = 8, ongoing: Boolean = false, deep: Int? = 100, rem: Int? = 115, hrv: Int? = 62) =
        MorningNumbers(day, recovery, baseline, ongoing, asleepMin = 432, deepMin = deep, remMin = rem, hrvMs = hrv)

    private fun face(n: MorningNumbers?, today: String = day) = MorningFace.of(n, today, Locale.UK)

    @Test
    fun lastNightIsShownInFullAndNamedByItsDate() {
        val f = face(numbers())
        assertEquals("Wed 12 Mar", f.title)
        assertEquals("", f.note)
        assertEquals("63%", f.recovery)
        assertEquals("Partly recovered", f.word)
        assertEquals(MorningFace.AMBER, f.recoveryColor)
        assertEquals(MorningFace.AMBER, f.wordColor)
        assertEquals("7h 12m", f.asleep)
        assertEquals("1h 40m", f.deep)
        assertEquals("1h 55m", f.rem)
        assertEquals("62 ms", f.hrv)
        assertEquals(MorningFace.TEXT, f.figureColor)
        // Estimates are a step quieter than measured figures.
        assertEquals(MorningFace.TEXT2, f.estimateColor)
    }

    @Test
    fun theBandColoursFollowTheScore() {
        assertEquals(MorningFace.GREEN, face(numbers(recovery = 84)).recoveryColor)
        assertEquals("Well recovered", face(numbers(recovery = 84)).word)
        assertEquals(MorningFace.RED, face(numbers(recovery = 20)).recoveryColor)
        assertEquals("Not recovered", face(numbers(recovery = 20)).word)
    }

    @Test
    fun aShortBaselineIsSaidWhileThereIsAScore() {
        assertEquals("Based on 4 nights", face(numbers(baseline = 4)).note)
        assertEquals(MorningFace.TEXT3, face(numbers(baseline = 4)).noteColor)
        assertEquals("", face(numbers(baseline = 8)).note)
        assertEquals("", face(numbers(recovery = null, baseline = 2)).note)
    }

    @Test
    fun aNightWithoutAScoreOrStagesShowsDashes() {
        val f = face(numbers(recovery = null, baseline = 2, deep = null, rem = null, hrv = null))
        assertEquals("—", f.recovery)
        assertEquals("No score yet", f.word)
        assertEquals(MorningFace.TEXT2, f.recoveryColor)
        assertEquals("—", f.deep)
        assertEquals("—", f.rem)
        assertEquals("—", f.hrv)
        assertEquals("7h 12m", f.asleep)
    }

    @Test
    fun anOlderNightGoesGreyAndSaysNothingIsInForLastNight() {
        // The same stored numbers, looked at the next day: nothing new has been worked out since.
        val f = face(numbers(baseline = 4), today = "2031-03-13")
        assertEquals("Wed 12 Mar", f.title)
        assertEquals("Nothing for last night yet", f.note)
        assertEquals(MorningFace.AMBER, f.noteColor)
        assertEquals("63%", f.recovery)
        listOf(f.recoveryColor, f.wordColor, f.figureColor, f.estimateColor).forEach { assertEquals(MorningFace.TEXT3, it) }
    }

    @Test
    fun aNightThatMayNotBeOverGoesGreyAndSaysSo() {
        val f = face(numbers(ongoing = true))
        assertEquals("May not be complete", f.note)
        assertEquals(MorningFace.AMBER, f.noteColor)
        listOf(f.recoveryColor, f.wordColor, f.figureColor, f.estimateColor).forEach { assertEquals(MorningFace.TEXT3, it) }
    }

    @Test
    fun withNothingStoredItShowsWhatTheLayoutItselfShows() {
        val f = face(null)
        assertEquals(MorningFace.empty, f)
        assertEquals("LHOOP", f.title)
        assertEquals("No night yet", f.word)
        // The layout's own default texts, which show if no code has run, say the same.
        val strings = File("src/main/res/values/morning_widget.xml").readText()
        assertTrue(strings.contains("""<string name="morning_widget_no_night" translatable="false">No night yet</string>"""))
        assertTrue(strings.contains("""<string name="morning_widget_dash" translatable="false">—</string>"""))
    }

    @Test
    fun theWidgetsColoursAreTheAppsOwn() {
        assertEquals(Ink.text.toArgb(), MorningFace.TEXT)
        assertEquals(Ink.text2.toArgb(), MorningFace.TEXT2)
        assertEquals(Ink.text3.toArgb(), MorningFace.TEXT3)
        assertEquals(Ink.green.toArgb(), MorningFace.GREEN)
        assertEquals(Ink.amber.toArgb(), MorningFace.AMBER)
        assertEquals(Ink.red.toArgb(), MorningFace.RED)
    }

    @Test
    fun theLayoutUsesOnlyViewsAWidgetMayHold() {
        // A home-screen widget is drawn by another process and may hold only a few kinds of view. One wrong
        // tag and the launcher shows "Can't load widget", which nothing on the Mac would otherwise catch.
        val file = File("src/main/res/layout/morning_widget.xml")
        assertTrue("run from the app module directory", file.isFile)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val tags = HashSet<String>()
        fun walk(e: Element) {
            tags += e.tagName
            val children = e.childNodes
            for (i in 0 until children.length) (children.item(i) as? Element)?.let(::walk)
        }
        walk(root)
        assertEquals(setOf("LinearLayout", "TextView"), tags)
        // Every view the code sets is in the layout once.
        val text = file.readText()
        listOf("root", "title", "note", "recovery", "word", "asleep", "deep", "rem", "hrv").forEach { id ->
            assertEquals("morning_$id", 1, Regex("""android:id="@\+id/morning_$id"""").findAll(text).count())
        }
    }
}
