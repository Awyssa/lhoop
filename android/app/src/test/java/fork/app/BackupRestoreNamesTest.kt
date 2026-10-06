package fork.app

import com.lhoop.data.DataBackup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The app has been renamed, and with it the name of the database inside a backup. A backup written under
 * an earlier name must still restore: it is the only way to carry recorded nights from an old install to
 * a new one, because a renamed app is a different app to Android and starts empty.
 */
class BackupRestoreNamesTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The first bytes of every SQLite file, then some content. */
    private val database = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(2_000) { (it % 251).toByte() }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                for ((name, content) in entries) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun stage(archive: ByteArray, dest: File): DataBackup.StageResult =
        DataBackup.stageBackupSqlite(ByteArrayInputStream(archive), archive.copyOfRange(0, 16), dest)

    @Test
    fun aBackupWrittenUnderAnEarlierAppNameStillStages() {
        val dest = folder.newFile("staged.sqlite")
        val archive = zip("earlier-backup.sqlite" to database, "settings.json" to "{}".toByteArray())
        assertEquals(DataBackup.StageResult.OK, stage(archive, dest))
        assertArrayEquals(database, dest.readBytes())
        assertTrue(DataBackup.isValidSqliteHeader(dest))
    }

    @Test
    fun anArchiveWithNoDatabaseInItIsRefused() {
        val dest = folder.newFile("staged.sqlite")
        val archive = zip("settings.json" to "{}".toByteArray(), "notes.sqlite" to database)
        assertEquals(DataBackup.StageResult.NO_DB_IN_ZIP, stage(archive, dest))
    }

    /** The real thing: every backup pulled off the phone so far. Skipped where there are none. Prints nothing private. */
    @Test
    fun theBackupsPulledOffThePhoneStillStage() {
        val backups = File("../../whoop-data/lhoop-backups").listFiles().orEmpty()
            .filter { it.isFile && it.length() > 1_000_000 }
        assumeTrue("no private backup on this machine", backups.isNotEmpty())
        for (backup in backups) {
            val dest = folder.newFile()
            val header = backup.inputStream().use { it.readNBytes(16) }
            val result = DataBackup.stageBackupSqlite(backup.inputStream(), header, dest)
            assertEquals("a backup from the phone did not stage", DataBackup.StageResult.OK, result)
            assertTrue(DataBackup.isValidSqliteHeader(dest))
        }
    }
}
