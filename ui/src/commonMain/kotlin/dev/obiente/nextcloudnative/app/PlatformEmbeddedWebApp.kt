package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Document-only Office editor for a verified core Direct Editing session.
 *
 * The URL carries one-time authorization. This component never authenticates an app dashboard
 * and must not send the account password or permit top-level navigation away from the document.
 */
@Composable
internal expect fun PlatformEmbeddedNextcloudWebApp(
    session: NextcloudSession,
    initialUrl: String,
    onExit: () -> Unit,
    onRetrySession: () -> Unit,
    modifier: Modifier = Modifier,
)
