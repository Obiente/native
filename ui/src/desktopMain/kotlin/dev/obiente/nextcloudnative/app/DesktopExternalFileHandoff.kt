package dev.obiente.nextcloudnative.app

import java.awt.Desktop
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens a detached, read-only file generation through the desktop's registered application.
 *
 * The live DAV object is never mounted or exposed. The staged bytes must carry the exact ETag the
 * user selected in Files, and every copy lives in an evictable disposable XDG cache.
 */
internal class DesktopExternalFileHandoff(
    private val root: File = desktopExternalFileHandoffDirectory(),
    private val launchFile: (File) -> Boolean = ::launchDesktopFile,
    private val exportFile: (File) -> DesktopStagedFileExport = ::exportDesktopStagedFile,
    private val reservations: DesktopStagingSpaceReservations = sharedDesktopStagingSpaceReservations,
) {
    init {
        pruneLegacyDesktopExternalFileCache(root)
    }

    suspend fun launch(
        accountId: String,
        file: NextcloudFile,
        action: ExternalFileHandoffAction,
        capability: ExternalFileHandoffCapability,
        download: suspend (maximumBytes: Long) -> NextcloudFileContent,
    ): ExternalFileHandoffResult {
        validateExternalFileHandoff(file, action, capability)?.let { return it }
        val staged = withContext(Dispatchers.IO) {
            val content = download(capability.maximumInMemoryFileBytes)
            validateDownloadedExternalFile(file, content, capability.maximumInMemoryFileBytes)?.let { rejection ->
                return@withContext DesktopStagedExternalFile.Rejected(rejection)
            }
            DesktopStagedExternalFile.Ready(stageDetachedCopy(accountId, file.name, content.bytes))
        }
        if (staged is DesktopStagedExternalFile.Rejected) return staged.result
        staged as DesktopStagedExternalFile.Ready
        return launchStaged(staged.file, action)
    }

    suspend fun launchStreamed(
        accountId: String,
        file: NextcloudFile,
        action: ExternalFileHandoffAction,
        capability: ExternalFileHandoffCapability,
        download: suspend (FileOutputStream, Long) -> DesktopDetachedDownload,
    ): ExternalFileHandoffResult {
        validateExternalFileHandoff(file, action, capability)?.let { return it }
        val staged = withContext(Dispatchers.IO) {
            stageStreamedCopy(
                accountId = accountId,
                sourceName = file.name,
                declaredByteCount = file.size,
                expectedEtag = requireSafeFileRangeEtag(requireNotNull(file.etag)),
                download = download,
            )
        }
        return launchStaged(staged, action)
    }

    suspend fun launchDetached(
        accountId: String,
        attachment: DeckAttachment,
        action: ExternalFileHandoffAction,
        capability: ExternalFileHandoffCapability,
        download: suspend (
            output: FileOutputStream,
            maximumBytes: Long,
        ) -> DesktopDetachedDownload,
    ): ExternalFileHandoffResult {
        validateDeckAttachmentHandoff(attachment, action, capability)?.let { return it }
        val staged = withContext(Dispatchers.IO) {
            stageStreamedCopy(
                accountId = accountId,
                sourceName = attachment.name,
                declaredByteCount = attachment.byteCount,
                download = download,
            )
        }
        return launchStaged(staged, action)
    }

    private suspend fun launchStaged(
        staged: File,
        action: ExternalFileHandoffAction,
    ): ExternalFileHandoffResult =
        withContext(Dispatchers.IO) {
            when (action) {
                ExternalFileHandoffAction.OpenWith -> {
                    if (launchFile(staged)) {
                        ExternalFileHandoffResult.Launched(action)
                    } else {
                        staged.parentFile?.deleteRecursively()
                        ExternalFileHandoffResult.NoCompatibleApplication(action)
                    }
                }
                ExternalFileHandoffAction.Share -> try {
                    when (exportFile(staged)) {
                        DesktopStagedFileExport.Exported -> ExternalFileHandoffResult.Launched(action)
                        DesktopStagedFileExport.Cancelled -> ExternalFileHandoffResult.Cancelled(action)
                        DesktopStagedFileExport.Unavailable ->
                            ExternalFileHandoffResult.NoCompatibleApplication(action)
                    }
                } finally {
                    staged.parentFile?.deleteRecursively()
                }
            }
        }

    private suspend fun stageStreamedCopy(
        accountId: String,
        sourceName: String,
        declaredByteCount: Long?,
        expectedEtag: String? = null,
        download: suspend (FileOutputStream, Long) -> DesktopDetachedDownload,
    ): File {
        val canonicalRoot = prepareAccountRoot(accountId)
        pruneDesktopExternalFileCache(canonicalRoot, declaredByteCount ?: 0L)
        val reservation = reservations.reserve(
            root = canonicalRoot,
            declaredByteCount = declaredByteCount,
            reserveBytes = STAGED_FILE_FREE_SPACE_RESERVE_BYTES,
        )
        reservation.use {
            val maximumBytes = reservation.maximumBytes
            val operationDirectory = File(canonicalRoot, UUID.randomUUID().toString())
            check(operationDirectory.mkdir()) { "Could not create a private desktop handoff directory." }
            check(operationDirectory.canonicalFile.parentFile == canonicalRoot) {
                "Unsafe desktop handoff directory."
            }
            val target = File(operationDirectory, sanitizeExternalFileName(sourceName))
            check(target.canonicalFile.parentFile == operationDirectory.canonicalFile) {
                "Unsafe desktop handoff filename."
            }
            val temporary = File.createTempFile("payload-", ".tmp", operationDirectory)
            try {
                val downloaded = FileOutputStream(temporary).use { output ->
                    download(output, maximumBytes).also {
                        output.fd.sync()
                    }
                }
                check(downloaded.byteCount in 0L..maximumBytes)
                expectedEtag?.let { expected ->
                    check(downloaded.etag == expected) {
                        "The file changed while it was being prepared. Refresh and try again."
                    }
                }
                verifyDownloadedDeckAttachmentSize(declaredByteCount, downloaded.byteCount)
                check(temporary.length() == downloaded.byteCount) {
                    "The desktop attachment cache copy is incomplete."
                }
                moveAtomicallyOrReplace(temporary, target, replaceExisting = false)
                check(target.isFile && target.length() == downloaded.byteCount) {
                    "Could not publish the desktop attachment cache copy."
                }
                check(target.setWritable(false, false) || !target.canWrite()) {
                    "Could not make the detached desktop attachment read-only."
                }
                return target
            } catch (failure: Throwable) {
                temporary.delete()
                operationDirectory.deleteRecursively()
                throw failure
            }
        }
    }

    private fun stageDetachedCopy(accountId: String, sourceName: String, bytes: ByteArray): File {
        require(bytes.size.toLong() <= MAX_IN_MEMORY_EXTERNAL_FILE_HANDOFF_BYTES)
        val canonicalRoot = prepareAccountRoot(accountId)
        pruneDesktopExternalFileCache(canonicalRoot, bytes.size.toLong())
        reservations.reserve(
            root = canonicalRoot,
            declaredByteCount = bytes.size.toLong(),
            reserveBytes = STAGED_FILE_FREE_SPACE_RESERVE_BYTES,
        ).use {
            val operationDirectory = File(canonicalRoot, UUID.randomUUID().toString())
            check(operationDirectory.mkdir()) { "Could not create a private desktop handoff directory." }
            check(operationDirectory.canonicalFile.parentFile == canonicalRoot) {
                "Unsafe desktop handoff directory."
            }
            val target = File(operationDirectory, sanitizeExternalFileName(sourceName))
            check(target.canonicalFile.parentFile == operationDirectory.canonicalFile) {
                "Unsafe desktop handoff filename."
            }
            val temporary = File.createTempFile("payload-", ".tmp", operationDirectory)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                check(temporary.length() == bytes.size.toLong()) { "The desktop handoff copy is incomplete." }
                moveAtomicallyOrReplace(temporary, target, replaceExisting = false)
                check(target.isFile && target.length() == bytes.size.toLong()) {
                    "Could not publish the desktop handoff copy."
                }
                check(target.setWritable(false, false) || !target.canWrite()) {
                    "Could not make the detached desktop copy read-only."
                }
                return target
            } catch (failure: Throwable) {
                temporary.delete()
                operationDirectory.deleteRecursively()
                throw failure
            }
        }
    }

    fun removeAccount(accountId: String) {
        requireDesktopExternalFileHandoffAccountId(accountId)
        pruneLegacyDesktopExternalFileCache(root)
        val rootPath = root.toPath().toAbsolutePath().normalize()
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) return
        check(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(rootPath)) {
            "The desktop external-file cache root is not a safe directory."
        }
        val accountPath = rootPath.resolve(accountId)
        if (!Files.exists(accountPath, LinkOption.NOFOLLOW_LINKS)) return
        check(accountPath.parent == rootPath && !Files.isSymbolicLink(accountPath)) {
            "Unsafe desktop external-file account directory."
        }
        check(Files.isDirectory(accountPath, LinkOption.NOFOLLOW_LINKS)) {
            "The desktop external-file account entry is not a directory."
        }
        check(accountPath.toFile().deleteRecursively() && !Files.exists(accountPath, LinkOption.NOFOLLOW_LINKS)) {
            "Could not clear this account's desktop external-file copies."
        }
    }

    private fun prepareAccountRoot(accountId: String): File {
        requireDesktopExternalFileHandoffAccountId(accountId)
        pruneLegacyDesktopExternalFileCache(root)
        check(root.isDirectory || root.mkdirs()) { "Could not create the desktop external-file cache." }
        val canonicalRoot = root.canonicalFile
        val accountRoot = File(canonicalRoot, accountId)
        check(accountRoot.isDirectory || accountRoot.mkdir()) {
            "Could not create the desktop external-file account cache."
        }
        check(accountRoot.canonicalFile.parentFile == canonicalRoot) {
            "Unsafe desktop external-file account directory."
        }
        return accountRoot.canonicalFile
    }

    private sealed interface DesktopStagedExternalFile {
        data class Ready(val file: File) : DesktopStagedExternalFile
        data class Rejected(val result: ExternalFileHandoffResult.Rejected) : DesktopStagedExternalFile
    }
}

