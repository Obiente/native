package dev.obiente.nextcloudnative

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dev.obiente.nextcloudnative.app.MediaSyncFolderDiscovery
import dev.obiente.nextcloudnative.app.MediaSyncFolderDiscoverySupport
import dev.obiente.nextcloudnative.app.MediaSyncFolderKind
import dev.obiente.nextcloudnative.app.MediaSyncFolderSuggestion
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class DetectedMediaFolderItem(
    val relativePath: String,
    val isImage: Boolean,
)

internal class AndroidMediaSyncFolderDetector(private val context: Context) {
    fun discover(): MediaSyncFolderDiscovery {
        if (!hasMediaPermission()) {
            return MediaSyncFolderDiscovery(
                support = MediaSyncFolderDiscoverySupport.NeedsPermission,
                suggestions = emptyList(),
                message = "Allow photos and videos access to find folders for automatic upload.",
            )
        }
        val items = runCatching { queryMediaFolders() }.getOrElse { failure ->
            return MediaSyncFolderDiscovery(
                support = MediaSyncFolderDiscoverySupport.Unsupported,
                suggestions = emptyList(),
                message = failure.message ?: "Android could not inspect the media library.",
            )
        }
        return MediaSyncFolderDiscovery(
            support = MediaSyncFolderDiscoverySupport.Available,
            suggestions = buildMediaSyncFolderSuggestions(items),
            message = if (items.isEmpty()) "No local photo or video folders were found." else null,
        )
    }

    private fun hasMediaPermission(): Boolean =
        hasMediaLibraryAccess(Build.VERSION.SDK_INT) { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun queryMediaFolders(): List<DetectedMediaFolderItem> {
        val modernStorage = Build.VERSION.SDK_INT >= 29
        val pathColumn = if (modernStorage) MediaStore.Files.FileColumns.RELATIVE_PATH
        else MediaStore.Files.FileColumns.DATA
        val mediaTypeColumn = MediaStore.Files.FileColumns.MEDIA_TYPE
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val selection = "$mediaTypeColumn = ? OR $mediaTypeColumn = ?"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        return buildList {
            context.contentResolver.query(
                collection,
                arrayOf(pathColumn, mediaTypeColumn),
                selection,
                selectionArgs,
                null,
            )?.use { cursor ->
                val pathIndex = cursor.getColumnIndexOrThrow(pathColumn)
                val typeIndex = cursor.getColumnIndexOrThrow(mediaTypeColumn)
                while (cursor.moveToNext()) {
                    val storedPath = cursor.getString(pathIndex) ?: continue
                    val relativePath = if (modernStorage) {
                        storedPath
                    } else {
                        File(storedPath).parent
                            ?.removePrefix(Environment.getExternalStorageDirectory().absolutePath)
                    }.orEmpty().trim('/')
                    if (relativePath.isBlank()) continue
                    add(
                        DetectedMediaFolderItem(
                            relativePath = relativePath,
                            isImage = cursor.getInt(typeIndex) == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
                        ),
                    )
                }
            }
        }
    }
}

internal fun buildMediaSyncFolderSuggestions(
    items: List<DetectedMediaFolderItem>,
): List<MediaSyncFolderSuggestion> =
    items.groupBy { it.relativePath.trim('/') }
        .mapNotNull { (relativePath, folderItems) ->
            if (relativePath.isBlank()) return@mapNotNull null
            val imageCount = folderItems.count(DetectedMediaFolderItem::isImage)
            val videoCount = folderItems.size - imageCount
            val displayName = relativePath.substringAfterLast('/').takeIf { it != "." && it != ".." }
                ?: return@mapNotNull null
            val kind = classifyMediaSyncFolder(relativePath, imageCount, videoCount)
            val remoteCategory = if (kind == MediaSyncFolderKind.Videos) "Videos" else "Photos"
            MediaSyncFolderSuggestion(
                localRootHint = externalStorageTreeHint(relativePath),
                displayName = displayName,
                relativePath = relativePath,
                kind = kind,
                imageCount = imageCount,
                videoCount = videoCount,
                suggestedRemoteRootPath = "$remoteCategory/$displayName",
            )
        }
        .sortedWith(
            compareBy<MediaSyncFolderSuggestion>(
                { it.kind.sortPriority() },
                { -(it.imageCount + it.videoCount) },
                { it.displayName.lowercase() },
            ),
        )
        .take(MAX_MEDIA_FOLDER_SUGGESTIONS)

private fun classifyMediaSyncFolder(
    relativePath: String,
    imageCount: Int,
    videoCount: Int,
): MediaSyncFolderKind {
    val normalized = relativePath.lowercase()
    return when {
        "screenshot" in normalized -> MediaSyncFolderKind.Screenshots
        normalized == "dcim/camera" || normalized.endsWith("/camera") -> MediaSyncFolderKind.Camera
        imageCount > 0 && videoCount == 0 -> MediaSyncFolderKind.Images
        videoCount > 0 && imageCount == 0 -> MediaSyncFolderKind.Videos
        else -> MediaSyncFolderKind.Mixed
    }
}

private fun MediaSyncFolderKind.sortPriority(): Int = when (this) {
    MediaSyncFolderKind.Camera -> 0
    MediaSyncFolderKind.Screenshots -> 1
    MediaSyncFolderKind.Mixed -> 2
    MediaSyncFolderKind.Images -> 3
    MediaSyncFolderKind.Videos -> 4
}

private fun externalStorageTreeHint(relativePath: String): String {
    val documentId = "primary:$relativePath"
    val encoded = URLEncoder.encode(documentId, StandardCharsets.UTF_8.name()).replace("+", "%20")
    return "content://$EXTERNAL_STORAGE_AUTHORITY/tree/$encoded"
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val MAX_MEDIA_FOLDER_SUGGESTIONS = 24
