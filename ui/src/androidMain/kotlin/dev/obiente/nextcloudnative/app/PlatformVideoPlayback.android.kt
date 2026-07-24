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
    file: NextcloudFile,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(session.serverUrl, session.loginName, userId, file.path, file.etag) {
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
        val item = MediaItem.Builder()
            .setMediaId(file.fileId?.toString() ?: file.path)
            .setUri(buildNextcloudFileUrl(session.serverUrl, userId, file.path))
            .setMimeType(file.mimeType)
            .build()
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(sourceFactory).createMediaSource(item))
            prepare()
            playWhenReady = false
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
