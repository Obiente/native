package dev.obiente.nextcloudnative

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import dev.obiente.nextcloudnative.app.MAX_MEDIA_PREVIEW_THUMBNAIL_BYTES
import dev.obiente.nextcloudnative.app.MAX_MEDIA_SYNC_FOLDER_PREVIEW_ITEMS
import dev.obiente.nextcloudnative.app.MediaSyncFolderAccess
import dev.obiente.nextcloudnative.app.MediaSyncFolderDiscovery
import dev.obiente.nextcloudnative.app.MediaSyncFolderDiscoverySupport
import dev.obiente.nextcloudnative.app.MediaSyncFolderKind
import dev.obiente.nextcloudnative.app.MediaSyncFolderPreview
import dev.obiente.nextcloudnative.app.MediaSyncFolderPreviewItem
import dev.obiente.nextcloudnative.app.MediaSyncFolderPreviewState
import dev.obiente.nextcloudnative.app.MediaSyncFolderSuggestion
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap

internal data class DetectedMediaFolderItem(
    val relativePath: String,
    val isImage: Boolean,
    val sizeBytes: Long = 0L,
)

internal class AndroidMediaSyncFolderDetector(private val context: Context) {
    private val thumbnailCache = MediaFolderThumbnailCache(MAX_CACHED_MEDIA_FOLDER_THUMBNAILS)

    fun discover(): MediaSyncFolderDiscovery {
        if (!hasMediaPermission()) {
            return MediaSyncFolderDiscovery(
                support = MediaSyncFolderDiscoverySupport.NeedsPermission,
                suggestions = emptyList(),
                message = "Allow photos and videos access to find folders for automatic upload.",
            )
        }
        val folders = runCatching { queryMediaFolderAggregates() }.getOrElse { failure ->
            return MediaSyncFolderDiscovery(
                support = MediaSyncFolderDiscoverySupport.Unsupported,
                suggestions = emptyList(),
                message = failure.message ?: "Android could not inspect the media library.",
            )
        }
        return MediaSyncFolderDiscovery(
            support = MediaSyncFolderDiscoverySupport.Available,
            suggestions = buildMediaSyncFolderSuggestions(folders),
            message = if (folders.isEmpty()) "No local photo or video folders were found." else null,
            access = mediaLibraryAccess(),
        )
    }

    fun preview(suggestion: MediaSyncFolderSuggestion): MediaSyncFolderPreview {
        if (!hasMediaPermission()) {
            return unavailablePreview(
                suggestion,
                MediaSyncFolderPreviewState.Inaccessible,
                "Photo and video access is required to preview this folder.",
            )
        }
        if (mediaLibraryAccess() != MediaSyncFolderAccess.FullLibrary) {
            return unavailablePreview(
                suggestion,
                MediaSyncFolderPreviewState.Inaccessible,
                "Full photo and video library access is required before automatic folder upload can be enabled.",
            )
        }
        val unresolvedRoot = File(Environment.getExternalStorageDirectory(), suggestion.relativePath)
        if (!unresolvedRoot.isDirectory) {
            return unavailablePreview(
                suggestion,
                MediaSyncFolderPreviewState.Removed,
                "This media folder was removed from the device.",
            )
        }
        val root = runCatching {
            resolveMediaStoreSyncRoot(
                suggestion.localRootHint,
                Environment.getExternalStorageDirectory(),
            )
        }.getOrElse {
            return unavailablePreview(
                suggestion,
                MediaSyncFolderPreviewState.Inaccessible,
                "Android could not read this media folder.",
            )
        }
        val inspection = runCatching {
            inspectMediaFolderSyncScope(root, MAX_MEDIA_SYNC_FOLDER_PREVIEW_ITEMS)
        }.getOrElse {
            return unavailablePreview(
                suggestion,
                MediaSyncFolderPreviewState.Inaccessible,
                "Android could not inspect the files this sync would upload.",
            )
        }
        if (inspection.exceedsSyncLimit) {
            return unavailablePreview(
                suggestion,
                MediaSyncFolderPreviewState.Inaccessible,
                "This folder has too many uploadable media files to sync safely.",
            )
        }
        if (inspection.totalItems == 0) {
            return unavailablePreview(
                suggestion,
                MediaSyncFolderPreviewState.Empty,
                "No direct, visible photo, video, or RAW files are available to upload. " +
                    "Subfolders, hidden files, and other file types are excluded.",
            )
        }
        val changed =
            inspection.imageCount != suggestion.imageCount ||
                inspection.videoCount != suggestion.videoCount ||
                inspection.totalBytes != suggestion.totalBytes
        val items = runCatching {
            inspection.previewFiles.asSequence()
                .map { file -> file.toMediaSyncFolderPreviewItem(suggestion.relativePath) }
                .toList()
        }.getOrElse {
            return unavailablePreview(
                suggestion,
                MediaSyncFolderPreviewState.Inaccessible,
                "Android could not load a preview for this media folder.",
            )
        }
        return MediaSyncFolderPreview(
            localRootHint = suggestion.localRootHint,
            state = if (changed) MediaSyncFolderPreviewState.Changed else MediaSyncFolderPreviewState.Available,
            access = MediaSyncFolderAccess.FullLibrary,
            totalItems = inspection.totalItems,
            totalBytes = inspection.totalBytes,
            items = items,
            message = if (changed) {
                "The upload scope changed since detection. These are the exact current totals. " +
                    "Only direct, visible photo, video, and RAW files are included."
            } else {
                "Only direct, visible photo, video, and RAW files are included. " +
                    "Subfolders, hidden files, sidecars, and other file types are excluded."
            },
        )
    }