internal suspend fun <Result> DesktopAccountOperationGuard.withExternalFileHandoffSession(
    expectedSession: NextcloudSession,
    resolveSession: suspend () -> NextcloudSession?,
    handoff: suspend () -> Result,
): Result = withAccountPrivateStatePublication(
    expectedSession = expectedSession,
    resolveSession = resolveSession,
    unavailable = { error("The account changed before the external file copy could be published.") },
    publish = handoff,
)

private fun requireDesktopExternalFileHandoffAccountId(accountId: String) {
    require(accountId.length == 64 && accountId.all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }) { "The desktop external-file account identity is invalid." }
}

internal enum class DesktopStagedFileExport {
    Exported,
    Cancelled,
    Unavailable,
}

internal data class DesktopDetachedDownload(
    val byteCount: Long,
    val etag: String? = null,
)

internal fun desktopExternalFileHandoffDirectory(): File {
    val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
    val cacheRoot = xdgCache?.let(::File) ?: File(System.getProperty("user.home"), ".cache")
    return File(cacheRoot, "nextcloud-native/external-open")
}

internal fun pruneLegacyDesktopExternalFileCache(
    root: File,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val rootPath = root.toPath().toAbsolutePath().normalize()
    if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) return
    if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(rootPath)) return
    Files.newDirectoryStream(rootPath).use { entries ->
        entries.forEach { entry ->
            val name = entry.fileName.toString()
            if (!name.matches(LEGACY_EXTERNAL_FILE_OPERATION_DIRECTORY)) return@forEach
            if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)) return@forEach
            val modified = Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toMillis()
            if (nowMillis >= modified && nowMillis - modified > DESKTOP_EXTERNAL_FILE_MAX_AGE_MILLIS) {
                entry.toFile().deleteRecursively()
            }
        }
    }
}

