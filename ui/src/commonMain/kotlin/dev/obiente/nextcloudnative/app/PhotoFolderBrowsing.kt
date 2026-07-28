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
 * Compact folder inventory derived from every unstacked timeline page.
 *
 * Full [NextcloudFile] records are deliberately absent. The summary retains only normalized path
 * and stack identities while pages are accumulated, then exposes folder counts for presentation.
 */
data class PhotoFolderInventorySummary(
    val folders: List<PhotoFolderSummary>,
    val rootRecursiveMediaCount: Int,
    val indexedMediaRecordCount: Int,
) {
    init {
        require(rootRecursiveMediaCount >= 0) { "The root photo count is invalid." }
        require(indexedMediaRecordCount >= rootRecursiveMediaCount) {
            "The indexed photo record count is invalid."
        }
        require(folders.map(PhotoFolderSummary::path).distinct().size == folders.size) {
            "The photo folder summary contains duplicate folders."
        }
        require(folders == folders.sortedBy(PhotoFolderSummary::path)) {
            "The photo folder summary is not ordered by path."
        }
    }

    fun folder(path: String): PhotoFolderSummary? =
        folders.binarySearchBy(path) { folder -> folder.path }.takeIf { it >= 0 }?.let(folders::get)

    fun recursiveMediaCount(path: String): Int =
        if (path.isEmpty()) rootRecursiveMediaCount else folder(path)?.recursiveMediaCount ?: 0
}

/**
 * One immutable result from a paged folder inventory pass.
 *
 * [selectedMediaFiles] is a bounded display window. Folder counts remain independent from that
 * window and therefore persist when older timeline pages are accumulated.
 */
data class PhotoFolderPagedInventory(
    val selectedFolderPath: String,
    val selectedScope: PhotoFolderBrowseScope,
    val summary: PhotoFolderInventorySummary,
    val selectedMediaFiles: List<NextcloudFile>,
) {
    init {
        requirePhotoFolderPath(selectedFolderPath, allowRoot = true)
        require(selectedMediaFiles.size <= MAX_PHOTO_FOLDER_SELECTED_MEDIA_RECORDS) {
            "The selected photo folder media window is too large."
        }
        require(
            selectedMediaFiles.all { file ->
                file.path.parentFolderPath().isInPhotoFolderScope(selectedFolderPath, selectedScope)
            },
        ) {
            "The retained media does not belong to the selected photo folder."
        }
    }
}

enum class PhotoFolderInventoryReadiness {
    Loading,
    InitialFailure,
    Ready,
    Stale,
}

fun photoFolderInventoryReadiness(
    hasInventory: Boolean,
    refreshError: String?,
): PhotoFolderInventoryReadiness = when {
    hasInventory && refreshError != null -> PhotoFolderInventoryReadiness.Stale
    hasInventory -> PhotoFolderInventoryReadiness.Ready
    refreshError != null -> PhotoFolderInventoryReadiness.InitialFailure
    else -> PhotoFolderInventoryReadiness.Loading
}

enum class PhotoFolderInventoryTruncationReason {
    MediaRecordLimit,
    FolderLimit,
}

sealed interface PhotoFolderPageAcceptance {
    data class Accepted(
        val novelMediaRecords: Int,
    ) : PhotoFolderPageAcceptance {
        init {
            require(novelMediaRecords >= 0) { "The accepted photo record count is invalid." }
        }
    }

    data class Truncated(
        val reason: PhotoFolderInventoryTruncationReason,
    ) : PhotoFolderPageAcceptance
}

/**
 * Bounded accumulator for paged, unstacked timeline records.
 *
 * Each logical media path is retained only as compact folder/stack metadata. Exact normalized-path
 * deduplication and RAW/rendered sibling matching therefore continue across page boundaries without
 * retaining the complete media list. Only the selected folder keeps bounded full records for its
 * viewer and grid.
 */
