package dev.obiente.nextcloudnative

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest

internal fun createAndroidFileSyncLocalTree(
    resolver: ContentResolver,
    rootId: String,
    contentHashPaths: Set<String> = emptySet(),
): AndroidFileSyncLocalTree =
    if (rootId.startsWith(MEDIA_STORE_SYNC_ROOT_PREFIX)) {
        AndroidMediaStoreSyncLocalTree(
            root = resolveMediaStoreSyncRoot(rootId, Environment.getExternalStorageDirectory()),
            contentHashPaths = contentHashPaths,
        )
    } else {
        AndroidSafFileSyncLocalTree(resolver, rootId, contentHashPaths)
    }

internal fun resolveMediaStoreSyncRoot(rootId: String, externalStorageRoot: File): File {
    require(rootId.startsWith(MEDIA_STORE_SYNC_ROOT_PREFIX)) {
        "The local sync root is not a detected MediaStore folder."
    }
    val relativePath = normalizeMediaStoreRelativePath(rootId.removePrefix(MEDIA_STORE_SYNC_ROOT_PREFIX))
    val storageRoot = externalStorageRoot.canonicalFile
    val folder = File(storageRoot, relativePath).canonicalFile
    require(folder.toPath().startsWith(storageRoot.toPath()) && folder != storageRoot) {
        "The detected media folder is outside shared storage."
    }
    require(folder.isDirectory) { "The detected media folder no longer exists." }
    require(folder.canRead()) { "The detected media folder is not readable." }
    return folder
}

/**
 * Direct shared-storage adapter for folders already discovered through MediaStore.
 *
 * It avoids a redundant SAF browser while constraining every root to primary shared storage.
 */