    private fun hasMediaPermission(): Boolean =
        hasMediaLibraryAccess(Build.VERSION.SDK_INT) { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun mediaLibraryAccess(): MediaSyncFolderAccess =
        if (
            hasFullMediaLibraryAccess(Build.VERSION.SDK_INT) { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            MediaSyncFolderAccess.FullLibrary
        } else {
            MediaSyncFolderAccess.LimitedSelection
        }

    private fun queryMediaFolderAggregates(): List<DetectedMediaFolderAggregate> {
        val modernStorage = Build.VERSION.SDK_INT >= 29
        val pathColumn = if (modernStorage) MediaStore.Files.FileColumns.RELATIVE_PATH
        else MediaStore.Files.FileColumns.DATA
        val mediaTypeColumn = MediaStore.Files.FileColumns.MEDIA_TYPE
        val sizeColumn = MediaStore.Files.FileColumns.SIZE
        val collection = externalMediaCollection()
        val selection = "$mediaTypeColumn = ? OR $mediaTypeColumn = ?"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val folders = linkedMapOf<String, MutableDetectedMediaFolderAggregate>()
        context.contentResolver.query(
            collection,
            arrayOf(pathColumn, mediaTypeColumn, sizeColumn),
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            val pathIndex = cursor.getColumnIndexOrThrow(pathColumn)
            val typeIndex = cursor.getColumnIndexOrThrow(mediaTypeColumn)
            val sizeIndex = cursor.getColumnIndex(sizeColumn)
            while (cursor.moveToNext()) {
                val storedPath = cursor.getString(pathIndex) ?: continue
                val relativePath = if (modernStorage) {
                    storedPath
                } else {
                    File(storedPath).parent
                        ?.removePrefix(Environment.getExternalStorageDirectory().absolutePath)
                }.orEmpty().trim('/')
                if (relativePath.isBlank()) continue
                val aggregate = folders.getOrPut(relativePath) {
                    MutableDetectedMediaFolderAggregate(relativePath)
                }
                aggregate.add(
                    isImage =
                        cursor.getInt(typeIndex) == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
                    sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex).coerceAtLeast(0L)
                    } else {
                        0L
                    },
                )
            }
        }
        return folders.values.map(MutableDetectedMediaFolderAggregate::toImmutable)
    }

    private fun File.toMediaSyncFolderPreviewItem(relativePath: String): MediaSyncFolderPreviewItem {
        val identity = findMediaStoreIdentity(this, relativePath)
        val cacheKey = identity?.let { "${it.id}:${lastModified()}:${length()}:${it.mediaType}" }
        val thumbnail = if (identity == null || cacheKey == null) {
            null
        } else {
            thumbnailCache.get(cacheKey) ?: loadThumbnailBytes(
                identity.uri,
                identity.id,
                identity.mediaType,
            )?.also { thumbnailCache.put(cacheKey, it) }
        }
        val extension = extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: if (isMediaFolderSyncVideo(this)) "video/$extension" else "image/x-$extension"
        return MediaSyncFolderPreviewItem(
            stableId = stableMediaFileId(name, length(), lastModified()),
            displayName = safeMediaDisplayName(name),
            mimeType = mimeType,
            sizeBytes = length().coerceAtLeast(0L),
            modifiedAtEpochMillis = lastModified().coerceAtLeast(0L),
            thumbnailBytes = thumbnail,
        )
    }

