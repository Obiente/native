package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileOfflineKey
import dev.obiente.nextcloudnative.app.FileOfflineDescriptor
import dev.obiente.nextcloudnative.app.FileOfflineRequest
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.planFileOfflineRequest
import java.util.ArrayDeque

internal data class AndroidOfflineFolderLimits(
    val maximumDepth: Int = 16,
    val maximumEntries: Int = 5_000,
    val maximumTotalBytes: Long = Long.MAX_VALUE,
) {
    init {
        require(maximumDepth in 1..64)
        require(maximumEntries in 1..100_000)
        require(maximumTotalBytes > 0L)
    }
}

internal data class AndroidOfflineDirectory(
    val path: String,
    val displayName: String,
    val remoteEtag: String?,
    val lastModified: String?,
    val fileId: Long?,
) {
    init {
        require(displayName.isNotBlank())
        FileOfflineKey(VALIDATION_ACCOUNT_ID, path)
    }

    fun toNextcloudFile(): NextcloudFile = NextcloudFile(
        path = path,
        name = displayName,
        isDirectory = true,
        mimeType = null,
        size = null,
        lastModified = lastModified,
        fileId = fileId,
        hasPreview = false,
        etag = remoteEtag,
    )
}

internal data class AndroidOfflineFolderInventory(
    val rootPath: String,
    val rootDisplayName: String,
    val directories: List<AndroidOfflineDirectory>,
    val files: List<NextcloudFile>,
    val totalBytes: Long,
)

internal data class AndroidOfflineFolderRoot(
    val accountId: String,
    val rootPath: String,
    val rootDisplayName: String,
    val directories: List<AndroidOfflineDirectory>,
    val filePaths: List<String>,
) {
    init {
        FileOfflineKey(accountId, rootPath)
        require(rootDisplayName.isNotBlank())
        require(directories.isNotEmpty() && directories.any { it.path == rootPath })
        require(directories.map(AndroidOfflineDirectory::path).distinct().size == directories.size)
        require(filePaths.distinct().size == filePaths.size)
        filePaths.forEach { FileOfflineKey(accountId, it) }
        require((directories.map(AndroidOfflineDirectory::path) + filePaths).all {
            it == rootPath || it.startsWith("$rootPath/")
        })
    }
}

internal data class AndroidOfflineFolderState(
    val directPins: Set<FileOfflineKey> = emptySet(),
    val roots: List<AndroidOfflineFolderRoot> = emptyList(),
) {
    init {
        require(roots.map { it.accountId to it.rootPath }.distinct().size == roots.size)
    }

    fun root(accountId: String, path: String): AndroidOfflineFolderRoot? =
        roots.firstOrNull { it.accountId == accountId && it.rootPath == path }

    fun isCovered(key: FileOfflineKey): Boolean =
        roots.any { it.accountId == key.accountId && key.relativePath in it.filePaths }
}

/**
 * Reads a remote folder tree into a deterministic, bounded inventory.
 *
 * The caller supplies depth-one listings. Every returned path must be an immediate child of the
 * requested folder, which prevents a malformed DAV response from escaping or silently widening the
 * selected root. Files without a size or ETag are rejected because they cannot participate in a
 * conflict-safe download queue.
 */
internal fun planAndroidOfflineFolder(
    root: NextcloudFile,
    limits: AndroidOfflineFolderLimits = AndroidOfflineFolderLimits(),
    listChildren: (String) -> List<NextcloudFile>,
): AndroidOfflineFolderInventory {
    require(root.isDirectory) { "Only a folder can be planned recursively." }
    FileOfflineKey(VALIDATION_ACCOUNT_ID, root.path)

    data class PendingDirectory(val file: NextcloudFile, val depth: Int)

    val pending = ArrayDeque<PendingDirectory>()
    pending.add(PendingDirectory(root, 0))
    val seenPaths = linkedSetOf(root.path)
    val directories = mutableListOf(root.toOfflineDirectory())
    val files = mutableListOf<NextcloudFile>()
    var totalBytes = 0L

    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        val children = listChildren(current.file.path)
        if (current.depth >= limits.maximumDepth && children.isNotEmpty()) {
            error("This folder is deeper than the offline limit of ${limits.maximumDepth} levels.")
        }
        children.forEach { child ->
            FileOfflineKey(VALIDATION_ACCOUNT_ID, child.path)
            require(parentPath(child.path) == current.file.path) {
                "The server returned an item outside the folder being stored."
            }
            require(seenPaths.add(child.path)) {
                "The server returned the same folder item more than once."
            }
            require(seenPaths.size <= limits.maximumEntries) {
                "This folder contains more than ${limits.maximumEntries} offline items."
            }
            if (child.isDirectory) {
                directories += child.toOfflineDirectory()
                pending.add(PendingDirectory(child, current.depth + 1))
            } else {
                val size = child.size ?: error(
                    "Refresh ${child.name} before storing this folder because its size is unknown.",
                )
                require(size >= 0L) { "${child.name} has an invalid size." }
                require(!child.etag.isNullOrBlank()) {
                    "Refresh ${child.name} before storing this folder because its version is unknown."
                }
                require(totalBytes <= limits.maximumTotalBytes - size) {
                    "This folder exceeds the ${limits.maximumTotalBytes / (1024 * 1024)} MiB offline budget."
                }
                totalBytes = Math.addExact(totalBytes, size)
                files += child
            }
        }
    }

    return AndroidOfflineFolderInventory(
        rootPath = root.path,
        rootDisplayName = root.name,
        directories = directories.sortedBy(AndroidOfflineDirectory::path),
        files = files.sortedBy(NextcloudFile::path),
        totalBytes = totalBytes,
    )
}

