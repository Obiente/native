package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Authenticated, isolated platform web surface used only when an app has no verified native API. */
@Composable
internal expect fun PlatformEmbeddedNextcloudWebApp(
    session: NextcloudSession,
    initialUrl: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
)
