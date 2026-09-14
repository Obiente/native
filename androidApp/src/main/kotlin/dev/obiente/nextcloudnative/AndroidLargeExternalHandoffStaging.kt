package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.sanitizeExternalFileName
import dev.obiente.nextcloudnative.app.sanitizeExternalMimeType
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Generation admission covers filesystem creation and promotion, but never the network download. */
internal suspend fun stageAndroidLargeExternalHandoffCopy(
    cacheDirectory: File,
    file: NextcloudFile,
    expectedBytes: Long,
    generation: AndroidExternalFileHandoffGeneration,
    download: suspend (FileOutputStream, Long) -> AndroidDetachedDownload,
): AndroidExternalStagedCopy {
    val prepared = AndroidExternalFileHandoffRegistry.withGeneration(generation) {
        val root = File(cacheDirectory, EXTERNAL_LARGE_SHARE_CACHE_DIRECTORY)
        check(root.isDirectory || root.mkdirs()) { "Could not create the large-file handoff cache." }
        val canonicalRoot = root.canonicalFile
        prepareLargeExternalShareCache(canonicalRoot, expectedBytes,
            protectedDirectoryNames = AndroidExternalFileHandoffRegistry.activeManagedContentDirectoryNames())
        val available = androidLargeExternalHandoffAvailableBytes(canonicalRoot, canonicalRoot.usableSpace.coerceAtLeast(0L))
        check(androidLargeExternalHandoffFitsCapacity(expectedBytes, available)) {
            "There is not enough free space for the temporary external-file copy."
        }
        val directory = createLargeExternalShareOperationDirectory(canonicalRoot, expectedBytes)
        try {
            val target = File(directory, sanitizeExternalFileName(file.name))
            check(target.canonicalFile.parentFile == directory.canonicalFile) { "Unsafe external-file name." }
            val temporary = File.createTempFile("payload-", ".tmp", directory)
            PreparedLargeCopy(directory, target, temporary, FileOutputStream(temporary))
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }
    try {
        val downloaded = prepared.output.use { output -> download(output, expectedBytes).also { output.fd.sync() } }
        check(downloaded.byteCount == expectedBytes && prepared.temporary.length() == expectedBytes) {
            "The temporary external-file copy is incomplete."
        }
        return AndroidExternalFileHandoffRegistry.withGeneration(generation) {
            RandomAccessFile(File(prepared.directory, LARGE_EXTERNAL_SHARE_RESERVATION_FILE), "rw").use { marker ->
                marker.setLength(0L)
                marker.fd.sync()
            }
            check(!prepared.target.exists() && prepared.temporary.renameTo(prepared.target)) {
                "Could not publish the temporary external-file copy."
            }
            check(prepared.target.setWritable(false, true) || !prepared.target.canWrite()) {
                "Could not make the temporary external-file copy read-only."
            }
            val declared = sanitizeExternalMimeType(file.mimeType)
            val mime = declared.takeUnless { it == "application/octet-stream" } ?: sanitizeExternalMimeType(downloaded.mimeType)
            AndroidExternalStagedCopy(prepared.target, mime)
        }
    } catch (failure: Throwable) {
        try {
            try { prepared.output.close() } finally { prepared.directory.deleteRecursively() }
        } catch (cleanup: Exception) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

private data class PreparedLargeCopy(val directory: File, val target: File, val temporary: File, val output: FileOutputStream)