internal fun pruneDesktopExternalFileCache(
    root: File,
    requiredBytes: Long,
    nowMillis: Long = System.currentTimeMillis(),
) {
    require(root.isDirectory) { "The desktop external-file cache root is not a directory." }
    require(requiredBytes >= 0L)
    val entries = root.listFiles().orEmpty().sortedBy(File::lastModified).toMutableList()
    entries.filter { nowMillis - it.lastModified() > DESKTOP_EXTERNAL_FILE_MAX_AGE_MILLIS }.forEach { expired ->
        expired.deleteRecursively()
        entries.remove(expired)
    }
    var storedBytes = entries.fold(0L) { total, entry ->
        saturatingDesktopFileBytes(total, desktopRecursiveFileBytes(entry))
    }
    val retainedBeforeCopy = if (requiredBytes >= MAX_DESKTOP_EXTERNAL_FILE_CACHE_BYTES) {
        0L
    } else {
        MAX_DESKTOP_EXTERNAL_FILE_CACHE_BYTES - requiredBytes
    }
    val iterator = entries.filter { entry ->
        nowMillis >= entry.lastModified() &&
            nowMillis - entry.lastModified() >= DESKTOP_EXTERNAL_FILE_MINIMUM_RETENTION_MILLIS
    }.iterator()
    while (storedBytes > retainedBeforeCopy && iterator.hasNext()) {
        val oldest = iterator.next()
        val bytes = desktopRecursiveFileBytes(oldest)
        if (oldest.deleteRecursively()) storedBytes = (storedBytes - bytes).coerceAtLeast(0L)
    }
}

