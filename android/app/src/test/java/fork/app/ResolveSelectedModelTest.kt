package fork.app

import com.lhoop.ble.WhoopModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The strap family a scan-based connect starts on. [resolveSelectedModel] is copied from upstream
 * `ui/AppViewModel.kt`; these are the four cases upstream's own `SelectedModelSeedTest` pinned, kept so
 * the copy cannot drift. A wrong family points the service-filtered scan at the wrong service.
 */
class ResolveSelectedModelTest {
    @Test
    fun recordedFamilyWinsOverTheRememberedPair() {
        assertEquals(
            WhoopModel.WHOOP5_MG,
            resolveSelectedModel(recorded = WhoopModel.WHOOP5_MG, remembered = WhoopModel.WHOOP4),
        )
    }

    @Test
    fun recordedFamilyWinsInTheOtherDirectionToo() {
        assertEquals(
            WhoopModel.WHOOP4,
            resolveSelectedModel(recorded = WhoopModel.WHOOP4, remembered = WhoopModel.WHOOP5_MG),
        )
    }

    @Test
    fun rememberedPairCarriesAnInstallWithNoRecordedFamily() {
        assertEquals(
            WhoopModel.WHOOP5_MG,
            resolveSelectedModel(recorded = null, remembered = WhoopModel.WHOOP5_MG),
        )
    }

    @Test
    fun nothingKnownFallsBackToWhoop4() {
        assertEquals(WhoopModel.WHOOP4, resolveSelectedModel(recorded = null, remembered = null))
    }
}
