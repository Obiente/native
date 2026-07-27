package dev.obiente.nextcloudnative.app

import android.util.Base64
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient

internal actual val platformNativeVideoPlaybackAvailable: Boolean = true

@OptIn(UnstableApi::class)
@Composable
internal actual fun PlatformNativeVideoPlayer(
    session: NextcloudSession,
    userId: String,
    source: NativeVideoPlaybackSource,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(session.serverUrl, session.loginName, userId, source) {
        val authorization = Base64.encodeToString(
            "${session.loginName}:${session.appPassword}".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val sourceFactory = OkHttpDataSource.Factory(client)
            .setUserAgent("Nextcloud Native")
            .setDefaultRequestProperties(mapOf("Authorization" to "Basic $authorization"))
        val mediaId = when (source) {
            is NativeVideoPlaybackSource.DavFile ->
                source.file.fileId?.toString() ?: source.file.path
            is NativeVideoPlaybackSource.MemoriesLivePhoto ->
                "live-photo:${source.source.fileId}"
        }
        val uri = when (source) {
            is NativeVideoPlaybackSource.DavFile ->
                buildNextcloudFileUrl(session.serverUrl, userId, source.file.path)
            is NativeVideoPlaybackSource.MemoriesLivePhoto ->
                buildNextcloudApiUrl(session.serverUrl, memoriesLivePhotoVideoRequest(source.source))
        }
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .apply {
                if (source is NativeVideoPlaybackSource.DavFile) {
                    setMimeType(source.file.mimeType)
                }
            }
            .build()
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(sourceFactory).createMediaSource(item))
            prepare()
            playWhenReady = source is NativeVideoPlaybackSource.MemoriesLivePhoto
        }
    }
    val currentOnError = rememberUpdatedState(onError)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                currentOnError.value(error.message ?: "Android could not play this video format.")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                keepScreenOn = true
            }
        },
        update = { view -> view.player = player },
        modifier = modifier,
    )
}
