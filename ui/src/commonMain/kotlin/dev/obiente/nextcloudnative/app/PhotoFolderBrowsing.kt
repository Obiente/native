package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Controls which folder and media records are visible for the selected folder.
 *
 * Search always targets folder names and paths. It never turns into a filename search.
 */
@Serializable
enum class PhotoFolderBrowseScope {
    FoldersOnly,
    DirectMediaAndSubfolders,
    DirectMediaOnly,
    RecursiveMedia,
}

@Serializable
enum class PhotoFolderViewMode {
    Grid,
    List,
}

/**
 * Small durable preference record that platform stores can serialize without retaining navigation
 * or account state.
 */
@Serializable
data class PhotoFolderBrowsePreference(
    val viewMode: PhotoFolderViewMode = PhotoFolderViewMode.Grid,
)

@Serializable
data class PhotoFolderBrowseState(
    val selectedFolderPath: String = "",
    val query: String = "",
    val scope: PhotoFolderBrowseScope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
    val preference: PhotoFolderBrowsePreference = PhotoFolderBrowsePreference(),
) {
    init {
        require(selectedFolderPath.length <= MAX_PHOTO_FOLDER_PATH_LENGTH) {
            "The selected photo folder path is too long."
        }
        requirePhotoFolderPath(selectedFolderPath, allowRoot = true)
        require(query.length <= MAX_PHOTO_FOLDER_QUERY_LENGTH && query.none(Char::isISOControl)) {
            "The photo folder search is invalid."
        }
    }
}

data class PhotoFolderSummary(
    val path: String,
    val name: String,
    val directMediaCount: Int,
    val recursiveMediaCount: Int,
    val directChildFolderCount: Int,
) {
    init {
        require(path.isNotEmpty()) { "The account root is not a photo folder row." }
        requirePhotoFolderPath(path, allowRoot = false)
        require(name == path.substringAfterLast('/')) { "The photo folder name does not match its path." }
        require(directMediaCount >= 0 && recursiveMediaCount >= directMediaCount) {
            "The photo folder media counts are invalid."
        }
        require(directChildFolderCount >= 0) { "The photo folder child count is invalid." }
    }
}

data class PhotoFolderBrowseResult(
    val state: PhotoFolderBrowseState,
    val folders: List<PhotoFolderSummary>,
    val media: List<MediaStack>,
    /** Number of logical media items at or below the selected folder before search filtering. */
    val recursiveMediaCount: Int,
) {
    init {
        require(recursiveMediaCount >= media.size) { "The photo folder result count is invalid." }
        require(folders.map(PhotoFolderSummary::path).distinct().size == folders.size) {
            "The photo folder result contains duplicate folders."
        }
        require(media.flatMap(MediaStack::members).map(NextcloudFile::path).distinct().size ==
            media.sumOf { it.members.size }) {
            "The photo folder result contains duplicate media files."
        }
    }
}

/**
 * Creates a presentation-neutral folder browser from a bounded Files DAV or media search window.
 *
 * Directory records are optional. Missing ancestors are inferred from authoritative media paths so
 * a SEARCH response can still provide a complete folder hierarchy. Duplicate paths are reconciled
 * deterministically before logical RAW/rendered stacking.
 */
fun buildPhotoFolderBrowseResult(
    inventory: List<NextcloudFile>,
    state: PhotoFolderBrowseState,
    maximumRecords: Int = MAX_PHOTO_FOLDER_BROWSE_RECORDS,
): PhotoFolderBrowseResult {
    require(maximumRecords in 1..MAX_PHOTO_FOLDER_BROWSE_RECORDS) {
        "The photo folder browse limit is invalid."
    }
    require(inventory.size <= maximumRecords) {
        "The photo folder inventory exceeds the bounded browse window."
    }

    val files = reconcilePhotoFolderInventory(inventory)
    val mediaFiles = files.filter { file ->
        !file.isDirectory && file.mediaAssetFormat() != MediaAssetFormat.Other
    }
    val folderPaths = inferPhotoFolderPaths(mediaFiles, state.selectedFolderPath)
    val logicalMedia = stackMediaFiles(
        mediaFiles,
    ).sortedWith(photoFolderMediaOrder)
    val directMediaCounts = logicalMedia
        .groupingBy(MediaStack::folderPath)
        .eachCount()
    val recursiveMediaCounts = buildMap<String, Int> {
        directMediaCounts.forEach { (folderPath, count) ->
            folderPath.ancestorsIncludingSelf().forEach { ancestor ->
                put(ancestor, getOrElse(ancestor) { 0 } + count)
            }
        }
    }
    val directFolderCounts = folderPaths
        .asSequence()
        .filter(String::isNotEmpty)
        .groupingBy(String::parentFolderPath)
        .eachCount()

    val query = state.query.trim()
    val folders = if (state.scope.showsFolders()) {
        folderPaths.asSequence()
            .filter(String::isNotEmpty)
            .filter { it.isStrictDescendantOf(state.selectedFolderPath) }
            .filter { path ->
                if (query.isEmpty()) {
                    path.parentFolderPath() == state.selectedFolderPath
                } else {
                    path.matchesFolderQuery(query)
                }
            }
            .map { path ->
                PhotoFolderSummary(
                    path = path,
                    name = path.substringAfterLast('/'),
                    directMediaCount = directMediaCounts[path] ?: 0,
                    recursiveMediaCount = recursiveMediaCounts[path] ?: 0,
                    directChildFolderCount = directFolderCounts[path] ?: 0,
                )
            }
            .sortedWith(
                compareBy<PhotoFolderSummary> { it.name.lowercase() }
                    .thenBy { it.path.lowercase() }
                    .thenBy(PhotoFolderSummary::path),
            )
            .toList()
    } else {
        emptyList()
    }

    val media = logicalMedia.filter { stack ->
        val folderPath = stack.folderPath()
        val inScope = when (state.scope) {
            PhotoFolderBrowseScope.FoldersOnly -> false
            PhotoFolderBrowseScope.DirectMediaAndSubfolders,
            PhotoFolderBrowseScope.DirectMediaOnly,
            -> folderPath == state.selectedFolderPath
            PhotoFolderBrowseScope.RecursiveMedia ->
                folderPath == state.selectedFolderPath ||
                    folderPath.isStrictDescendantOf(state.selectedFolderPath)
        }
        inScope && (query.isEmpty() || folderPath.matchesFolderQuery(query))
    }

    return PhotoFolderBrowseResult(
        state = state,
        folders = folders,
        media = media,
        recursiveMediaCount = recursiveMediaCounts[state.selectedFolderPath] ?: 0,
    )
}

