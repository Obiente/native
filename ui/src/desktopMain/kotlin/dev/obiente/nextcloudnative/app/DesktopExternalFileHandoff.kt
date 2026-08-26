package dev.obiente.nextcloudnative.app

import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
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
) {
    suspend fun launch(
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
            DesktopStagedExternalFile.Ready(stageDetachedCopy(file.name, content.bytes))
        }
        if (staged is DesktopStagedExternalFile.Rejected) return staged.result
        staged as DesktopStagedExternalFile.Ready
        return launchStaged(staged.file, action)
    }

    suspend fun launchStreamed(
        file: NextcloudFile,
        action: ExternalFileHandoffAction,
        capability: ExternalFileHandoffCapability,
        download: suspend (FileOutputStream, Long) -> DesktopDetachedDownload,
    ): ExternalFileHandoffResult {
        validateExternalFileHandoff(file, action, capability)?.let { return it }
        val staged = withContext(Dispatchers.IO) {
            stageStreamedCopy(
                sourceName = file.name,
                declaredByteCount = file.size,
                expectedEtag = requireSafeFileRangeEtag(requireNotNull(file.etag)),
                maximumBytes = Long.MAX_VALUE,
                download = download,
            )
        }
        return launchStaged(staged, action)
    }

    suspend fun launchDetached(
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
                sourceName = attachment.name,
                declaredByteCount = attachment.byteCount,
                maximumBytes = Long.MAX_VALUE,
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
            if (launchFile(staged)) {
                ExternalFileHandoffResult.Launched(action)
            } else {
                staged.parentFile?.deleteRecursively()
                ExternalFileHandoffResult.NoCompatibleApplication(action)
            }
        }

    private suspend fun stageStreamedCopy(
        sourceName: String,
        declaredByteCount: Long?,
        expectedEtag: String? = null,
        maximumBytes: Long,
        download: suspend (FileOutputStream, Long) -> DesktopDetachedDownload,
    ): File {
        require(maximumBytes > 0L)
        check(root.isDirectory || root.mkdirs()) { "Could not create the desktop external-file cache." }
        val canonicalRoot = root.canonicalFile
        pruneDesktopExternalFileCache(canonicalRoot, maximumBytes)
        val operationDirectory = File(canonicalRoot, UUID.randomUUID().toString())
        check(operationDirectory.mkdir()) { "Could not create a private desktop handoff directory." }
        check(operationDirectory.canonicalFile.parentFile == canonicalRoot) { "Unsafe desktop handoff directory." }
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
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath())
            }
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

    private fun stageDetachedCopy(sourceName: String, bytes: ByteArray): File {
        require(bytes.size.toLong() <= MAX_IN_MEMORY_EXTERNAL_FILE_HANDOFF_BYTES)
        check(root.isDirectory || root.mkdirs()) { "Could not create the desktop external-file cache." }
        val canonicalRoot = root.canonicalFile
        pruneDesktopExternalFileCache(canonicalRoot, bytes.size.toLong())
        val operationDirectory = File(canonicalRoot, UUID.randomUUID().toString())
        check(operationDirectory.mkdir()) { "Could not create a private desktop handoff directory." }
        check(operationDirectory.canonicalFile.parentFile == canonicalRoot) { "Unsafe desktop handoff directory." }
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
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath())
            }
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

    private sealed interface DesktopStagedExternalFile {
        data class Ready(val file: File) : DesktopStagedExternalFile
        data class Rejected(val result: ExternalFileHandoffResult.Rejected) : DesktopStagedExternalFile
    }
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
    val iterator = entries.iterator()
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

private fun desktopRecursiveFileBytes(file: File): Long = when {
    file.isFile -> file.length()
    file.isDirectory -> file.listFiles().orEmpty().sumOf(::desktopRecursiveFileBytes)
    else -> 0L
}

private fun saturatingDesktopFileBytes(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private const val MAX_DESKTOP_EXTERNAL_FILE_CACHE_BYTES = 256L * 1024L * 1024L
private const val DESKTOP_EXTERNAL_FILE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