internal class AndroidMediaStoreSyncLocalTree(
    private val root: File,
    private val contentHashPaths: Set<String> = emptySet(),
) : AndroidFileSyncLocalTree {
    init {
        require(root.isDirectory && root.canRead()) { "The detected media folder is unavailable." }
    }

    override fun scan(): List<AndroidLocalSyncDocument> {
        return mediaFolderSyncFiles(root).map { file -> file.toSyncDocument(file.name) }
    }

    override fun stageForUpload(path: String, destination: File, maximumBytes: Long): LocalSyncEntry {
        val before = requireNotNull(resolve(path)) { "The local file no longer exists." }
        require(before.entry.kind == SyncEntryKind.File) { "Only files can be uploaded as file content." }
        require((before.entry.size ?: 0L) <= maximumBytes) { "The local file exceeds the sync size limit." }
        FileInputStream(before.uri.toFile()).use { input ->
            FileOutputStream(destination).use { output ->
                var copied = 0L
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= maximumBytes) { "The local file exceeds the sync size limit." }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        val after = requireNotNull(resolve(path)) { "The local file disappeared while it was read." }
        require(after.entry.revision == before.entry.revision) {
            "The local file changed while it was being prepared for upload."
        }
        return after.entry
    }

    override fun createDirectory(path: String, expectedLocalRevision: String?) {
        throw UnsupportedOperationException("Detected media folders are upload-only.")
    }

    override fun writeFile(path: String, source: File, expectedLocalRevision: String?) {
        throw UnsupportedOperationException("Detected media folders are upload-only.")
    }

    override fun delete(path: String, expectedLocalRevision: String) {
        throw UnsupportedOperationException("Detected media folders are upload-only.")
    }

    override fun resolve(path: String): AndroidLocalSyncDocument? {
        if (path.isBlank()) return null
        val normalized = normalizeRelativeSyncPath(path)
        val file = safeFile(normalized)
        if (!file.isMediaFolderSyncFile(root)) return null
        return file.toSyncDocument(normalized)
    }

    private fun safeFile(path: String): File {
        val normalized = normalizeRelativeSyncPath(path)
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, normalized).canonicalFile
        require(file.toPath().startsWith(canonicalRoot.toPath()) && file != canonicalRoot) {
            "The local sync path escapes its root."
        }
        return file
    }

    private fun File.toSyncDocument(relativePath: String): AndroidLocalSyncDocument {
        val kind = if (isDirectory) SyncEntryKind.Directory else SyncEntryKind.File
        val size = if (kind == SyncEntryKind.File) length().coerceAtLeast(0L) else null
        return AndroidLocalSyncDocument(
            entry = LocalSyncEntry(
                relativePath = relativePath,
                kind = kind,
                revision = fileRevision(relativePath, kind, lastModified(), size),
                size = size,
                contentHash = if (
                    kind == SyncEntryKind.File &&
                    relativePath in contentHashPaths &&
                    size != null &&
                    size <= ANDROID_SYNC_CONTENT_IDENTITY_MAX_BYTES
                ) {
                    runCatching {
                        inputStream().use { input ->
                            sha256SyncContentHash(
                                input,
                                expectedBytes = size,
                                maximumBytes = ANDROID_SYNC_CONTENT_IDENTITY_MAX_BYTES,
                            )
                        }
                    }.getOrNull()
                } else {
                    null
                },
            ),
            uri = Uri.fromFile(this),
            displayName = name,
        )
    }

    private fun normalizeRelativeSyncPath(path: String): String {
        val segments = path.trim('/').split('/')
        require(segments.size == 1)
        require(segments.all { it.isSafeLocalName() }) { "The local sync path is invalid." }
        return segments.joinToString("/")
    }

    private fun String.isSafeLocalName(): Boolean =
        isNotBlank() &&
            this !in setOf(".", "..") &&
            '/' !in this &&
            '\\' !in this &&
            none(Char::isISOControl)

    private fun fileRevision(
        relativePath: String,
        kind: SyncEntryKind,
        modified: Long,
        size: Long?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$relativePath\u0000$kind\u0000$modified\u0000${size ?: -1L}".encodeToByteArray())
        return "file-" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun Uri.toFile(): File = File(requireNotNull(path))

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

internal const val MAX_MEDIA_FOLDER_SYNC_ENTRIES = 20_000

/**
 * The exact upload scope used by detected media-folder sync and its confirmation preview.
 *
 * Only direct, visible, regular media files are included. Subfolders, hidden files, sidecars,
 * documents, and temporary files are deliberately outside automatic media upload.
 */
internal fun mediaFolderSyncFiles(
    root: File,
    maximumEntries: Int = MAX_MEDIA_FOLDER_SYNC_ENTRIES,
): List<File> {
    require(maximumEntries > 0)
    val result = mutableListOf<File>()
    var exceedsLimit = false
    forEachMediaFolderSyncFile(root) {
        if (result.size >= maximumEntries) {
            exceedsLimit = true
            false
        } else {
            result += it
            true
        }
    }
    require(!exceedsLimit) { "The local media folder contains too many uploadable files." }
    return result.sortedBy { it.name.lowercase() }
}

internal data class MediaFolderSyncScopeInspection(
    val previewFiles: List<File>,
    val imageCount: Int,
    val videoCount: Int,
    val totalBytes: Long,
    val exceedsSyncLimit: Boolean,
) {
    val totalItems: Int get() = imageCount + videoCount
}

internal fun inspectMediaFolderSyncScope(
    root: File,
    maximumPreviewItems: Int,
): MediaFolderSyncScopeInspection {
    require(maximumPreviewItems >= 0)
    val previews = mutableListOf<File>()
    var imageCount = 0
    var videoCount = 0
    var totalBytes = 0L
    var exceedsSyncLimit = false
    forEachMediaFolderSyncFile(root) { file ->
        if (imageCount + videoCount >= MAX_MEDIA_FOLDER_SYNC_ENTRIES) {
            exceedsSyncLimit = true
            return@forEachMediaFolderSyncFile false
        }
        if (isMediaFolderSyncVideo(file)) videoCount++ else imageCount++
        val size = file.length().coerceAtLeast(0L)
        totalBytes = if (Long.MAX_VALUE - totalBytes < size) Long.MAX_VALUE else totalBytes + size
        previews += file
        previews.sortWith(compareByDescending<File>(File::lastModified).thenBy { it.name.lowercase() })
        if (previews.size > maximumPreviewItems) previews.removeAt(previews.lastIndex)
        true
    }
    return MediaFolderSyncScopeInspection(
        previewFiles = previews,
        imageCount = imageCount,
        videoCount = videoCount,
        totalBytes = totalBytes,
        exceedsSyncLimit = exceedsSyncLimit,
    )
}

private inline fun forEachMediaFolderSyncFile(
    root: File,
    visit: (File) -> Boolean = { true },
) {
    val canonicalRoot = root.canonicalFile
    Files.newDirectoryStream(canonicalRoot.toPath()).use { entries ->
        for (entry in entries) {
            val file = entry.toFile()
            if (file.isMediaFolderSyncFile(canonicalRoot) && !visit(file)) return
        }
    }
}

private fun File.isMediaFolderSyncFile(root: File): Boolean {
    if (
        !isFile ||
        Files.isSymbolicLink(toPath()) ||
        isHidden ||
        name.startsWith('.') ||
        !name.isSafeMediaFolderSyncName()
    ) {
        return false
    }
    val canonicalRoot = root.canonicalFile
    val canonicalFile = runCatching { canonicalFile }.getOrNull() ?: return false
    if (canonicalFile.parentFile != canonicalRoot) return false
    return extension.lowercase() in MEDIA_FOLDER_SYNC_IMAGE_EXTENSIONS ||
        extension.lowercase() in MEDIA_FOLDER_SYNC_VIDEO_EXTENSIONS
}

private fun String.isSafeMediaFolderSyncName(): Boolean =
    isNotBlank() &&
        this !in setOf(".", "..") &&
        '/' !in this &&
        '\\' !in this &&
        none(Char::isISOControl)

internal fun isMediaFolderSyncVideo(file: File): Boolean =
    file.extension.lowercase() in MEDIA_FOLDER_SYNC_VIDEO_EXTENSIONS

private val MEDIA_FOLDER_SYNC_IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "tif", "tiff", "jxl",
    "dng", "arw", "cr2", "cr3", "nef", "nrw", "orf", "raf", "rw2", "pef", "srw", "x3f",
)

private val MEDIA_FOLDER_SYNC_VIDEO_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "3gpp", "mpg", "mpeg", "ts", "mts", "m2ts", "ogv",
)