private fun reconcilePhotoFolderInventory(inventory: List<NextcloudFile>): List<NextcloudFile> =
    inventory
        .onEach { file ->
            require(file.path.length <= MAX_PHOTO_FOLDER_PATH_LENGTH) {
                "A photo inventory path is too long."
            }
            requirePhotoFolderPath(file.path, allowRoot = file.isDirectory)
        }
        .groupBy(NextcloudFile::path)
        .toSortedMap()
        .values
        .map { duplicates -> duplicates.maxWith(photoInventoryPreference) }

private val photoInventoryPreference = compareBy<NextcloudFile>(
    { if (it.isDirectory) 1 else 0 },
    { if (it.fileId != null) 1 else 0 },
    { if (it.hasPreview) 1 else 0 },
    { if (it.etag != null) 1 else 0 },
    { if (it.originalAccessAllowed) 1 else 0 },
    { if (it.davPathAuthoritative) 1 else 0 },
    { if (it.mimeType != null) 1 else 0 },
    { it.lastModified.orEmpty() },
    { it.size ?: Long.MIN_VALUE },
    { it.name },
    { it.mimeType.orEmpty() },
    { it.etag.orEmpty() },
)

private fun inferPhotoFolderPaths(
    inventory: List<NextcloudFile>,
    selectedFolderPath: String,
): Set<String> = buildSet {
    add("")
    selectedFolderPath.ancestorsIncludingSelf().forEach(::add)
    inventory.forEach { file ->
        val folderPath = if (file.isDirectory) file.path else file.path.parentFolderPath()
        folderPath.ancestorsIncludingSelf().forEach(::add)
    }
}

private fun PhotoFolderBrowseScope.showsFolders(): Boolean =
    this == PhotoFolderBrowseScope.FoldersOnly ||
        this == PhotoFolderBrowseScope.DirectMediaAndSubfolders

private fun MediaStack.folderPath(): String = cover.path.parentFolderPath()

private val photoFolderMediaOrder = compareByDescending<MediaStack> { stack ->
    stack.members
        .mapNotNull { file -> file.lastModified?.let(::parsePhotoFolderTimestamp) }
        .maxOrNull()
        ?: Long.MIN_VALUE
}.thenBy { it.cover.name.lowercase() }
    .thenBy { it.cover.path.lowercase() }
    .thenBy { it.cover.path }

private fun parsePhotoFolderTimestamp(value: String): Long? =
    parseDavMediaSearchTimestamp(value)
        ?: runCatching { Instant.parse(value.trim()).epochSeconds }.getOrNull()

internal fun sanitizePhotoFolderQuery(value: String): String = buildString(
    minOf(value.length, MAX_PHOTO_FOLDER_QUERY_LENGTH),
) {
    value.asSequence()
        .map { character -> if (character.isISOControl()) ' ' else character }
        .take(MAX_PHOTO_FOLDER_QUERY_LENGTH)
        .forEach(::append)
}

private fun String.parentFolderPath(): String = substringBeforeLast('/', missingDelimiterValue = "")

private fun String.ancestorsIncludingSelf(): List<String> {
    if (isEmpty()) return listOf("")
    return buildList {
        add("")
        var separator = this@ancestorsIncludingSelf.indexOf('/')
        while (separator >= 0) {
            add(substring(0, separator))
            separator = this@ancestorsIncludingSelf.indexOf('/', startIndex = separator + 1)
        }
        add(this@ancestorsIncludingSelf)
    }
}

private fun String.isStrictDescendantOf(parent: String): Boolean =
    if (parent.isEmpty()) isNotEmpty() else startsWith("$parent/")

private fun String.matchesFolderQuery(query: String): Boolean =
    contains(query, ignoreCase = true) ||
        substringAfterLast('/', missingDelimiterValue = this).contains(query, ignoreCase = true)

private fun requirePhotoFolderPath(path: String, allowRoot: Boolean): String {
    require(path.isEmpty() || path.count { it == '/' } < MAX_PHOTO_FOLDER_DEPTH) {
        "The photo folder path is too deeply nested."
    }
    return requireSafeFilePath(path, allowRoot)
}

const val MAX_PHOTO_FOLDER_BROWSE_RECORDS = 50_000
private const val MAX_PHOTO_FOLDER_PATH_LENGTH = 4_096
internal const val MAX_PHOTO_FOLDER_QUERY_LENGTH = 256
private const val MAX_PHOTO_FOLDER_DEPTH = 128
