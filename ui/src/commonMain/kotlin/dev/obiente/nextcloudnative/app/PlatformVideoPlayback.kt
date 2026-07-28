package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal expect val platformNativeVideoPlaybackAvailable: Boolean

internal sealed interface NativeVideoPlaybackSource {
    data class DavFile(val file: NextcloudFile) : NativeVideoPlaybackSource

    data class MemoriesLivePhoto(val source: MemoriesLivePhotoSource) : NativeVideoPlaybackSource
}

internal fun NativeVideoPlaybackSource.authenticatedRequestProperties(
    authorization: String,
): Map<String, String> = buildMap {
    put("Authorization", authorization)
    if (this@authenticatedRequestProperties is NativeVideoPlaybackSource.MemoriesLivePhoto) {
        put("OCS-APIRequest", "true")
    }
}

internal fun NativeVideoPlaybackSource.restoresStillAfterPlaybackEnds(): Boolean =
    this is NativeVideoPlaybackSource.MemoriesLivePhoto

@Composable
internal expect fun PlatformNativeVideoPlayer(
    session: NextcloudSession,
    userId: String,
    source: NativeVideoPlaybackSource,
    onPlaybackEnded: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
)
