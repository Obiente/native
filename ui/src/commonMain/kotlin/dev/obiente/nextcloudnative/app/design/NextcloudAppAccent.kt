package dev.obiente.nextcloudnative.app.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.obiente.nextcloudnative.app.canonicalAppWorkspaceId

/**
 * App families that carry their own accent. Home, Activity, Settings, Files, and apps without
 * a family keep the brand mint, so the accent marks where you are without recoloring everything.
 */
enum class NextcloudAppAccentFamily {
    Brand,
    Photos,
    Talk,
    Calendar,
    Deck,
    FolderSync,
    Apps,
}

internal fun nextcloudAppAccentFamily(destinationKey: NextcloudShellDestinationKey): NextcloudAppAccentFamily =
    when (destinationKey) {
        is NextcloudShellDestinationKey.Root -> when (destinationKey.destination) {
            NextcloudDestination.FolderSync -> NextcloudAppAccentFamily.FolderSync
            NextcloudDestination.Apps -> NextcloudAppAccentFamily.Apps
            NextcloudDestination.Home,
            NextcloudDestination.Activity,
            NextcloudDestination.Settings,
            -> NextcloudAppAccentFamily.Brand
        }
        is NextcloudShellDestinationKey.App -> when (canonicalAppWorkspaceId(destinationKey.canonicalAppId)) {
            "photos", "memories" -> NextcloudAppAccentFamily.Photos
            "spreed" -> NextcloudAppAccentFamily.Talk
            "calendar" -> NextcloudAppAccentFamily.Calendar
            "deck" -> NextcloudAppAccentFamily.Deck
            else -> NextcloudAppAccentFamily.Brand
        }
    }

/**
 * Gives the shell and its workspace the accent of [destinationKey] as Material's primary roles.
 *
 * The scheme switches in one step: the color scheme is a static composition local, so animating
 * it would recompose the whole workspace on every frame. The switch lands on the frame the new
 * destination begins its entrance from transparent, so it is not seen; navigation chrome that
 * stays visible animates its own selection colors toward the new accent instead.
 */
@Composable
internal fun NextcloudAppAccentScope(
    destinationKey: NextcloudShellDestinationKey,
    content: @Composable () -> Unit,
) {
    val tones = NextcloudTheme.colors.appAccents.tones(nextcloudAppAccentFamily(destinationKey))
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, tones) {
        base.copy(
            primary = tones.primary,
            onPrimary = tones.onPrimary,
            primaryContainer = tones.primaryContainer,
            onPrimaryContainer = tones.onPrimaryContainer,
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
