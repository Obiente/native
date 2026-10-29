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
    val appAccents: NextcloudAppAccents,
)

/** Accent roles one app family contributes to Material's primary color roles. */
@Immutable
data class NextcloudAccentTones(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)

/**
 * Per-app accents, derived from the website's oklch hues and tuned so each tone keeps WCAG AA
 * contrast on the theme's backgrounds and its own containers. Status colors are separate roles
 * and never take an app accent.
 */
@Immutable
class NextcloudAppAccents internal constructor(
    private val brand: NextcloudAccentTones,
    private val families: Map<NextcloudAppAccentFamily, NextcloudAccentTones>,
) {
    fun tones(family: NextcloudAppAccentFamily): NextcloudAccentTones = families[family] ?: brand
}

private object NextcloudPalette {
    val Mint = Color(0xFF52E0B4)
    val MintStrong = Color(0xFF3BC99E)
    val MintDark = Color(0xFF087D62)
    val Charcoal = Color(0xFF101418)
    val CharcoalAlt = Color(0xFF151B20)
    val CharcoalCard = Color(0xFF1C242B)
    val CharcoalIcon = Color(0xFF253039)
    val CharcoalDivider = Color(0xFF2D3942)
    val Paper = Color(0xFFF5F7F8)
    val Ink = Color(0xFF101820)
    val Slate = Color(0xFF596775)
    val Mist = Color(0xFFE1E7EC)
    val White = Color(0xFFFFFFFF)
    val Green = Color(0xFF52E0B4)
    val Amber = Color(0xFFFFC857)
}

val NextcloudLightColorScheme = lightColorScheme(
    primary = NextcloudPalette.MintDark,
    onPrimary = NextcloudPalette.White,
    primaryContainer = Color(0xFFDFF7EF),
    onPrimaryContainer = Color(0xFF073D31),
    secondary = Color(0xFF375F87),
    onSecondary = NextcloudPalette.White,
    secondaryContainer = Color(0xFFE5F0FF),
    onSecondaryContainer = Color(0xFF102D4C),
    tertiary = Color(0xFF3C695F),
    onTertiary = NextcloudPalette.White,
    tertiaryContainer = Color(0xFFBFEDE1),
    onTertiaryContainer = Color(0xFF00201A),
    background = NextcloudPalette.Paper,
    onBackground = NextcloudPalette.Ink,
    surface = NextcloudPalette.White,
    onSurface = NextcloudPalette.Ink,
    surfaceVariant = Color(0xFFF0F4F6),
    onSurfaceVariant = NextcloudPalette.Slate,
    surfaceTint = NextcloudPalette.MintDark,
    surfaceDim = Color(0xFFDCE3E8),
    surfaceBright = NextcloudPalette.White,
    surfaceContainerLowest = NextcloudPalette.White,
    surfaceContainerLow = Color(0xFFF8FAFB),
    surfaceContainer = Color(0xFFF1F5F7),
    surfaceContainerHigh = Color(0xFFEBF0F3),
    surfaceContainerHighest = Color(0xFFE2E8ED),
    inverseSurface = Color(0xFF27333C),
    inverseOnSurface = Color(0xFFF0F5F8),
    inversePrimary = NextcloudPalette.Mint,
    outline = Color(0xFF71808A),
    outlineVariant = NextcloudPalette.Mist,
    error = Color(0xFFBA1A1A),
    onError = NextcloudPalette.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0x99000000),
)

val NextcloudDarkColorScheme = darkColorScheme(
    primary = NextcloudPalette.Mint,
    onPrimary = Color(0xFF063E30),
    primaryContainer = Color(0xFF153E34),
    onPrimaryContainer = Color(0xFFDFF7EF),
    secondary = Color(0xFFA6CCFF),
    onSecondary = Color(0xFF15304F),
    secondaryContainer = Color(0xFF254866),
    onSecondaryContainer = Color(0xFFDDEBFF),
    tertiary = NextcloudPalette.Green,
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF175046),
    onTertiaryContainer = Color(0xFF8CF2DF),
    background = NextcloudPalette.Charcoal,
    onBackground = Color(0xFFF5F7F8),
    surface = NextcloudPalette.Charcoal,
    onSurface = Color(0xFFF5F7F8),
    surfaceVariant = NextcloudPalette.CharcoalAlt,
    onSurfaceVariant = Color(0xFFA8B6C2),
    surfaceTint = NextcloudPalette.Mint,
    surfaceDim = NextcloudPalette.Charcoal,
    surfaceBright = NextcloudPalette.CharcoalIcon,
    surfaceContainerLowest = Color(0xFF0B1014),
    surfaceContainerLow = NextcloudPalette.CharcoalAlt,
    surfaceContainer = NextcloudPalette.CharcoalCard,
    surfaceContainerHigh = Color(0xFF222C34),
    surfaceContainerHighest = NextcloudPalette.CharcoalIcon,
    inverseSurface = Color(0xFFE2E8ED),
    inverseOnSurface = Color(0xFF27333C),
    inversePrimary = NextcloudPalette.MintDark,
    outline = Color(0xFF8D9EAB),
    outlineVariant = NextcloudPalette.CharcoalDivider,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xCC000000),
)

