package fork.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The manifest overlay exists once per flavor because a flavor source set is the only fork-owned place a
 * manifest can merge from. The two copies must not drift: a component removed for one flavor and left in
 * the other would crash that build when Android triggers it.
 */
class ManifestOverlayCopiesTest {
    @Test
    fun theFullAndDemoOverlaysAreIdentical() {
        val full = File("src/full/AndroidManifest.xml")
        val demo = File("src/demo/AndroidManifest.xml")
        assertTrue("run from the app module directory", full.isFile && demo.isFile)
        assertEquals(full.readText(), demo.readText())
    }
}