class PhotoFolderSummaryAccumulator(
    private val selectedFolderPath: String,
    private val selectedScope: PhotoFolderBrowseScope,
    private val maximumMediaRecords: Int = MAX_PHOTO_FOLDER_SUMMARY_MEDIA_RECORDS,
    private val maximumFolders: Int = MAX_PHOTO_FOLDER_SUMMARY_FOLDERS,
    private val maximumSelectedMediaRecords: Int = MAX_PHOTO_FOLDER_SELECTED_MEDIA_RECORDS,
) {
    private val mediaByPath = linkedMapOf<String, CompactPhotoFolderMedia>()
    private val folderPaths = linkedSetOf("")
    private val selectedMediaByPath = linkedMapOf<String, NextcloudFile>()

    init {
        requirePhotoFolderPath(selectedFolderPath, allowRoot = true)
        require(maximumMediaRecords in 1..MAX_PHOTO_FOLDER_SUMMARY_MEDIA_RECORDS) {
            "The photo folder summary media limit is invalid."
        }
        require(maximumFolders in 1..MAX_PHOTO_FOLDER_SUMMARY_FOLDERS) {
            "The photo folder summary folder limit is invalid."
        }
        require(maximumSelectedMediaRecords in 1..MAX_PHOTO_FOLDER_SELECTED_MEDIA_RECORDS) {
            "The selected photo folder media limit is invalid."
        }
        selectedFolderPath.ancestorsIncludingSelf().forEach(folderPaths::add)
        require(folderPaths.size <= maximumFolders + 1) {
            "The selected photo folder exceeds the summary folder bound."
        }
    }

    /**
     * Adds one timeline page atomically. A page that would exceed a configured bound is rejected
     * before any accumulator state changes.
     */
    fun tryAddPage(records: List<NextcloudFile>): PhotoFolderPageAcceptance {
        val pageMedia = reconcilePhotoFolderInventory(records)
            .asSequence()
            .filter { file -> !file.isDirectory && file.mediaAssetFormat() != MediaAssetFormat.Other }
            .toList()
        val novelMedia = pageMedia.filterNot { file -> file.path in mediaByPath }
        if (mediaByPath.size + novelMedia.size > maximumMediaRecords) {
            return PhotoFolderPageAcceptance.Truncated(
                PhotoFolderInventoryTruncationReason.MediaRecordLimit,
            )
        }
        val novelFolders = novelMedia
            .asSequence()
            .flatMap { file -> file.path.parentFolderPath().ancestorsIncludingSelf().asSequence() }
            .filterNot(folderPaths::contains)
            .toSet()
        if (folderPaths.size + novelFolders.size > maximumFolders + 1) {
            return PhotoFolderPageAcceptance.Truncated(
                PhotoFolderInventoryTruncationReason.FolderLimit,
            )
        }

        pageMedia.forEach { file ->
            if (file.path !in mediaByPath) {
                mediaByPath[file.path] = file.toCompactPhotoFolderMedia()
            }
            file.path.parentFolderPath().ancestorsIncludingSelf().forEach(folderPaths::add)

            if (file.path.parentFolderPath().isInPhotoFolderScope(selectedFolderPath, selectedScope)) {
                when {
                    file.path in selectedMediaByPath ->
                        selectedMediaByPath[file.path] = listOf(
                            selectedMediaByPath.getValue(file.path),
                            file,
                        ).maxWith(photoInventoryPreference)
                    selectedMediaByPath.size < maximumSelectedMediaRecords ->
                        selectedMediaByPath[file.path] = file
                }
            }
        }
        return PhotoFolderPageAcceptance.Accepted(novelMedia.size)
    }

    fun addPage(records: List<NextcloudFile>) {
        when (val acceptance = tryAddPage(records)) {
            is PhotoFolderPageAcceptance.Accepted -> Unit
            is PhotoFolderPageAcceptance.Truncated -> throw IllegalArgumentException(
                when (acceptance.reason) {
                    PhotoFolderInventoryTruncationReason.MediaRecordLimit ->
                        "The paged photo folder summary exceeds its media bound."
                    PhotoFolderInventoryTruncationReason.FolderLimit ->
                        "The paged photo folder summary exceeds its folder bound."
                },
            )
        }
    }

    fun summarySnapshot(): PhotoFolderInventorySummary {
        val renderedKeysByFolder = mediaByPath.values
            .asSequence()
            .filter { record ->
                record.format == MediaAssetFormat.Jpeg || record.format == MediaAssetFormat.Image
            }
            .groupBy(CompactPhotoFolderMedia::folderPath)
            .mapValues { (_, records) -> records.flatMapTo(mutableSetOf(), CompactPhotoFolderMedia::stackKeys) }
        val directMediaCounts = mediaByPath.values
            .asSequence()
            .filterNot { record ->
                record.format == MediaAssetFormat.Raw &&
                    record.stackKeys.single() in renderedKeysByFolder[record.folderPath].orEmpty()
            }
            .groupingBy(CompactPhotoFolderMedia::folderPath)
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
        val folders = folderPaths
            .asSequence()
            .filter(String::isNotEmpty)
            .sorted()
            .map { path ->
                PhotoFolderSummary(
                    path = path,
                    name = path.substringAfterLast('/'),
                    directMediaCount = directMediaCounts[path] ?: 0,
                    recursiveMediaCount = recursiveMediaCounts[path] ?: 0,
                    directChildFolderCount = directFolderCounts[path] ?: 0,
                )
            }
            .toList()

        return PhotoFolderInventorySummary(
            folders = folders,
            rootRecursiveMediaCount = recursiveMediaCounts[""] ?: 0,
            indexedMediaRecordCount = mediaByPath.size,
        )
    }

    fun snapshot(): PhotoFolderPagedInventory = PhotoFolderPagedInventory(
        selectedFolderPath = selectedFolderPath,
        selectedScope = selectedScope,
        summary = summarySnapshot(),
        selectedMediaFiles = selectedMediaByPath.values.toList(),
    )
}

