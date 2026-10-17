package dev.obiente.nextcloudnative.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal enum class NativeVideoTransportStatus { Loading, Playing, Paused, Ended }

/** What the transport controls show; the player that owns playback produces it. */
@Immutable
internal data class NativeVideoTransportState(
    val status: NativeVideoTransportStatus = NativeVideoTransportStatus.Loading,
    val positionMillis: Long = 0L,
    val durationMillis: Long? = null,
    val seekable: Boolean = false,
    val muted: Boolean = false,
    val volumePercent: Int = 100,
) {
    init {
        require(positionMillis >= 0L) { "The playback position cannot be negative." }
        require(durationMillis == null || durationMillis >= 0L) { "The video duration cannot be negative." }
        require(volumePercent in 0..MAX_VIDEO_VOLUME_PERCENT) { "The volume is out of range." }
    }

    val canSeek: Boolean
        get() = seekable && (durationMillis ?: 0L) > 0L
}

internal sealed interface NativeVideoTransportCommand {
    data object TogglePlayback : NativeVideoTransportCommand

    /** Pauses only; sent by the host when the player stops being visible. */
    data object Pause : NativeVideoTransportCommand
    data class SeekTo(val positionMillis: Long) : NativeVideoTransportCommand
    data class SeekBy(val deltaMillis: Long) : NativeVideoTransportCommand
    data object ToggleMute : NativeVideoTransportCommand
    data class SetVolume(val percent: Int) : NativeVideoTransportCommand
}

/**
 * Keyboard shortcuts while the player has focus. Arrow keys stay with the viewer so they keep
 * moving between media items; J and L seek like common desktop players.
 */
internal fun nativeVideoTransportCommandForKey(key: Key): NativeVideoTransportCommand? = when (key) {
    Key.Spacebar, Key.K -> NativeVideoTransportCommand.TogglePlayback
    Key.J -> NativeVideoTransportCommand.SeekBy(-VIDEO_KEYBOARD_SEEK_MILLIS)
    Key.L -> NativeVideoTransportCommand.SeekBy(VIDEO_KEYBOARD_SEEK_MILLIS)
    Key.M -> NativeVideoTransportCommand.ToggleMute
    else -> null
}

/** The absolute position a seek command lands on, or null when this media cannot seek. */
internal fun NativeVideoTransportState.seekTargetMillis(command: NativeVideoTransportCommand): Long? {
    if (!canSeek) return null
    val duration = requireNotNull(durationMillis)
    val target = when (command) {
        is NativeVideoTransportCommand.SeekTo -> command.positionMillis
        is NativeVideoTransportCommand.SeekBy -> positionMillis.saturatingPlus(command.deltaMillis)
        else -> return null
    }
    return target.coerceIn(0L, duration)
}

internal fun formatVideoTimestamp(millis: Long): String =
    formatMediaDuration((millis.coerceAtLeast(0L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

/** Center play affordance and bottom transport bar, shown together while controls are visible. */
@Composable
internal fun BoxScope.NativeVideoTransportOverlay(
    state: NativeVideoTransportState,
    visible: Boolean,
    onCommand: (NativeVideoTransportCommand) -> Unit,
) {
    if (state.status == NativeVideoTransportStatus.Loading) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
    }
    AnimatedVisibility(
        visible = visible && state.status != NativeVideoTransportStatus.Loading &&
            state.status != NativeVideoTransportStatus.Playing,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center),
    ) {
        IconButton(
            onClick = { onCommand(NativeVideoTransportCommand.TogglePlayback) },
            colors = viewerIconButtonColors(),
            modifier = Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.58f), CircleShape),
        ) {
            Icon(
                imageVector = state.playbackIcon(),
                contentDescription = state.playbackLabel(),
                modifier = Modifier.size(44.dp),
            )
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        NativeVideoTransportBar(state = state, onCommand = onCommand)
    }
}

@Composable
internal fun NativeVideoTransportBar(
    state: NativeVideoTransportState,
    onCommand: (NativeVideoTransportCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draggingMillis by remember { mutableStateOf<Long?>(null) }
    val duration = state.durationMillis ?: 0L
    val shownPosition = draggingMillis ?: state.positionMillis
    val timeLabel = buildString {
        append(formatVideoTimestamp(shownPosition))
        state.durationMillis?.let { append(" / ").append(formatVideoTimestamp(it)) }
    }
    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
        color = Color(0xE625232B),
        contentColor = Color.White,
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = { onCommand(NativeVideoTransportCommand.TogglePlayback) },
                enabled = state.status != NativeVideoTransportStatus.Loading,
                colors = viewerIconButtonColors(),
            ) { Icon(state.playbackIcon(), contentDescription = state.playbackLabel()) }
            Text(timeLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Box(modifier = Modifier.weight(1f)) {
                Slider(
                    value = if (duration > 0L) (shownPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { fraction -> draggingMillis = (fraction * duration).toLong() },
                    onValueChangeFinished = {
                        draggingMillis?.let { onCommand(NativeVideoTransportCommand.SeekTo(it)) }
                        draggingMillis = null
                    },
                    enabled = state.canSeek,
                    colors = videoSliderColors(),
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Playback position"
                        stateDescription = timeLabel.replace(" / ", " of ")
                    },
                )
            }
            IconButton(
                onClick = { onCommand(NativeVideoTransportCommand.ToggleMute) },
                colors = viewerIconButtonColors(),
            ) {
                Icon(
                    imageVector = if (state.muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    contentDescription = if (state.muted) "Unmute" else "Mute",
                )
            }
            Slider(
                value = if (state.muted) 0f else state.volumePercent / MAX_VIDEO_VOLUME_PERCENT.toFloat(),
                onValueChange = { fraction ->
                    onCommand(NativeVideoTransportCommand.SetVolume((fraction * MAX_VIDEO_VOLUME_PERCENT).toInt()))
                },
                colors = videoSliderColors(),
                modifier = Modifier.width(96.dp).widthIn(min = 64.dp).semantics {
                    contentDescription = "Volume"
                    stateDescription = if (state.muted) "Muted" else "${state.volumePercent} percent"
                },
            )
        }
    }
}

private fun NativeVideoTransportState.playbackIcon() = when (status) {
    NativeVideoTransportStatus.Playing -> Icons.Outlined.Pause
    NativeVideoTransportStatus.Ended -> Icons.Outlined.Replay
    NativeVideoTransportStatus.Loading, NativeVideoTransportStatus.Paused -> Icons.Outlined.PlayArrow
}

private fun NativeVideoTransportState.playbackLabel() = when (status) {
    NativeVideoTransportStatus.Playing -> "Pause"
    NativeVideoTransportStatus.Ended -> "Play again"
    NativeVideoTransportStatus.Loading, NativeVideoTransportStatus.Paused -> "Play"
}

@Composable
private fun videoSliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Color.White,
    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
)

private fun Long.saturatingPlus(delta: Long): Long {
    val sum = this + delta
    return if (((this xor sum) and (delta xor sum)) < 0L) {
        if (delta > 0L) Long.MAX_VALUE else Long.MIN_VALUE
    } else {
        sum
    }
}

internal const val MAX_VIDEO_VOLUME_PERCENT = 100
private const val VIDEO_KEYBOARD_SEEK_MILLIS = 10_000L
