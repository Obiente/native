package dev.obiente.nextcloudnative

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
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
        val current = runCatching {
            queryMediaFolderAggregates().firstOrNull {
                it.relativePath == suggestion.relativePath.trim('/')
            }
        }.getOrElse {
            return unavailablePreview(
                suggestion,
                MediaSyncFolderPreviewState.Inaccessible,
                "Android could not read this media folder.",
            )
        }
        if (current == null) {
            if (mediaFolderAccess(suggestion) == MediaSyncFolderAccess.LimitedSelection) {
                return unavailablePreview(
                    suggestion,
                    MediaSyncFolderPreviewState.Empty,
                    "No items from this folder are included in Android's current media permission.",
                )
            }
            val folderExists = runCatching {
                File(Environment.getExternalStorageDirectory(), suggestion.relativePath).isDirectory
            }.getOrDefault(false)
            return unavailablePreview(
                suggestion,
                if (folderExists) MediaSyncFolderPreviewState.Empty else MediaSyncFolderPreviewState.Removed,
                if (folderExists) {
                    "This media folder is currently empty."
                } else {
                    "This media folder was removed from the device."
                },
            )
        }
        val changed =
            current.imageCount != suggestion.imageCount ||
                current.videoCount != suggestion.videoCount ||
                current.totalBytes != suggestion.totalBytes
        val items = runCatching { queryPreviewItems(suggestion.relativePath) }.getOrElse {
            return unavailablePreview(
                suggestion,
                MediaSyncFolderPreviewState.Inaccessible,
                "Android could not load a preview for this media folder.",
            )
        }
        return MediaSyncFolderPreview(
            localRootHint = suggestion.localRootHint,
            state = if (changed) MediaSyncFolderPreviewState.Changed else MediaSyncFolderPreviewState.Available,
            access = mediaFolderAccess(suggestion),
            totalItems = saturatingMediaItemCount(current.imageCount, current.videoCount),
            totalBytes = current.totalBytes,
            items = items,
            message = when {
                mediaFolderAccess(suggestion) == MediaSyncFolderAccess.LimitedSelection ->
                    "Only photos and videos included in Android's current media permission are represented."
                changed -> "The folder changed since it was detected. These are the latest totals."
                else -> null
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

    private fun mediaFolderAccess(suggestion: MediaSyncFolderSuggestion): MediaSyncFolderAccess =
        if (
            hasFullMediaFolderAccess(
                sdk = Build.VERSION.SDK_INT,
                includesImages = suggestion.imageCount > 0,
                includesVideos = suggestion.videoCount > 0,
            ) { permission ->
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

    private fun queryPreviewItems(relativePath: String): List<MediaSyncFolderPreviewItem> {
        val collection = externalMediaCollection()
        val modernStorage = Build.VERSION.SDK_INT >= 29
        val pathColumn = if (modernStorage) MediaStore.Files.FileColumns.RELATIVE_PATH
        else MediaStore.Files.FileColumns.DATA
        val mediaTypeColumn = MediaStore.Files.FileColumns.MEDIA_TYPE
        val selection = if (modernStorage) {
            "($pathColumn = ? OR $pathColumn = ?) AND ($mediaTypeColumn = ? OR $mediaTypeColumn = ?)"
        } else {
            "$pathColumn LIKE ? AND ($mediaTypeColumn = ? OR $mediaTypeColumn = ?)"
        }
        val normalized = normalizeMediaStoreRelativePath(relativePath)
        val selectionArgs = if (modernStorage) {
            arrayOf(
                normalized,
                "$normalized/",
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            )
        } else {
            arrayOf(
                File(Environment.getExternalStorageDirectory(), normalized).absolutePath + "/%",
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            )
        }
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns._ID),
            )
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_MEDIA_SYNC_FOLDER_PREVIEW_ITEMS)
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            mediaTypeColumn,
        )
        return buildList {
            context.contentResolver.query(collection, projection, queryArgs, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val typeIndex = cursor.getColumnIndexOrThrow(mediaTypeColumn)
                while (cursor.moveToNext() && size < MAX_MEDIA_SYNC_FOLDER_PREVIEW_ITEMS) {
                    val id = cursor.getLong(idIndex)
                    val modifiedSeconds = nullableLong(cursor, modifiedIndex)
                    val mediaType = cursor.getInt(typeIndex)
                    val cacheKey = "$id:${modifiedSeconds ?: 0L}:$mediaType"
                    val thumbnail = thumbnailCache.get(cacheKey) ?: loadThumbnailBytes(
                        ContentUris.withAppendedId(collection, id),
                        id,
                        mediaType,
                    )?.also { thumbnailCache.put(cacheKey, it) }
                    add(
                        MediaSyncFolderPreviewItem(
                            stableId = "media:$cacheKey",
                            displayName = safeMediaDisplayName(cursor.getString(nameIndex)),
                            mimeType = nullableString(cursor, mimeIndex)
                                ?.filterNot(Char::isISOControl)
                                ?.take(256)
                                ?.takeIf(String::isNotBlank),
                            sizeBytes = nullableLong(cursor, sizeIndex)?.coerceAtLeast(0L),
                            modifiedAtEpochMillis = modifiedSeconds?.coerceAtLeast(0L)?.let {
                                if (it > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else it * 1_000L
                            },
                            thumbnailBytes = thumbnail,
                        ),
                    )
                }
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
        access = mediaFolderAccess(suggestion),
        totalItems = 0,
        totalBytes = 0L,
        items = emptyList(),
        message = message,
    )

    private fun externalMediaCollection(): Uri = MediaStore.Files.getContentUri(
        if (Build.VERSION.SDK_INT >= 29) MediaStore.VOLUME_EXTERNAL else "external",
    )

    private fun nullableLong(cursor: android.database.Cursor, index: Int): Long? =
        if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)

    private fun nullableString(cursor: android.database.Cursor, index: Int): String? =
        if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
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

internal fun hasFullMediaFolderAccess(
    sdk: Int,
    includesImages: Boolean,
    includesVideos: Boolean,
    permissionGranted: (String) -> Boolean,
): Boolean = when {
    sdk >= 33 ->
        (!includesImages || permissionGranted(Manifest.permission.READ_MEDIA_IMAGES)) &&
            (!includesVideos || permissionGranted(Manifest.permission.READ_MEDIA_VIDEO))
    else -> permissionGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun safeMediaDisplayName(value: String?): String =
    value
        ?.filterNot(Char::isISOControl)
        ?.take(512)
        ?.takeIf(String::isNotBlank)
        ?: "Media item"

private fun saturatingMediaItemCount(images: Int, videos: Int): Int =
    (images.toLong() + videos.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
