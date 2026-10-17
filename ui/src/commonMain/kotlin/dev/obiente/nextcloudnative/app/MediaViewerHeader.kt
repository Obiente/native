package dev.obiente.nextcloudnative.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

@Composable
internal fun ViewerHeader(
    filename: String,
    counter: String,
    sourceChoices: List<MediaSourceChoice>,
    selectedSourcePath: String,
    onSelectSource: (MediaSourceChoice) -> Unit,
    onEdit: (() -> Unit)?,
    onTags: (() -> Unit)?,
    actions: List<MediaViewerAction>,
    onAction: (MediaViewerAction) -> Unit,
    onClose: () -> Unit,
    layout: MediaViewerLayout,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember(filename) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.62f))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.primaryRowHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = onClose,
                colors = viewerIconButtonColors(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close preview",
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = filename,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = counter,
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            onEdit?.let {
                IconButton(onClick = it, colors = viewerIconButtonColors()) {
                    Icon(NextcloudIcons.Edit, contentDescription = "Edit $filename")
                }
            }
            onTags?.let {
                IconButton(onClick = it, colors = viewerIconButtonColors()) {
                    Icon(NextcloudIcons.Tag, contentDescription = "Edit tags for $filename")
                }
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    colors = viewerIconButtonColors(),
                ) {
                    Icon(NextcloudIcons.More, contentDescription = "More actions for $filename")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            onClick = {
                                menuExpanded = false
                                onAction(action)
                            },
                        )
                    }
                }
            }
        }
        if (layout.sourceChoiceLayout == MediaViewerSourceChoiceLayout.SeparateScrollableRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(checkNotNull(layout.sourceChoiceRowHeight))
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                sourceChoices.forEach { choice ->
                    TextButton(
                        onClick = { onSelectSource(choice) },
                        enabled = choice.file.path != selectedSourcePath,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(
                            text = choice.pickerLabel,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

internal enum class MediaViewerContentLayout {
    FullCanvasBehindChrome,
}

internal enum class MediaViewerSourceChoiceLayout {
    Hidden,
    SeparateScrollableRow,
}

internal data class MediaViewerLayout(
    val contentLayout: MediaViewerContentLayout,
    val sourceChoiceLayout: MediaViewerSourceChoiceLayout,
    val primaryRowHeight: Dp,
    val sourceChoiceRowHeight: Dp?,
) {
    val chromeContentHeight: Dp
        get() = primaryRowHeight + (sourceChoiceRowHeight ?: 0.dp)
}

internal fun resolveMediaViewerLayout(sourceChoiceCount: Int): MediaViewerLayout {
    require(sourceChoiceCount >= 0) { "Source choice count cannot be negative." }
    val showsSourceChoices = sourceChoiceCount > 1
    return MediaViewerLayout(
        contentLayout = MediaViewerContentLayout.FullCanvasBehindChrome,
        sourceChoiceLayout = if (showsSourceChoices) {
            MediaViewerSourceChoiceLayout.SeparateScrollableRow
        } else {
            MediaViewerSourceChoiceLayout.Hidden
        },
        primaryRowHeight = 56.dp,
        sourceChoiceRowHeight = if (showsSourceChoices) 48.dp else null,
    )
}

/** Viewer controls that fade out together while media is shown immersively. */
@Composable
internal fun MediaViewerChrome(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        content()
    }
}
