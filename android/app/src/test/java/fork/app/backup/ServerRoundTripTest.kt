package fork.app.backup

import fork.app.backup.MadeUpPhone.HOUR
import fork.app.backup.MadeUpPhone.T0
import com.lhoop.data.DataBackup
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The app's backup code against the real server, built from fork/server on this machine.
 *
 * Everything but the two Android adapters runs here as it will on the phone: the checksums, the delta
 * files, the HTTP client and the run. The database is a made-up one behind JDBC. Skipped where there
 * is no Go to build the server with.
 */
class ServerRoundTripTest {

    companion object {
        private val serverDir = File("../../fork/server")
        private var binary: File? = null
        private var buildDir: File? = null

        /** The server, built once for the class. Null where it cannot be built, and the tests are then skipped. */
        @BeforeClass
        @JvmStatic
        fun buildTheServer() {
            if (!File(serverDir, "go.mod").isFile) return
            // The Gradle daemon's PATH is not the shell's, so the usual places are tried as well.
            val go = listOf("go", "/opt/homebrew/bin/go", "/usr/local/go/bin/go", "/usr/local/bin/go").firstOrNull { candidate ->
                runCatching { ProcessBuilder(candidate, "version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
            } ?: return
            val dir = Files.createTempDirectory("lhoop-server-build").toFile()
            val out = File(dir, "lhoop-backup")
            val build = ProcessBuilder(go, "build", "-o", out.path, ".").directory(serverDir).redirectErrorStream(true).start()
            val said = build.inputStream.bufferedReader().readText()
            buildDir = dir
            check(build.waitFor() == 0 && out.isFile) { "the server did not build:\n$said" }
            binary = out
        }

        @AfterClass
        @JvmStatic
        fun removeTheServer() {
            buildDir?.deleteRecursively()
        }
    }

    private val token = "a-made-up-token-for-the-tests"
    private lateinit var dir: File
    private lateinit var data: File
    private lateinit var phone: JdbcSql
    private var server: Process? = null
    private lateinit var url: String

    private fun serverCommand(vararg args: String): ProcessBuilder =
        ProcessBuilder(listOf(binary!!.path, "--data", data.path) + args).redirectErrorStream(true)

    @Before
    fun setUp() {
        assumeTrue("the server is beside the app and Go is here to build it", binary != null)
        dir = Files.createTempDirectory("lhoop-roundtrip").toFile()
        data = File(dir, "data")
        phone = MadeUpPhone.create(File(dir, "phone.sqlite"))
        val port = ServerSocket(0).use { it.localPort }
        url = "http://127.0.0.1:$port"
        val sha256 = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        server = serverCommand("serve", "--bind", "127.0.0.1", "--port", port.toString())
            .redirectOutput(File(dir, "server.log"))
            .apply { environment()["LHOOP_TOKEN_SHA256"] = sha256 }
            .start()
        val up = (1..100).any {
            Thread.sleep(100)
            runCatching { java.net.URL("$url/healthz").readText() == "ok\n" }.getOrDefault(false)
        }
        assertTrue("the server came up: " + File(dir, "server.log").takeIf { it.isFile }?.readText(), up)
    }

    @After
    fun tearDown() {
        server?.destroy()
        server?.waitFor(5, TimeUnit.SECONDS)
        if (::phone.isInitialized) phone.close()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun run(days: Int? = null, withToken: String = token): BackupReport =
        BackupRun(phone, HttpBackupServer(url, withToken), File(dir, "work")) { JdbcSql(it) }
            .run(days, mapOf("app_build" to "test", "app_version" to "made-up", "settings_json" to """{"profile.age":40}"""))

    /** The tables whose rows differ between the phone and the server's copy. */
    private fun differing(): List<String> =
        JdbcSql(File(data, "replica.sqlite")).use { copy ->
            BackupDelta.shapeOf(phone).tables.keys.filter { MadeUpPhone.rowsOf(phone, it) != MadeUpPhone.rowsOf(copy, it) }
        }

    @Test
    fun aFirstRunMakesTheCopyAndASecondFindsNothingWanted() {
        MadeUpPhone.wear(phone, T0, 2 * HOUR + 300)
        val first = run()
        assertTrue(first.schemaCreated)
        assertTrue(first.verified)
        assertEquals(1, first.files)
        assertTrue(first.bytesSent > 0)
        assertEquals(emptyList<String>(), differing())

        val second = run()
        assertFalse(second.schemaCreated)
        assertEquals(0, second.hoursWanted)
        assertTrue(second.verified)
        assertEquals(emptyList<String>(), differing())
    }

    @Test
    fun whatChangedOnThePhoneIsWhatIsSent() {
        MadeUpPhone.wear(phone, T0, 2 * HOUR)
        run()
        MadeUpPhone.wear(phone, T0 + 2 * HOUR, 900)                                       // a new hour
        phone.exec("UPDATE rrInterval SET tsSuspect = 1, srcChannel = 5 WHERE ts = ${T0 + 7}")   // a flag set later
        phone.exec("DELETE FROM dailyMetric")                                             // a small table changed
        val report = run()
        assertEquals("the new hour of five tables, and the hour of the flag", 6, report.hoursWanted)
        assertTrue(report.verified)
        assertEquals(emptyList<String>(), differing())
    }

    @Test
    fun rowsThePhoneDroppedStayOnTheServer() {
        MadeUpPhone.wear(phone, T0, 3 * HOUR)
        run()
        phone.exec("DELETE FROM v18AuxSample WHERE ts < ${T0 + HOUR + 1800}")
        val report = run()
        assertEquals(1, report.hoursWanted)
        assertTrue(report.verified)
        assertEquals(listOf("v18AuxSample"), differing())
        JdbcSql(File(data, "replica.sqlite")).use { copy ->
            assertEquals(3 * HOUR, copy.rows("SELECT count(*) FROM v18AuxSample").single()[0])
        }
        assertEquals(0, run().hoursWanted)
    }

    @Test
    fun aLongFirstRunGoesUpADayToAFileAndARunOverTheLastDaysLeavesTheRest() {
        for (day in listOf(0L, 2L, 30L)) MadeUpPhone.wear(phone, T0 + day * 86_400, 300)
        val recent = run(days = 15)
        assertEquals(1, recent.files)
        assertTrue(recent.verified)
        assertEquals(setOf("hrSample", "rrInterval", "gravitySample", "event", "v18AuxSample"), differing().toSet())
        val all = run()
        assertEquals(2, all.files)
        assertTrue(all.verified)
        assertEquals(emptyList<String>(), differing())
    }

    @Test
    fun aWrongTokenIsRefusedAndNothingIsMade() {
        MadeUpPhone.wear(phone, T0, 60)
        val refused = runCatching { run(withToken = "not-the-token") }.exceptionOrNull()
        assertTrue(refused is ServerSaidNo)
        assertEquals(401, (refused as ServerSaidNo).status)
        assertFalse(File(data, "replica.sqlite").exists())
        assertEquals("nothing left in the work folder", emptyList<String>(), File(dir, "work").list().orEmpty().toList())
    }

    @Test
    fun theServersOwnChecksPassOnWhatTheAppSent() {
        MadeUpPhone.wear(phone, T0, HOUR + 200)
        run()
        MadeUpPhone.wear(phone, T0 + HOUR + 200, 400)
        run()
        for (command in listOf(listOf("verify"), listOf("rebuild", "--to", File(dir, "second").path))) {
            val check = serverCommand(*command.toTypedArray()).start()
            val said = check.inputStream.bufferedReader().readText()
            assertEquals(said, 0, check.waitFor())
        }
    }

    /**
     * The way back, as far as it can be taken off the phone: the server writes a backup, and the core's
     * own import code, the same function the Import button runs, unpacks it. What comes out must be the
     * phone's database again.
     */
    @Test
    fun aBackupTheServerWritesIsOneTheAppsImportTakes() {
        MadeUpPhone.wear(phone, T0, HOUR + 200)
        run()
        for (days in listOf<String?>(null, "30")) {
            val backup = File(dir, "from-server-${days ?: "all"}.lhoopbak")
            val args = listOfNotNull("export", "--out", backup.path, days?.let { "--days" }, days)
            val export = serverCommand(*args.toTypedArray()).start()
            val said = export.inputStream.bufferedReader().readText()
            assertEquals(said, 0, export.waitFor())

            val header = ByteArray(16)
            backup.inputStream().use { it.read(header) }
            val database = File(dir, "imported-${days ?: "all"}.sqlite")
            val settings = File(dir, "imported-${days ?: "all"}.json")
            val staged = DataBackup.stageBackupSqlite(backup.inputStream(), header, database, settings)
            assertEquals(DataBackup.StageResult.OK, staged)
            assertTrue(DataBackup.isValidSqliteHeader(database))
            assertEquals("""{"profile.age":40}""", settings.readText())
            JdbcSql(database).use { imported ->
                assertEquals(41L, imported.rows("PRAGMA user_version").single()[0])
                for (table in BackupDelta.shapeOf(phone).tables.keys) {
                    assertEquals(table, MadeUpPhone.rowsOf(phone, table), MadeUpPhone.rowsOf(imported, table))
                }
            }
        }
    }
}
