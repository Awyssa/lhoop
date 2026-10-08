// Fork-owned. The app's one look: a dark ground for a screen read in bed in the morning, one blue for
// sleep, and the three recovery colours. It does not follow the phone's light setting; that is the
// owner's choice (fork/docs/02-decisions.md).
package fork.app

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Every colour the screens use. Text colours all read at 4.5:1 or better on [card] and [ground]. */
internal object Ink {
    val ground = Color(0xFF0F1218)
    val card = Color(0xFF181D26)
    val inner = Color(0xFF212836)
    val hairline = Color(0xFF2B3342)
    val bar = Color(0xFF12161D)

    val text = Color(0xFFEEF1F5)
    val text2 = Color(0xFFAEB6C4)
    val text3 = Color(0xFF8D97A8)

    /** Asleep, and anything that is "the sleep figure". */
    val sleep = Color(0xFF8FAAFF)
    val restless = Color(0xFF5266A8)
    val awake = Color(0xFFC9CFDA)

    /** The band a chart draws for "your usual range". */
    val range = Color(0xFF2E3A5C)

    /** The stage split, told apart by lightness as well as hue. */
    val deep = Color(0xFF5E7DF2)
    val rem = Color(0xFFCDB8FF)
    val light = Color(0xFF3D4A70)

    val green = Color(0xFF46C486)
    val amber = Color(0xFFE8B84A)
    val red = Color(0xFFF0726C)
}

private val scheme = darkColorScheme(
    primary = Ink.sleep,
    onPrimary = Ink.ground,
    primaryContainer = Ink.range,
    onPrimaryContainer = Ink.text,
    secondary = Ink.sleep,
    onSecondary = Ink.ground,
    secondaryContainer = Ink.inner,
    onSecondaryContainer = Ink.text,
    tertiary = Ink.amber,
    onTertiary = Ink.ground,
    background = Ink.ground,
    onBackground = Ink.text,
    surface = Ink.ground,
    onSurface = Ink.text,
    surfaceVariant = Ink.card,
    onSurfaceVariant = Ink.text2,
    surfaceContainerLowest = Ink.ground,
    surfaceContainerLow = Ink.card,
    surfaceContainer = Ink.card,
    surfaceContainerHigh = Ink.card,
    surfaceContainerHighest = Ink.inner,
    outline = Ink.text3,
    outlineVariant = Ink.hairline,
    error = Ink.red,
    onError = Ink.ground,
)

/** The dark look everywhere, with figures set in digits of equal width so columns of numbers line up. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            content = content,
        )
    }
}

internal fun bandColor(band: RecoveryBand?): Color = when (band) {
    RecoveryBand.GREEN -> Ink.green
    RecoveryBand.YELLOW -> Ink.amber
    RecoveryBand.RED -> Ink.red
    null -> Ink.text2
}