data class PhotoFolderInventoryPublication(
    val revision: Long,
    val summary: PhotoFolderInventorySummary,
) {
    init {
        require(revision > 0) { "The photo folder inventory revision is invalid." }
    }
}

/**
 * Account-local owner of the complete bounded media index.
 *
 * Full records never enter [PhotoFolderInventoryPublication]. Presentation can observe the compact
 * summary and revision, then call [browse] only when its local selection changes.
 */
class PhotoFolderInventoryRepository(
    maximumMediaRecords: Int = MAX_PHOTO_FOLDER_INVENTORY_PAGING_RECORDS,
    maximumFolders: Int = MAX_PHOTO_FOLDER_SUMMARY_FOLDERS,
    private val maximumSelectionRecords: Int = MAX_PHOTO_FOLDER_SELECTED_MEDIA_RECORDS,
) {
    private val summaryAccumulator = PhotoFolderSummaryAccumulator(
        selectedFolderPath = "",
        selectedScope = PhotoFolderBrowseScope.RecursiveMedia,
        maximumMediaRecords = maximumMediaRecords,
        maximumFolders = maximumFolders,
        maximumSelectedMediaRecords = 1,
    )
    private val mediaByPath = linkedMapOf<String, NextcloudFile>()
    private var revision = 0L

    init {
        require(maximumMediaRecords in 1..MAX_PHOTO_FOLDER_INVENTORY_PAGING_RECORDS) {
            "The photo folder inventory media limit is invalid."
        }
        require(maximumSelectionRecords in 1..MAX_PHOTO_FOLDER_SELECTED_MEDIA_RECORDS) {
            "The photo folder inventory selection limit is invalid."
        }
    }

    fun tryAddPage(records: List<NextcloudFile>): PhotoFolderPageAcceptance {
        val normalizedMedia = reconcilePhotoFolderInventory(records)
            .filter { file -> !file.isDirectory && file.mediaAssetFormat() != MediaAssetFormat.Other }
        return when (val acceptance = summaryAccumulator.tryAddPage(normalizedMedia)) {
            is PhotoFolderPageAcceptance.Truncated -> acceptance
            is PhotoFolderPageAcceptance.Accepted -> {
                normalizedMedia.forEach { file ->
                    val existing = mediaByPath[file.path]
                    mediaByPath[file.path] = if (existing == null) {
                        file
                    } else {
                        listOf(existing, file).maxWith(photoInventoryPreference)
                    }
                }
                revision += 1
                acceptance
            }
        }
    }

    fun publication(): PhotoFolderInventoryPublication? =
        revision.takeIf { it > 0 }?.let { currentRevision ->
            PhotoFolderInventoryPublication(
                revision = currentRevision,
                summary = summaryAccumulator.summarySnapshot(),
            )
        }

    fun selectionSnapshot(state: PhotoFolderBrowseState): PhotoFolderPagedInventory {
        val query = state.query.trim()
        val selectedFiles = if (state.scope == PhotoFolderBrowseScope.FoldersOnly) {
            emptyList()
        } else {
            mediaByPath.values
                .asSequence()
                .filter { file ->
                    val folderPath = file.path.parentFolderPath()
                    folderPath.isInPhotoFolderScope(state.selectedFolderPath, state.scope) &&
                        (query.isEmpty() || folderPath.matchesFolderQuery(query))
                }
                .take(maximumSelectionRecords)
                .toList()
        }
        return PhotoFolderPagedInventory(
            selectedFolderPath = state.selectedFolderPath,
            selectedScope = state.scope,
            summary = summaryAccumulator.summarySnapshot(),
            selectedMediaFiles = selectedFiles,
        )
    }

    fun browse(state: PhotoFolderBrowseState): PhotoFolderBrowseResult =
        buildPhotoFolderBrowseResult(selectionSnapshot(state), state)
}