internal fun planAndroidOfflineFolderPin(
    current: AndroidFileOfflinePersistedState,
    accountId: String,
    inventory: AndroidOfflineFolderInventory,
    nowEpochMillis: Long,
    localGenerationExists: (FileOfflineKey, String) -> Boolean,
): AndroidFileOfflinePersistedState {
    val previousRoot = current.folders.root(accountId, inventory.rootPath)
    val replacement = AndroidOfflineFolderRoot(
        accountId = accountId,
        rootPath = inventory.rootPath,
        rootDisplayName = inventory.rootDisplayName,
        directories = inventory.directories,
        filePaths = inventory.files.map(NextcloudFile::path),
    )
    val nextFolders = current.folders.copy(
        roots = current.folders.roots.filterNot {
            it.accountId == accountId && it.rootPath == inventory.rootPath
        } + replacement,
    )
    var nextQueue = current.queue
    inventory.files.forEach { file ->
        val key = FileOfflineKey(accountId, file.path)
        val observedRevision = nextQueue.record(key)?.localRevision?.takeIf {
            localGenerationExists(key, it)
        }
        nextQueue = planFileOfflineRequest(
            nextQueue,
            FileOfflineRequest.Pin(
                descriptor = FileOfflineDescriptor(
                    key = key,
                    displayName = file.name,
                    remoteEtag = requireNotNull(file.etag),
                    size = file.size,
                    mimeType = file.mimeType,
                ),
                observedLocalRevision = observedRevision,
            ),
            nowEpochMillis,
        )
    }
    previousRoot?.filePaths.orEmpty()
        .filter { oldPath ->
            val key = FileOfflineKey(accountId, oldPath)
            key !in nextFolders.directPins && !nextFolders.isCovered(key)
        }
        .forEach { oldPath ->
            val key = FileOfflineKey(accountId, oldPath)
            val record = nextQueue.record(key) ?: return@forEach
            val observedRevision = record.localRevision?.takeIf { localGenerationExists(key, it) }
            nextQueue = planFileOfflineRequest(
                nextQueue,
                FileOfflineRequest.Unpin(key, observedRevision),
                nowEpochMillis,
            )
        }
    return AndroidFileOfflinePersistedState(nextQueue, nextFolders)
}

internal fun planAndroidOfflineFolderUnpin(
    current: AndroidFileOfflinePersistedState,
    accountId: String,
    rootPath: String,
    nowEpochMillis: Long,
    localGenerationExists: (FileOfflineKey, String) -> Boolean,
): AndroidFileOfflinePersistedState {
    val removed = current.folders.root(accountId, rootPath) ?: return current
    val nextFolders = current.folders.copy(
        roots = current.folders.roots.filterNot {
            it.accountId == accountId && it.rootPath == rootPath
        },
    )
    var nextQueue = current.queue
    removed.filePaths
        .filter { path ->
            val key = FileOfflineKey(accountId, path)
            key !in nextFolders.directPins && !nextFolders.isCovered(key)
        }
        .forEach { path ->
            val key = FileOfflineKey(accountId, path)
            val record = nextQueue.record(key) ?: return@forEach
            val observedRevision = record.localRevision?.takeIf { localGenerationExists(key, it) }
            nextQueue = planFileOfflineRequest(
                nextQueue,
                FileOfflineRequest.Unpin(key, observedRevision),
                nowEpochMillis,
            )
        }
    return AndroidFileOfflinePersistedState(nextQueue, nextFolders)
}

private fun NextcloudFile.toOfflineDirectory(): AndroidOfflineDirectory {
    require(isDirectory)
    return AndroidOfflineDirectory(path, name, etag, lastModified, fileId)
}

private fun parentPath(path: String): String = path.substringBeforeLast('/', "")

private const val VALIDATION_ACCOUNT_ID = "offline-folder-validation"