    private fun findMediaStoreIdentity(
        file: File,
        relativePath: String,
    ): MediaStorePreviewIdentity? {
        val collection = externalMediaCollection()
        val modernStorage = Build.VERSION.SDK_INT >= 29
        val mediaTypeColumn = MediaStore.Files.FileColumns.MEDIA_TYPE
        val lookup = mediaStorePreviewSelection(modernStorage, relativePath, file)
        return context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID, mediaTypeColumn),
            lookup.selection,
            lookup.arguments.toTypedArray(),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeIndex = cursor.getColumnIndexOrThrow(mediaTypeColumn)
                val id = cursor.getLong(idIndex)
                MediaStorePreviewIdentity(
                    id = id,
                    mediaType = cursor.getInt(typeIndex),
                    uri = ContentUris.withAppendedId(collection, id),
                )
            } else {
                null
            }
        }
    }

    private fun loadThumbnailBytes(uri: Uri, id: Long, mediaType: Int): ByteArray? =
        runCatching {
            val bitmap = if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_EDGE_PX, THUMBNAIL_EDGE_PX), null)
            } else if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null,
                )
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver,
                    id,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null,
                )
            } ?: return@runCatching null
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, output)
                output.toByteArray().takeIf { it.size <= MAX_MEDIA_PREVIEW_THUMBNAIL_BYTES }
            }
        }.getOrNull()

    private fun unavailablePreview(
        suggestion: MediaSyncFolderSuggestion,
        state: MediaSyncFolderPreviewState,
        message: String,
    ) = MediaSyncFolderPreview(
        localRootHint = suggestion.localRootHint,
        state = state,
        access = mediaLibraryAccess(),
        totalItems = 0,
        totalBytes = 0L,
        items = emptyList(),
        message = message,
    )

    private fun externalMediaCollection(): Uri = MediaStore.Files.getContentUri(
        if (Build.VERSION.SDK_INT >= 29) MediaStore.VOLUME_EXTERNAL else "external",
    )

}

private data class MediaStorePreviewIdentity(
    val id: Long,
    val mediaType: Int,
    val uri: Uri,
)

internal data class MediaStorePreviewSelection(
    val selection: String,
    val arguments: List<String>,
)

internal fun mediaStorePreviewSelection(
    modernStorage: Boolean,
    relativePath: String,
    file: File,
): MediaStorePreviewSelection {
    val normalized = normalizeMediaStoreRelativePath(relativePath)
    return if (modernStorage) {
        MediaStorePreviewSelection(
            selection =
                "(${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? OR " +
                    "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?) AND " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?",
            arguments = listOf(normalized, "$normalized/", file.name),
        )
    } else {
        MediaStorePreviewSelection(
            selection = "${MediaStore.Files.FileColumns.DATA} = ?",
            arguments = listOf(file.absolutePath),
        )
    }
}

internal data class DetectedMediaFolderAggregate(
    val relativePath: String,
    val imageCount: Int,
    val videoCount: Int,
    val totalBytes: Long,
)

private class MutableDetectedMediaFolderAggregate(val relativePath: String) {
    private var imageCount = 0
    private var videoCount = 0
    private var totalBytes = 0L

    fun add(isImage: Boolean, sizeBytes: Long) {
        if (isImage) {
            if (imageCount < Int.MAX_VALUE) imageCount++
        } else {
            if (videoCount < Int.MAX_VALUE) videoCount++
        }
        totalBytes = if (Long.MAX_VALUE - totalBytes < sizeBytes) Long.MAX_VALUE else totalBytes + sizeBytes
    }

    fun toImmutable() = DetectedMediaFolderAggregate(relativePath, imageCount, videoCount, totalBytes)
}

