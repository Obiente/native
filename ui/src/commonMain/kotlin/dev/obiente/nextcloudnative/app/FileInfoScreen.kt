package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

/** Phone and single-pane file details: metadata, versions, tags, and comments. */
@Composable
internal fun FileInfoScreen(
    services: NextcloudPlatformServices, writeFence: FileWriteFence,
    session: NextcloudSession,
    userId: String,
    file: NextcloudFile,
    onBack: () -> Unit,
    showVersions: Boolean,
    onVersionRestored: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(file.name, if (file.isDirectory) "Folder details" else "File details", onBack,
            titleSharedKey = SharedElementKeys.fileName(file.path))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XLarge),
        ) {
            item {
                FileAudioPlaybackOrIcon(session, userId, file,
                    if (file.isDirectory) NextcloudIcons.Folder else workspaceFileIcon(file))
            }
            item {
                Text(file.name, style = MaterialTheme.typography.headlineMedium)
                Text(
                    file.path,
                    modifier = Modifier.padding(top = NextcloudSpacing.Small),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Column(
                        modifier = Modifier.padding(NextcloudSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        FileMetadataLine("Type", if (file.isDirectory) "Folder" else file.mimeType ?: "Unknown")
                        if (!file.isDirectory) FileMetadataLine("Size", formatOptionalByteSize(file.size))
                        file.lastModified?.let { FileMetadataLine("Modified", it) }
                        if (!file.isDirectory) {
                            FileMetadataLine("Image preview", if (file.hasPreview) "Available" else "Unavailable")
                        }
                    }
                }
            }
            if (!file.isDirectory && file.fileId != null) {
                item {
                    FileVersionHistorySection(
                        services = services, writeFence = writeFence,
                        session = session,
                        userId = userId,
                        file = file,
                        initiallyExpanded = showVersions,
                        onVersionRestored = onVersionRestored,
                    )
                }
            }
            file.fileId?.takeIf { userId.isNotBlank() }?.let { fileId ->
                item(key = "tags") { FileSystemTagsSection(services, session, fileId) }
                item(key = "comments") { FileCommentsSection(services, session, userId, fileId) }
            }
            onEdit?.let { edit ->
                item {
                    Button(onClick = edit) {
                        Icon(NextcloudIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Edit text")
                    }
                }
            }
        }
    }
}

@Composable
private fun FileMetadataLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.padding(start = NextcloudSpacing.Large),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