private data class CompactPhotoFolderMedia(
    val folderPath: String,
    val format: MediaAssetFormat,
    val stackKeys: Set<String>,
)

private fun NextcloudFile.toCompactPhotoFolderMedia(): CompactPhotoFolderMedia {
    val stem = name.substringBeforeLast('.', missingDelimiterValue = name).lowercase()
    val keys = when (mediaAssetFormat()) {
        MediaAssetFormat.Raw -> setOf(if (".original" in stem) stem.substringBefore('.') else stem)
        MediaAssetFormat.Jpeg,
        MediaAssetFormat.Image,
        -> buildSet {
            add(stem)
            if ('.' in stem) add(stem.substringBefore('.'))
        }
        MediaAssetFormat.Video -> emptySet()
        MediaAssetFormat.Other -> error("Non-media files cannot enter a photo folder summary.")
    }
    return CompactPhotoFolderMedia(
        folderPath = path.parentFolderPath(),
        format = mediaAssetFormat(),
        stackKeys = keys,
    )
}

/**
 * A bounded, account-local snapshot used to paint the folder browser immediately while its
 * inventory is revalidated in the background.
 */
data class PhotoFolderInventorySnapshot(
    val files: List<NextcloudFile>,
    val backupStatuses: Map<String, MediaBackupStatus>,
) {
    init {
        require(files.size <= MAX_PHOTO_FOLDER_BROWSE_RECORDS) {
            "The cached photo folder inventory exceeds the bounded browse window."
        }
        val paths = files.mapTo(mutableSetOf()) { file -> file.path.trim('/') }
        require(backupStatuses.keys.all(paths::contains)) {
            "The cached photo backup statuses include files outside the inventory."
        }
    }

    fun withUpdatedBackupStatuses(
        updates: Map<String, MediaBackupStatus>,
    ): PhotoFolderInventorySnapshot {
        if (updates.isEmpty()) return this
        val paths = files.mapTo(mutableSetOf()) { file -> file.path.trim('/') }
        val normalizedUpdates = updates
            .asSequence()
            .map { (path, status) -> path.trim('/') to status }
            .filter { (path, _) -> path in paths }
            .toMap()
        return copy(backupStatuses = backupStatuses + normalizedUpdates)
    }
}

fun buildPhotoFolderInventorySnapshot(
    inventory: List<NextcloudFile>,
    backupStatuses: Map<String, MediaBackupStatus>,
    maximumRecords: Int = MAX_PHOTO_FOLDER_BROWSE_RECORDS,
): PhotoFolderInventorySnapshot {
    require(maximumRecords in 1..MAX_PHOTO_FOLDER_BROWSE_RECORDS) {
        "The photo folder inventory cache limit is invalid."
    }
    require(inventory.size <= maximumRecords) {
        "The photo folder inventory exceeds the bounded cache window."
    }
    val files = reconcilePhotoFolderInventory(inventory)
    val paths = files.mapTo(mutableSetOf()) { file -> file.path.trim('/') }
    val statuses = backupStatuses
        .asSequence()
        .map { (path, status) -> path.trim('/') to status }
        .filter { (path, _) -> path in paths }
        .toMap()
    return PhotoFolderInventorySnapshot(files = files, backupStatuses = statuses)
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

    return buildPhotoFolderBrowseResult(
        inventory = buildPhotoFolderPagedInventory(
            pages = listOf(inventory),
            state = state,
            maximumMediaRecords = maximumRecords,
            maximumSelectedMediaRecords = minOf(MAX_PHOTO_FOLDER_SELECTED_MEDIA_RECORDS, maximumRecords),
        ),
        state = state,
    )
}

