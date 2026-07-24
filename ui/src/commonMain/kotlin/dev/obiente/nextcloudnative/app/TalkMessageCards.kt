package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

/**
 * Native, read-only Talk message renderer.
 *
 * It performs only authenticated core-preview reads. Original attachment access is delegated to
 * the caller and never offered when Talk's rich-object parameter says `hide-download=yes`.
 */
@Composable
fun TalkMessageCard(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    message: TalkMessage,
    mine: Boolean,
    onOpenAttachment: (TalkAttachmentRenderModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val model = remember(message) { message.toRenderModel() }) {
        is TalkMessageRenderModel.Text -> TalkNativeTextBubble(
            actorDisplayName = message.actorDisplayName,
            model = model,
            mine = mine,
            modifier = modifier,
        )
        is TalkMessageRenderModel.Attachments -> TalkAttachmentsBubble(
            services = services,
            session = session,
            actorDisplayName = message.actorDisplayName,
            model = model,
            mine = mine,
            onOpenAttachment = onOpenAttachment,
            modifier = modifier,
        )
        is TalkMessageRenderModel.Event -> TalkNativeEventCard(model, modifier)
        is TalkMessageRenderModel.SharedObject -> TalkNativeEventCard(
            model = TalkMessageRenderModel.Event(
                kind = TalkMessageRenderKind.SystemEvent,
                title = model.title,
                summary = model.summary,
                tone = TalkEventTone.Neutral,
            ),
            modifier = modifier,
            icon = NextcloudIcons.FormatLink,
        )
        is TalkMessageRenderModel.Deleted -> TalkNativeEventCard(
            model = TalkMessageRenderModel.Event(
                kind = TalkMessageRenderKind.SystemEvent,
                title = "Message deleted",
                summary = model.summary,
                tone = TalkEventTone.Neutral,
            ),
            modifier = modifier,
            icon = NextcloudIcons.Info,
        )
    }
}

