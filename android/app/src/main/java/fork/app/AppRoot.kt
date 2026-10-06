// Fork-owned. The root of the app's UI: the theme, the two tabs, and the place the driver starts.
package fork.app

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/** Follows the phone's light or dark setting, with the system's own colours on Android 12 and later. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

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

    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Bedtime, contentDescription = null) },
                    label = { Text("Last night") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Watch, contentDescription = null) },
                    label = { Text("Strap") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> LastNightScreen(Modifier.padding(padding))
            else -> StatusScreen(driver, Modifier.padding(padding))
        }
    }
}
