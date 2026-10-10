package fork.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a resume brings the foreground service back. The Bluetooth side of it cannot be tested here. */
class StrapStartupTest {

    private fun start(
        strapSaved: Boolean = true,
        backgroundConnection: Boolean = true,
        intentionallyDisconnected: Boolean = false,
        serviceInForeground: Boolean = false,
    ) = StrapStartup.shouldStartService(strapSaved, backgroundConnection, intentionallyDisconnected, serviceInForeground)

    @Test
    fun aServiceThatAndroidTookAwayIsStartedAgain() {
        assertTrue(start())
    }

    @Test
    fun aServiceAlreadyInTheForegroundIsLeftAlone() {
        assertFalse(start(serviceInForeground = true))
    }

    @Test
    fun aDeliberateDisconnectStaysDown() {
        assertFalse(start(intentionallyDisconnected = true))
    }

    @Test
    fun nothingStartsWithoutASavedStrapOrWithTheBackgroundSettingOff() {
        assertFalse(start(strapSaved = false))
        assertFalse(start(backgroundConnection = false))
    }

    @Test
    fun everyOtherCombinationIsANo() {
        for (saved in listOf(true, false)) for (background in listOf(true, false))
            for (deliberate in listOf(true, false)) for (foreground in listOf(true, false)) {
                val expected = saved && background && !deliberate && !foreground
                assertTrue(start(saved, background, deliberate, foreground) == expected)
            }
    }
}
