package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Isolated platform web surface for a verified same-origin Nextcloud route.
 *
 * Session authentication is enabled only for an app dashboard. A Direct Editing URL already
 * carries its one-time authorization and must not also receive the account password.
 */
@Composable
internal expect fun PlatformEmbeddedNextcloudWebApp(
    session: NextcloudSession,
    initialUrl: String,
    authenticateWithSession: Boolean,
    onExit: () -> Unit,
    onRetrySession: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)
