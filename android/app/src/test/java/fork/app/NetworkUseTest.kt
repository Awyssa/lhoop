package fork.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AGENTS.md: the app uses the network for the backup to the owner's server and for nothing else. This
 * holds the code to it. Any source file that opens a connection, or brings in a library that could,
 * fails the build unless it is the one file whose job that is.
 */
class NetworkUseTest {

    private val allowed = setOf("src/main/java/fork/app/backup/BackupServer.kt")

    private val signs = listOf(
        "java.net.", "javax.net.", "okhttp3", "HttpURLConnection", "HttpsURLConnection", "openConnection(",
        "SocketChannel", "DatagramSocket", "android.net.http", "io.ktor", "retrofit2", "DownloadManager",
    )

    @Test
    fun onlyTheBackupClientOpensAConnection() {
        val sources = File("src/main/java")
        assertTrue("run from the app module directory", sources.isDirectory)
        val found = sources.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { file -> file.readText().let { text -> signs.any { it in text } } }
            .map { it.path.replace(File.separatorChar, '/') }
            .toSet()
        assertEquals(allowed, found)
    }

    @Test
    fun theReleaseBuildRefusesPlainHttpAndOnlyADebugBuildMayReachTheEmulatorsHost() {
        val release = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(release.contains("""<base-config cleartextTrafficPermitted="false" />"""))
        assertTrue("no exception in the release file", !release.contains("domain-config"))
        val debug = File("src/debug/res/xml/network_security_config.xml").readText()
        assertTrue(debug.contains("""<base-config cleartextTrafficPermitted="false" />"""))
        assertEquals(listOf("10.0.2.2"), Regex("<domain[^>]*>([^<]+)</domain>").findAll(debug).map { it.groupValues[1] }.toList())
    }
}