private val LightAppAccents = NextcloudAppAccents(
    brand = NextcloudAccentTones(
        primary = NextcloudPalette.MintDark,
        onPrimary = NextcloudPalette.White,
        primaryContainer = Color(0xFFDFF7EF),
        onPrimaryContainer = Color(0xFF073D31),
    ),
    families = mapOf(
        NextcloudAppAccentFamily.Photos to NextcloudAccentTones(
            Color(0xFFAD5C15), NextcloudPalette.White, Color(0xFFFFEADD), Color(0xFF482304),
        ),
        NextcloudAppAccentFamily.Talk to NextcloudAccentTones(
            Color(0xFF1174B4), NextcloudPalette.White, Color(0xFFE1F1FF), Color(0xFF05304E),
        ),
        NextcloudAppAccentFamily.Calendar to NextcloudAccentTones(
            Color(0xFFB45057), NextcloudPalette.White, Color(0xFFFEE9E9), Color(0xFF4B1D20),
        ),
        NextcloudAppAccentFamily.Deck to NextcloudAccentTones(
            Color(0xFF7B62B8), NextcloudPalette.White, Color(0xFFEFECFE), Color(0xFF31254D),
        ),
        NextcloudAppAccentFamily.FolderSync to NextcloudAccentTones(
            Color(0xFF027C7C), NextcloudPalette.White, Color(0xFFD5F6F6), Color(0xFF023536),
        ),
        NextcloudAppAccentFamily.Apps to NextcloudAccentTones(
            Color(0xFF017A9B), NextcloudPalette.White, Color(0xFFDAF3FE), Color(0xFF013343),
        ),
    ),
)

private val DarkAppAccents = NextcloudAppAccents(
    brand = NextcloudAccentTones(
        primary = NextcloudPalette.Mint,
        onPrimary = Color(0xFF063E30),
        primaryContainer = Color(0xFF153E34),
        onPrimaryContainer = Color(0xFFDFF7EF),
    ),
    families = mapOf(
        NextcloudAppAccentFamily.Photos to NextcloudAccentTones(
            Color(0xFFFEA668), Color(0xFF3C1D05), Color(0xFF4D2C15), Color(0xFFFFE6D6),
        ),
        NextcloudAppAccentFamily.Talk to NextcloudAccentTones(
            Color(0xFF80C5FF), Color(0xFF062942), Color(0xFF173852), Color(0xFFDCEEFE),
        ),
        NextcloudAppAccentFamily.Calendar to NextcloudAccentTones(
            Color(0xFFFF9FA2), Color(0xFF3F191B), Color(0xFF50282A), Color(0xFFFEE4E4),
        ),
        NextcloudAppAccentFamily.Deck to NextcloudAccentTones(
            Color(0xFFC3AFFE), Color(0xFF292040), Color(0xFF382F51), Color(0xFFECE8FE),
        ),
        NextcloudAppAccentFamily.FolderSync to NextcloudAccentTones(
            Color(0xFF2AD7D7), Color(0xFF002D2D), Color(0xFF013E3E), Color(0xFFD1F3F2),
        ),
        NextcloudAppAccentFamily.Apps to NextcloudAccentTones(
            Color(0xFF49CFFC), Color(0xFF012B39), Color(0xFF043B4C), Color(0xFFD3F1FD),
        ),
    ),
)

private val LightExtendedColors = NextcloudExtendedColors(
    appTile = NextcloudPalette.White,
    appTileSelected = Color(0xFFDFF7EF),
    appIconContainer = Color(0xFFE5F0FF),
    appIcon = Color(0xFF2469BE),
    success = Color(0xFF176B5C),
    warning = Color(0xFF8A5200),
    scrim = Color(0x6607131D),
    appAccents = LightAppAccents,
)

private val DarkExtendedColors = NextcloudExtendedColors(
    appTile = NextcloudPalette.CharcoalCard,
    appTileSelected = Color(0xFF153E34),
    appIconContainer = NextcloudPalette.CharcoalIcon,
    appIcon = Color(0xFF75B4FF),
    success = NextcloudPalette.Green,
    warning = NextcloudPalette.Amber,
    scrim = Color(0xB30D0F13),
    appAccents = DarkAppAccents,
)

private val LocalNextcloudExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** Theme-specific app accents, for contrast checks outside composition. */
internal fun nextcloudAppAccents(darkTheme: Boolean): NextcloudAppAccents =
    if (darkTheme) DarkAppAccents else LightAppAccents

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
