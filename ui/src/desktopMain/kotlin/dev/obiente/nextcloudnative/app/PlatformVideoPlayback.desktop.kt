package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal actual val platformNativeVideoPlaybackAvailable: Boolean = false

@Composable
internal actual fun PlatformNativeVideoPlayer(
    session: NextcloudSession,
    userId: String,
    source: NativeVideoPlaybackSource,
    onPlaybackEnded: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    Box(modifier)
}
