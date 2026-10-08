// Fork-owned. The root of the app's UI: the three tabs, the night opened over them, and the place the
// driver starts. The look is in Theme.kt.
package fork.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

private const val TAB_TODAY = 0
private const val TAB_TRENDS = 1
private const val TAB_STRAP = 2

/**
 * The whole UI. `viewModel()` creates [FoundationDriver] during the first composition, exactly where
 * upstream's root composable created its `AppViewModel` (ui/MainActivity.kt 1584 at 6ce65730). It lives here,
 * above the tabs, so the core starts whichever tab is showing.
 */
@Composable
fun AppRoot(driver: FoundationDriver = viewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Upstream ui/MainActivity.kt 1586-1598: every time the app comes to the foreground, ask the
    // Bluetooth client for a sync. The client's own gates make it a no-op when nothing is bonded.
    DisposableEffect(lifecycleOwner, driver) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                driver.ble.onForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var tab by rememberSaveable { mutableIntStateOf(TAB_TODAY) }
    // The night opened over the tabs, by its day. Back closes it; so does picking a tab.
    var openNight by rememberSaveable { mutableStateOf<String?>(null) }
    val status = rememberStrapStatus(driver)
    BackHandler(enabled = openNight != null) { openNight = null }

    Scaffold(
        containerColor = Ink.ground,
        bottomBar = {
            NavigationBar(containerColor = Ink.bar, tonalElevation = 0.dp) {
                listOf(
                    Triple(TAB_TODAY, "Today", Icons.Filled.Bedtime),
                    Triple(TAB_TRENDS, "Trends", Icons.Filled.BarChart),
                    Triple(TAB_STRAP, "Strap", Icons.Filled.Watch),
                ).forEach { (index, label, icon) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = {
                            tab = index
                            openNight = null
                        },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ink.text,
                            selectedTextColor = Ink.text,
                            indicatorColor = Ink.inner,
                            unselectedIconColor = Ink.text2,
                            unselectedTextColor = Ink.text2,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        val night = openNight
        when {
            night != null -> NightScreen(night, status, onBack = { openNight = null }, modifier)
            tab == TAB_TODAY -> TodayScreen(status, onOpenNight = { openNight = it }, onOpenTrends = { tab = TAB_TRENDS }, modifier)
            tab == TAB_TRENDS -> TrendsScreen(status, onOpenNight = { openNight = it }, modifier)
            else -> StatusScreen(driver, modifier)
        }
    }
}
