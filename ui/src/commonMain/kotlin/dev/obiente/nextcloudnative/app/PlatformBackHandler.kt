package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable

/** Maps native back navigation into the shared screen stack when supported. */
@Composable
internal expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
