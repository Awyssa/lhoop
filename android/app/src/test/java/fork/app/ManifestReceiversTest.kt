package fork.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The two components the app adds of its own are declared once, in the shared manifest, and the flavor
 * overlays (which only remove things) leave them alone. A slip here is silent until the phone restarts
 * or a widget is placed, so it is checked on the Mac.
 */
class ManifestReceiversTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun receivers(): Map<String, Element> {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("run from the app module directory", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("receiver")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttributeNS(androidNs, "name") }
    }

    private fun actions(receiver: Element): List<String> {
        val nodes = receiver.getElementsByTagName("action")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
    }

    private fun overlays() = listOf(File("src/full/AndroidManifest.xml"), File("src/demo/AndroidManifest.xml")).map { it.readText() }

    @Test
    fun theBootReceiverIsDeclaredForARestartOnlyAndIsNotExported() {
        val receiver = receivers()[BootReceiver::class.java.name]
        assertNotNull("fork.app.BootReceiver is not in the main manifest", receiver)
        assertEquals("false", receiver!!.getAttributeNS(androidNs, "exported"))
        assertEquals(listOf("android.intent.action.BOOT_COMPLETED"), actions(receiver))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android.permission.RECEIVE_BOOT_COMPLETED"))
    }

    @Test
    fun theWidgetIsDeclaredForTheLauncherWithItsProviderInfo() {
        val receiver = receivers()[MorningWidgetReceiver::class.java.name]
        assertNotNull("fork.app.MorningWidgetReceiver is not in the main manifest", receiver)
        assertEquals("true", receiver!!.getAttributeNS(androidNs, "exported"))
        assertEquals(listOf("android.appwidget.action.APPWIDGET_UPDATE"), actions(receiver))
        val meta = receiver.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("android.appwidget.provider", meta.getAttributeNS(androidNs, "name"))
        assertEquals("@xml/morning_widget_info", meta.getAttributeNS(androidNs, "resource"))
        assertTrue(File("src/main/res/xml/morning_widget_info.xml").isFile)
    }

    @Test
    fun noOverlayRemovesWhatTheAppAdded() {
        overlays().forEach { overlay ->
            assertFalse(overlay.contains("fork.app."))
            assertFalse(overlay.contains("RECEIVE_BOOT_COMPLETED\" tools:node=\"remove\""))
        }
    }
}
