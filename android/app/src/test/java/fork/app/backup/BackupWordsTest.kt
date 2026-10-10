package fork.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** What the Strap tab says about the server backup, and which addresses it takes. Times are made up. */
class BackupWordsTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private fun ms(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2031, 3, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    private val now = ms(12, 21)   // a Wednesday evening

    private fun status(address: String? = "https://backup.example.com", running: Boolean = false, lastOkAt: Long = 0, problem: String? = null) =
        ServerBackup.Status(address, running, lastOkAt, problem)

    @Test
    fun theLineSaysWhereTheBackupStands() {
        assertEquals("not set up", BackupWords.line(status(address = null), now, zone, Locale.UK))
        assertEquals("nothing sent yet", BackupWords.line(status(), now, zone, Locale.UK))
        assertEquals("running", BackupWords.line(status(running = true, lastOkAt = ms(12, 7)), now, zone, Locale.UK))
        assertEquals("complete 07:12", BackupWords.line(status(lastOkAt = ms(12, 7, 12)), now, zone, Locale.UK))
        assertEquals("complete Tue 07:12", BackupWords.line(status(lastOkAt = ms(11, 7, 12)), now, zone, Locale.UK))
        assertEquals("complete 3 Mar 07:12", BackupWords.line(status(lastOkAt = ms(3, 7, 12)), now, zone, Locale.UK))
    }

    @Test
    fun aWarningShowsForAProblemOrTwoDaysWithoutABackup() {
        assertNull(BackupWords.warning(status(address = null, problem = "left over"), now))
        assertNull(BackupWords.warning(status(lastOkAt = ms(11, 7)), now))
        assertNull("nothing sent yet is not a warning", BackupWords.warning(status(), now))
        assertEquals("No complete backup for over two days.", BackupWords.warning(status(lastOkAt = ms(10, 20, 59)), now))
        assertNull(BackupWords.warning(status(lastOkAt = ms(10, 21, 1)), now))
        assertEquals("The server could not be reached.", BackupWords.warning(status(lastOkAt = ms(12, 7), problem = "The server could not be reached."), now))
        assertNull("no warning over a run in progress", BackupWords.warning(status(running = true, problem = "earlier"), now))
    }

    @Test
    fun aRefusalIsPutInTheOwnersTerms() {
        assertEquals("The server did not accept the token.", BackupWords.refusal(401, "401: unauthorised"))
        assertEquals("The server's copy belongs to another version of the app's data.", BackupWords.refusal(409, "409: x"))
        assertEquals("The server is short of disk.", BackupWords.refusal(507, "507: the server is short of disk"))
        assertEquals("The server failed (502). It will be tried again.", BackupWords.refusal(502, "502: "))
        assertEquals("The server refused the backup (422): the upload is damaged", BackupWords.refusal(422, "422: the upload is damaged"))
    }

    @Test
    fun onlyAnHttpsAddressWithNothingAfterTheNameIsTaken() {
        assertNull(BackupAddress.problem("https://backup.example.com"))
        assertNull(BackupAddress.problem("  https://backup.example.com/  "))
        assertEquals("https://backup.example.com", BackupAddress.clean("  https://backup.example.com/ "))
        assertEquals("https://backup.example.com:8443", BackupAddress.clean("https://backup.example.com:8443"))
        assertEquals("The address must start with https://", BackupAddress.problem("http://backup.example.com"))
        assertEquals("The address must start with https://", BackupAddress.problem("ftp://backup.example.com"))
        assertEquals("That is not a web address.", BackupAddress.problem("backup.example.com"))
        assertEquals("That is not a web address.", BackupAddress.problem(""))
        for (extra in listOf("https://backup.example.com/v1", "https://backup.example.com/?a=b", "https://user:secret@backup.example.com", "https://backup.example.com/#x")) {
            assertEquals(extra, "Give the address alone, such as https://backup.example.com", BackupAddress.problem(extra))
            assertNull(BackupAddress.clean(extra))
        }
    }

    @Test
    fun plainHttpIsTakenOnlyForAHostNamedAsTheEmulatorsOwn() {
        val emulator = setOf("10.0.2.2")
        assertNull(BackupAddress.problem("http://10.0.2.2:8787", emulator))
        assertEquals("http://10.0.2.2:8787", BackupAddress.clean("http://10.0.2.2:8787/", emulator))
        assertEquals("The address must start with https://", BackupAddress.problem("http://10.0.2.2:8787"))
        assertEquals("The address must start with https://", BackupAddress.problem("http://192.168.1.20:8787", emulator))
        assertEquals("The address must start with https://", BackupAddress.problem("http://10.0.2.2.example.com", emulator))
    }
}