private fun launchDesktopFile(file: File): Boolean {
    if (Desktop.isDesktopSupported()) {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.OPEN)) {
            return runCatching {
                desktop.open(file)
                true
            }.getOrDefault(false)
        }
    }
    return runCatching {
        ProcessBuilder("xdg-open", file.absolutePath).start()
        true
    }.getOrDefault(false)
}

private fun exportDesktopStagedFile(file: File): DesktopStagedFileExport {
    if (GraphicsEnvironment.isHeadless()) return DesktopStagedFileExport.Unavailable
    val destination = chooseDesktopDetachedExportDestination(file.name)
        ?: return DesktopStagedFileExport.Cancelled
    return publishDesktopStagedFile(file, destination.absoluteFile)
}

internal fun publishDesktopStagedFile(
    file: File,
    destination: File,
    reservations: JvmStagingSpaceReservations = sharedJvmStagingSpaceReservations,
): DesktopStagedFileExport {
    val parent = destination.absoluteFile.parentFile
        ?.takeIf(File::isDirectory)
        ?: return DesktopStagedFileExport.Unavailable
    val sourceStore = Files.getFileStore(file.toPath())
    val destinationStore = Files.getFileStore(parent.toPath())
    if (sourceStore == destinationStore) {
        moveAtomicallyOrReplace(file, destination.absoluteFile, replaceExisting = true)
        check(destination.setWritable(true, true) || destination.canWrite()) {
            "Could not make the exported file writable."
        }
        return DesktopStagedFileExport.Exported
    }
    val reservation = reservations.reserve(
        storageKey = jvmStagingStorageKey(parent),
        usableBytes = parent.usableSpace.coerceAtLeast(0L),
        declaredByteCount = file.length(),
        reserveBytes = STAGED_FILE_FREE_SPACE_RESERVE_BYTES,
    )
    return reservation.use {
        val temporary = File.createTempFile(".nextcloud-native-export-", ".tmp", parent)
        try {
            Files.copy(file.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            RandomAccessFile(temporary, "rw").use { staged -> staged.fd.sync() }
            moveAtomicallyOrReplace(temporary, destination.absoluteFile, replaceExisting = true)
            DesktopStagedFileExport.Exported
        } finally {
            temporary.delete()
        }
    }
}

private fun chooseDesktopDetachedExportDestination(fileName: String): File? {
    val selected = AtomicReference<File?>()
    val choose = {
        val dialog = FileDialog(null as Frame?, "Save a copy", FileDialog.SAVE)
        try {
            dialog.file = sanitizeExternalFileName(fileName)
            dialog.isVisible = true
            val directory = dialog.directory
            val name = dialog.file
            if (!directory.isNullOrBlank() && !name.isNullOrBlank()) selected.set(File(directory, name))
        } finally {
            dialog.dispose()
        }
    }
    if (EventQueue.isDispatchThread()) choose() else EventQueue.invokeAndWait { choose() }
    return selected.get()
}

private fun moveAtomicallyOrReplace(source: File, destination: File, replaceExisting: Boolean) {
    val options = if (replaceExisting) {
        arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } else {
        arrayOf(StandardCopyOption.ATOMIC_MOVE)
    }
    try {
        Files.move(source.toPath(), destination.toPath(), *options)
    } catch (_: AtomicMoveNotSupportedException) {
        if (replaceExisting) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.move(source.toPath(), destination.toPath())
        }
    }
}

private fun desktopRecursiveFileBytes(file: File): Long = when {
    file.isFile -> file.length()
    file.isDirectory -> file.listFiles().orEmpty().sumOf(::desktopRecursiveFileBytes)
    else -> 0L
}

private const val DESKTOP_EXTERNAL_FILE_MINIMUM_RETENTION_MILLIS = 60L * 60L * 1000L
private val LEGACY_EXTERNAL_FILE_OPERATION_DIRECTORY =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private fun saturatingDesktopFileBytes(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private const val MAX_DESKTOP_EXTERNAL_FILE_CACHE_BYTES = 256L * 1024L * 1024L
private const val DESKTOP_EXTERNAL_FILE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
