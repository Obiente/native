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
    rangeSource: NativeVideoRangeSource?,
    compatibilityPlaybackRequested: Boolean,
    onPlaybackEnded: () -> Unit,
    onFailure: (NativeVideoPlaybackFailure) -> Unit,
    modifier: Modifier,
) {
    Box(modifier)
}
