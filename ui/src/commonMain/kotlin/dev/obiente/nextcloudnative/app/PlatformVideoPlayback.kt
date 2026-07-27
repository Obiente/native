package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal expect val platformNativeVideoPlaybackAvailable: Boolean

internal sealed interface NativeVideoPlaybackSource {
    data class DavFile(val file: NextcloudFile) : NativeVideoPlaybackSource

    data class MemoriesLivePhoto(val source: MemoriesLivePhotoSource) : NativeVideoPlaybackSource
}

@Composable
internal expect fun PlatformNativeVideoPlayer(
    session: NextcloudSession,
    userId: String,
    source: NativeVideoPlaybackSource,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
)