internal fun buildMediaSyncFolderSuggestions(
    items: List<DetectedMediaFolderItem>,
): List<MediaSyncFolderSuggestion> {
    val aggregates = linkedMapOf<String, MutableDetectedMediaFolderAggregate>()
    items.forEach { item ->
        val relativePath = item.relativePath.trim('/')
        if (relativePath.isNotBlank()) {
            aggregates.getOrPut(relativePath) {
                MutableDetectedMediaFolderAggregate(relativePath)
            }.add(item.isImage, item.sizeBytes.coerceAtLeast(0L))
        }
    }
    return buildMediaSyncFolderSuggestions(aggregates.values.map(MutableDetectedMediaFolderAggregate::toImmutable))
}

internal fun buildMediaSyncFolderSuggestions(
    folders: Collection<DetectedMediaFolderAggregate>,
): List<MediaSyncFolderSuggestion> =
    folders.mapNotNull { folder ->
        val relativePath = folder.relativePath
        if (relativePath.isBlank()) return@mapNotNull null
        val imageCount = folder.imageCount
        val videoCount = folder.videoCount
        val displayName = relativePath.substringAfterLast('/').takeIf { it != "." && it != ".." }
            ?: return@mapNotNull null
        val kind = classifyMediaSyncFolder(relativePath, imageCount, videoCount)
        val remoteCategory = if (kind == MediaSyncFolderKind.Videos) "Videos" else "Photos"
        MediaSyncFolderSuggestion(
            localRootHint = mediaStoreSyncRootId(relativePath),
            displayName = displayName,
            relativePath = relativePath,
            kind = kind,
            imageCount = imageCount,
            videoCount = videoCount,
            suggestedRemoteRootPath = "$remoteCategory/$displayName",
            totalBytes = folder.totalBytes,
        )
    }
        .sortedWith(
            compareBy<MediaSyncFolderSuggestion>(
                { it.kind.sortPriority() },
                { -(it.imageCount.toLong() + it.videoCount.toLong()) },
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

internal fun mediaStoreSyncRootId(relativePath: String): String =
    MEDIA_STORE_SYNC_ROOT_PREFIX + normalizeMediaStoreRelativePath(relativePath)

internal fun normalizeMediaStoreRelativePath(relativePath: String): String {
    val segments = relativePath.trim('/').split('/')
    require(segments.isNotEmpty())
    require(segments.all { segment ->
        segment.isNotBlank() &&
            segment !in setOf(".", "..") &&
            '\\' !in segment &&
            segment.none(Char::isISOControl)
    }) { "The detected media folder path is invalid." }
    return segments.joinToString("/")
}

internal const val MEDIA_STORE_SYNC_ROOT_PREFIX = "media-store://primary/"
private const val MAX_MEDIA_FOLDER_SUGGESTIONS = 24
private const val MAX_CACHED_MEDIA_FOLDER_THUMBNAILS = 48
private const val THUMBNAIL_EDGE_PX = 240
private const val THUMBNAIL_JPEG_QUALITY = 82

internal class MediaFolderThumbnailCache(private val maximumEntries: Int) {
    init {
        require(maximumEntries > 0)
    }

    private val entries = object : LinkedHashMap<String, ByteArray>(maximumEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > maximumEntries
    }

    @Synchronized
    fun get(key: String): ByteArray? = entries[key]

    @Synchronized
    fun put(key: String, bytes: ByteArray) {
        require(bytes.size <= MAX_MEDIA_PREVIEW_THUMBNAIL_BYTES)
        entries[key] = bytes
    }

    @Synchronized
    internal fun keys(): Set<String> = entries.keys.toSet()
}

internal fun hasFullMediaLibraryAccess(
    sdk: Int,
    permissionGranted: (String) -> Boolean,
): Boolean = when {
    sdk >= 33 ->
        permissionGranted(Manifest.permission.READ_MEDIA_IMAGES) &&
            permissionGranted(Manifest.permission.READ_MEDIA_VIDEO)
    else -> permissionGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun safeMediaDisplayName(value: String?): String =
    value
        ?.filterNot(Char::isISOControl)
        ?.take(512)
        ?.takeIf(String::isNotBlank)
        ?: "Media item"

internal fun stableMediaFileId(name: String, size: Long, modifiedAt: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$name\u0000$size\u0000$modifiedAt".encodeToByteArray())
    return "media-file-" + digest.take(16).joinToString("") { "%02x".format(it) }
}