/**
 * Feeds accepted unstacked timeline pages into one bounded folder accumulator in fetch order.
 */
fun buildPhotoFolderPagedInventory(
    pages: Iterable<List<NextcloudFile>>,
    state: PhotoFolderBrowseState,
    maximumMediaRecords: Int = MAX_PHOTO_FOLDER_SUMMARY_MEDIA_RECORDS,
    maximumFolders: Int = MAX_PHOTO_FOLDER_SUMMARY_FOLDERS,
    maximumSelectedMediaRecords: Int = MAX_PHOTO_FOLDER_SELECTED_MEDIA_RECORDS,
): PhotoFolderPagedInventory {
    val accumulator = PhotoFolderSummaryAccumulator(
        selectedFolderPath = state.selectedFolderPath,
        selectedScope = state.scope,
        maximumMediaRecords = maximumMediaRecords,
        maximumFolders = maximumFolders,
        maximumSelectedMediaRecords = maximumSelectedMediaRecords,
    )
    pages.forEach(accumulator::addPage)
    return accumulator.snapshot()
}

fun buildPhotoFolderBrowseResult(
    inventory: PhotoFolderPagedInventory,
    state: PhotoFolderBrowseState,
): PhotoFolderBrowseResult {
    require(inventory.selectedFolderPath == state.selectedFolderPath) {
        "The paged photo inventory belongs to another selected folder."
    }
    require(inventory.selectedScope == state.scope) {
        "The paged photo inventory belongs to another folder scope."
    }
    val query = state.query.trim()
    val folders = if (state.scope.showsFolders()) {
        inventory.summary.folders.asSequence()
            .filter { folder -> folder.path.isStrictDescendantOf(state.selectedFolderPath) }
            .filter { folder ->
                if (query.isEmpty()) {
                    folder.path.parentFolderPath() == state.selectedFolderPath
                } else {
                    folder.path.matchesFolderQuery(query)
                }
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

    val media = stackMediaFiles(inventory.selectedMediaFiles)
        .sortedWith(photoFolderMediaOrder)
        .filter { stack ->
            val folderPath = stack.folderPath()
            folderPath.isInPhotoFolderScope(state.selectedFolderPath, state.scope) &&
                (query.isEmpty() || folderPath.matchesFolderQuery(query))
        }

    return PhotoFolderBrowseResult(
        state = state,
        folders = folders,
        media = media,
        recursiveMediaCount = inventory.summary.recursiveMediaCount(state.selectedFolderPath),
    )
}

private fun reconcilePhotoFolderInventory(inventory: List<NextcloudFile>): List<NextcloudFile> =
    inventory
        .map(NextcloudFile::normalizedPhotoFolderRecord)
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

private fun NextcloudFile.normalizedPhotoFolderRecord(): NextcloudFile {
    val normalizedPath = path.trim('/')
    return if (normalizedPath == path) this else copy(path = normalizedPath)
}

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

private fun PhotoFolderBrowseScope.showsFolders(): Boolean =
    this == PhotoFolderBrowseScope.FoldersOnly ||
        this == PhotoFolderBrowseScope.DirectMediaAndSubfolders

private fun MediaStack.folderPath(): String = cover.path.parentFolderPath()

private fun String.isInPhotoFolderScope(
    selectedFolderPath: String,
    scope: PhotoFolderBrowseScope,
): Boolean = when (scope) {
    PhotoFolderBrowseScope.FoldersOnly -> false
    PhotoFolderBrowseScope.DirectMediaAndSubfolders,
    PhotoFolderBrowseScope.DirectMediaOnly,
    -> this == selectedFolderPath
    PhotoFolderBrowseScope.RecursiveMedia ->
        this == selectedFolderPath || isStrictDescendantOf(selectedFolderPath)
}

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
const val MAX_PHOTO_FOLDER_SUMMARY_MEDIA_RECORDS = 250_000
const val MAX_PHOTO_FOLDER_SUMMARY_FOLDERS = 25_000
const val MAX_PHOTO_FOLDER_SELECTED_MEDIA_RECORDS = 2_000
private const val MAX_PHOTO_FOLDER_PATH_LENGTH = 4_096
internal const val MAX_PHOTO_FOLDER_QUERY_LENGTH = 256
private const val MAX_PHOTO_FOLDER_DEPTH = 128