@Composable
private fun TalkNativeTextBubble(
    actorDisplayName: String,
    model: TalkMessageRenderModel.Text,
    mine: Boolean,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.84f),
            color = talkBubbleColor(mine),
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column(modifier = Modifier.padding(NextcloudSpacing.Medium)) {
                Text(actorDisplayName, style = MaterialTheme.typography.labelMedium)
                if (model.markdown) {
                    Markdown(content = model.summary)
                } else {
                    Text(model.summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TalkAttachmentsBubble(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    actorDisplayName: String,
    model: TalkMessageRenderModel.Attachments,
    mine: Boolean,
    onOpenAttachment: (TalkAttachmentRenderModel) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f),
            color = talkBubbleColor(mine),
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column(
                modifier = Modifier.padding(NextcloudSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Text(
                    actorDisplayName,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.XSmall, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                model.items.forEach { attachment ->
                    TalkNativeAttachmentCard(
                        services = services,
                        session = session,
                        model = attachment,
                        onOpen = { onOpenAttachment(attachment) },
                    )
                }
                if (model.items.size > 1) {
                    Text(
                        "${model.items.size} attachments",
                        modifier = Modifier.padding(horizontal = NextcloudSpacing.XSmall, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TalkNativeAttachmentCard(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    model: TalkAttachmentRenderModel,
    onOpen: () -> Unit,
) {
    val file = remember(model) { model.asNextcloudFile() }
    var preview by remember(file.fileId, file.etag, model.canLoadServerRaster) {
        mutableStateOf<TalkAttachmentPreviewState>(
            if (model.canLoadServerRaster) TalkAttachmentPreviewState.Loading else TalkAttachmentPreviewState.None,
        )
    }
    LaunchedEffect(file.fileId, file.etag, model.canLoadServerRaster) {
        if (!model.canLoadServerRaster) return@LaunchedEffect
        preview = runCatching {
            val bytes = services.loadPreviewCached(session, file, width = 720, height = 480)
            decodePlatformImage(bytes) ?: error("Invalid Talk attachment preview")
        }.fold(
            onSuccess = TalkAttachmentPreviewState::Ready,
            onFailure = { TalkAttachmentPreviewState.Failed },
        )
    }

    val openModifier = if (model.canOpen) Modifier.clickable(onClick = onOpen) else Modifier
    Surface(
        modifier = Modifier.fillMaxWidth().then(openModifier),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        if (model.visual.hasVisualPreviewArea()) {
            Column {
                TalkAttachmentPreview(model, preview)
                TalkAttachmentMetadata(model)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                TalkAttachmentIcon(model, Modifier.size(42.dp))
                TalkAttachmentMetadata(model, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TalkAttachmentPreview(
    model: TalkAttachmentRenderModel,
    state: TalkAttachmentPreviewState,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (model.visual == TalkAttachmentVisual.File) 136.dp else 188.dp)
            .background(NextcloudTheme.colors.appIconContainer),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            TalkAttachmentPreviewState.Loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
            is TalkAttachmentPreviewState.Ready -> {
                Image(
                    bitmap = state.image,
                    contentDescription = model.attachment.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (model.visual == TalkAttachmentVisual.File) ContentScale.Fit else ContentScale.Crop,
                )
                if (model.visual == TalkAttachmentVisual.Video || model.visual == TalkAttachmentVisual.VideoRecording) {
                    Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f), shape = CircleShape) {
                        Icon(
                            NextcloudIcons.Video,
                            contentDescription = "Open video",
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(12.dp).size(28.dp),
                        )
                    }
                }
            }
            TalkAttachmentPreviewState.None,
            TalkAttachmentPreviewState.Failed,
            -> TalkAttachmentIcon(model, Modifier.size(44.dp))
        }
    }
}

@Composable
private fun TalkAttachmentMetadata(
    model: TalkAttachmentRenderModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            model.attachment.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append(model.visual.displayLabel())
                model.attachment.size?.let { append(" · ").append(formatTalkBytes(it)) }
                if (model.attachment.hideDownload) append(" · Preview only")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TalkAttachmentIcon(model: TalkAttachmentRenderModel, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = CircleShape) {
        Icon(
            imageVector = model.visual.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.padding(8.dp),
        )
    }
}

@Composable
private fun TalkNativeEventCard(
    model: TalkMessageRenderModel.Event,
    modifier: Modifier,
    icon: ImageVector = if (model.kind == TalkMessageRenderKind.CallEvent) NextcloudIcons.Video else NextcloudIcons.Info,
) {
    val accent = when (model.tone) {
        TalkEventTone.Neutral -> MaterialTheme.colorScheme.primary
        TalkEventTone.Positive -> MaterialTheme.colorScheme.tertiary
        TalkEventTone.Warning -> MaterialTheme.colorScheme.secondary
        TalkEventTone.Error -> MaterialTheme.colorScheme.error
    }
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Row(
                modifier = Modifier.padding(NextcloudSpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = accent.copy(alpha = 0.12f), shape = CircleShape) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        model.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun talkBubbleColor(mine: Boolean) =
    if (mine) MaterialTheme.colorScheme.primaryContainer else NextcloudTheme.colors.appTile

private fun TalkAttachmentVisual.hasVisualPreviewArea(): Boolean = when (this) {
    TalkAttachmentVisual.Image,
    TalkAttachmentVisual.Video,
    TalkAttachmentVisual.VideoRecording,
    TalkAttachmentVisual.File,
    -> true
    TalkAttachmentVisual.Audio,
    TalkAttachmentVisual.Voice,
    TalkAttachmentVisual.AudioRecording,
    -> false
}

private fun TalkAttachmentVisual.icon(): ImageVector = when (this) {
    TalkAttachmentVisual.Image -> NextcloudIcons.Image
    TalkAttachmentVisual.Video,
    TalkAttachmentVisual.VideoRecording,
    -> NextcloudIcons.Video
    TalkAttachmentVisual.Audio,
    TalkAttachmentVisual.Voice,
    TalkAttachmentVisual.AudioRecording,
    -> NextcloudIcons.app("music")
    TalkAttachmentVisual.File -> NextcloudIcons.File
}

private fun TalkAttachmentVisual.displayLabel(): String = when (this) {
    TalkAttachmentVisual.Image -> "Image"
    TalkAttachmentVisual.Video -> "Video"
    TalkAttachmentVisual.Audio -> "Audio"
    TalkAttachmentVisual.Voice -> "Voice message"
    TalkAttachmentVisual.AudioRecording -> "Audio recording"
    TalkAttachmentVisual.VideoRecording -> "Video recording"
    TalkAttachmentVisual.File -> "File"
}

private fun formatTalkBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> "${bytes / 1_024L} KiB"
    bytes < 1_024L * 1_024L * 1_024L -> "${bytes / (1_024L * 1_024L)} MiB"
    else -> "${bytes / (1_024L * 1_024L * 1_024L)} GiB"
}

private sealed interface TalkAttachmentPreviewState {
    data object None : TalkAttachmentPreviewState
    data object Loading : TalkAttachmentPreviewState
    data class Ready(val image: ImageBitmap) : TalkAttachmentPreviewState
    data object Failed : TalkAttachmentPreviewState
}
