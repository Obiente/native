package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.ThemePreference

/** Product colors that are useful outside Material's semantic color roles. */
@Immutable
data class NextcloudExtendedColors(
    val appTile: Color,
    val appTileSelected: Color,
    val appIconContainer: Color,
    val appIcon: Color,
    val success: Color,
    val warning: Color,
    val scrim: Color,
)

private object NextcloudPalette {
    val Lavender = Color(0xFFCBB3FD)
    val LavenderStrong = Color(0xFFB999F5)
    val LavenderDark = Color(0xFF684A9E)
    val Charcoal = Color(0xFF0D0F13)
    val CharcoalAlt = Color(0xFF111319)
    val CharcoalCard = Color(0xFF1A1C22)
    val CharcoalIcon = Color(0xFF24232E)
    val CharcoalDivider = Color(0xFF292B31)
    val Paper = Color(0xFFF7F6FA)
    val Ink = Color(0xFF1B191F)
    val Slate = Color(0xFF65616B)
    val Mist = Color(0xFFE5E1E9)
    val White = Color(0xFFFFFFFF)
    val Green = Color(0xFF6FD5C3)
    val Amber = Color(0xFFFFC857)
}

val NextcloudLightColorScheme = lightColorScheme(
    primary = NextcloudPalette.LavenderDark,
    onPrimary = NextcloudPalette.White,
    primaryContainer = Color(0xFFEBDDFF),
    onPrimaryContainer = Color(0xFF25103D),
    secondary = Color(0xFF655A70),
    onSecondary = NextcloudPalette.White,
    secondaryContainer = Color(0xFFEDE0F6),
    onSecondaryContainer = Color(0xFF21182A),
    tertiary = Color(0xFF3C695F),
    onTertiary = NextcloudPalette.White,
    tertiaryContainer = Color(0xFFBFEDE1),
    onTertiaryContainer = Color(0xFF00201A),
    background = NextcloudPalette.Paper,
    onBackground = NextcloudPalette.Ink,
    surface = NextcloudPalette.White,
    onSurface = NextcloudPalette.Ink,
    surfaceVariant = Color(0xFFF0ECF3),
    onSurfaceVariant = NextcloudPalette.Slate,
    surfaceTint = NextcloudPalette.LavenderDark,
    surfaceDim = Color(0xFFDED9E1),
    surfaceBright = NextcloudPalette.White,
    surfaceContainerLowest = NextcloudPalette.White,
    surfaceContainerLow = Color(0xFFF7F3F9),
    surfaceContainer = Color(0xFFF1EDF3),
    surfaceContainerHigh = Color(0xFFEBE7ED),
    surfaceContainerHighest = Color(0xFFE5E1E7),
    inverseSurface = Color(0xFF302E33),
    inverseOnSurface = Color(0xFFF5F0F6),
    inversePrimary = NextcloudPalette.Lavender,
    outline = Color(0xFF71808A),
    outlineVariant = NextcloudPalette.Mist,
    error = Color(0xFFBA1A1A),
    onError = NextcloudPalette.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0x99000000),
)

val NextcloudDarkColorScheme = darkColorScheme(
    primary = NextcloudPalette.Lavender,
    onPrimary = Color(0xFF2C1746),
    primaryContainer = Color(0xFF373044),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFD2C0DA),
    onSecondary = Color(0xFF362D3F),
    secondaryContainer = Color(0xFF4D4356),
    onSecondaryContainer = Color(0xFFEFDDF7),
    tertiary = NextcloudPalette.Green,
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF175046),
    onTertiaryContainer = Color(0xFF8CF2DF),
    background = NextcloudPalette.Charcoal,
    onBackground = Color(0xFFF7F5FA),
    surface = NextcloudPalette.Charcoal,
    onSurface = Color(0xFFF7F5FA),
    surfaceVariant = NextcloudPalette.CharcoalAlt,
    onSurfaceVariant = Color(0xFFA8A6B0),
    surfaceTint = NextcloudPalette.Lavender,
    surfaceDim = NextcloudPalette.Charcoal,
    surfaceBright = NextcloudPalette.CharcoalIcon,
    surfaceContainerLowest = Color(0xFF090B0E),
    surfaceContainerLow = NextcloudPalette.CharcoalAlt,
    surfaceContainer = NextcloudPalette.CharcoalCard,
    surfaceContainerHigh = Color(0xFF202228),
    surfaceContainerHighest = NextcloudPalette.CharcoalIcon,
    inverseSurface = Color(0xFFE5E1E7),
    inverseOnSurface = Color(0xFF302E33),
    inversePrimary = NextcloudPalette.LavenderDark,
    outline = Color(0xFF92929B),
    outlineVariant = NextcloudPalette.CharcoalDivider,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xCC000000),
)

private val LightExtendedColors = NextcloudExtendedColors(
    appTile = NextcloudPalette.White,
    appTileSelected = Color(0xFFEBDDFF),
    appIconContainer = Color(0xFFF0E8F9),
    appIcon = NextcloudPalette.LavenderDark,
    success = Color(0xFF176B5C),
    warning = Color(0xFF8A5200),
    scrim = Color(0x6607131D),
)

private val DarkExtendedColors = NextcloudExtendedColors(
    appTile = NextcloudPalette.CharcoalCard,
    appTileSelected = Color(0xFF373044),
    appIconContainer = NextcloudPalette.CharcoalIcon,
    appIcon = NextcloudPalette.Lavender,
    success = NextcloudPalette.Green,
    warning = NextcloudPalette.Amber,
    scrim = Color(0xB30D0F13),
)

private val LocalNextcloudExtendedColors = staticCompositionLocalOf { LightExtendedColors }

object NextcloudTheme {
    val colors: NextcloudExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNextcloudExtendedColors.current
}

/**
 * Resolves the persisted theme preference consistently on Android and desktop.
 */
@Composable
fun isNextcloudDarkTheme(preference: ThemePreference): Boolean = when (preference) {
    ThemePreference.System -> isSystemInDarkTheme()
    ThemePreference.Light -> false
    ThemePreference.Dark -> true
}

/**
 * Shared application theme. Its background is intentionally emitted by [NextcloudAppBackground]
 * so all screen roots, including dark mode, paint the complete window.
 */
@Composable
fun NextcloudNativeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    typography: Typography = NextcloudTypography,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) NextcloudDarkColorScheme else NextcloudLightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors
    androidx.compose.runtime.CompositionLocalProvider(
        LocalNextcloudExtendedColors provides extendedColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = NextcloudShapes,
            content = content,
        )
    }
}

/** Paints the entire available viewport with the active semantic background. */
@Composable
fun NextcloudAppBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        content()
    }
}

/** Shared responsive content widths for mobile and desktop screens. */
object NextcloudWidths {
    val Compact: Dp = 480.dp
    val Reading: Dp = 720.dp
    val Wide: Dp = 1_120.dp
}
