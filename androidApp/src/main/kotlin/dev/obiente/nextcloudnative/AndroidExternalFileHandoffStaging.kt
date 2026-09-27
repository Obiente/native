package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.jvmStagingStorageKey
import dev.obiente.nextcloudnative.app.sanitizeExternalFileName
import dev.obiente.nextcloudnative.app.sanitizeExternalMimeType
import dev.obiente.nextcloudnative.app.sharedJvmStagingSpaceReservations
import dev.obiente.nextcloudnative.app.verifyDownloadedDeckAttachmentSize
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal data class AndroidExternalStagedCopy(val file: File, val mimeType: String)

/** Private staging and publication share the producer's account-cleanup generation. */
internal fun stageAndroidExternalHandoffBytes(
    cacheDirectory: File, sourceName: String, bytes: ByteArray, generation: AndroidExternalFileHandoffGeneration,
): File = AndroidExternalFileHandoffRegistry.withGeneration(generation) {
    val root = File(cacheDirectory, EXTERNAL_SHARE_CACHE_DIRECTORY)
    check(root.isDirectory || root.mkdirs()) { "Could not create the private external-share cache." }
    val canonicalRoot = root.canonicalFile
    pruneExternalShareCache(canonicalRoot, bytes.size.toLong())
    val staging = prepareStaging(canonicalRoot, sourceName)
    try {
        staging.output.use { output -> output.write(bytes); output.fd.sync() }
        check(staging.temporary.length() == bytes.size.toLong()) { "The external-share cache copy is incomplete." }
        publishStaging(staging)
    } catch (failure: Throwable) {
        staging.discardAfterFailure(failure)
        throw failure
    }
}

internal suspend fun stageAndroidExternalHandoffStream(
    cacheDirectory: File,
    sourceName: String,
    declaredMimeType: String?,
    declaredByteCount: Long?,
    generation: AndroidExternalFileHandoffGeneration,
    download: suspend (FileOutputStream, Long) -> AndroidDetachedDownload,
): AndroidExternalStagedCopy {
    val canonicalRoot = AndroidExternalFileHandoffRegistry.withGeneration(generation) {
        val root = File(cacheDirectory, EXTERNAL_SHARE_CACHE_DIRECTORY)
        check(root.isDirectory || root.mkdirs()) { "Could not create the private external-share cache." }
        root.canonicalFile.also { pruneExternalShareCache(it, declaredByteCount ?: 0L) }
    }
    val reservation = sharedJvmStagingSpaceReservations.reserve(
        storageKey = jvmStagingStorageKey(canonicalRoot), usableBytes = canonicalRoot.usableSpace.coerceAtLeast(0L),
        declaredByteCount = declaredByteCount,
        reserveBytes = dev.obiente.nextcloudnative.app.STAGED_FILE_FREE_SPACE_RESERVE_BYTES,
    )
    reservation.use {
        val staging = AndroidExternalFileHandoffRegistry.withGeneration(generation) {
            prepareStaging(canonicalRoot, sourceName)
        }
        try {
            val downloaded = staging.output.use { output ->
                download(output, reservation.maximumBytes).also { output.fd.sync() }
            }
            check(downloaded.byteCount in 0L..reservation.maximumBytes)
            verifyDownloadedDeckAttachmentSize(declaredByteCount, downloaded.byteCount)
            check(staging.temporary.length() == downloaded.byteCount) { "The external-share cache copy is incomplete." }
            val target = AndroidExternalFileHandoffRegistry.withGeneration(generation) { publishStaging(staging) }
            val declared = sanitizeExternalMimeType(declaredMimeType)
            val mime = declared.takeUnless { it == "application/octet-stream" } ?: sanitizeExternalMimeType(downloaded.mimeType)
            return AndroidExternalStagedCopy(target, mime)
        } catch (failure: Throwable) {
            staging.discardAfterFailure(failure)
            throw failure
        }
    }
}

private data class PreparedStaging(val directory: File, val target: File, val temporary: File, val output: FileOutputStream) {
    fun discardAfterFailure(failure: Throwable) {
        try {
            try { output.close() } finally { directory.deleteRecursively() }
        } catch (cleanup: Exception) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
    }
}

private fun prepareStaging(canonicalRoot: File, sourceName: String): PreparedStaging {
    val directory = File(canonicalRoot, UUID.randomUUID().toString())
    check(directory.mkdir()) { "Could not create a private external-share directory." }
    try {
        check(directory.canonicalFile.parentFile == canonicalRoot) { "Unsafe external-share directory." }
        val target = File(directory, sanitizeExternalFileName(sourceName))
        check(target.canonicalFile.parentFile == directory.canonicalFile) { "Unsafe external-share filename." }
        val temporary = File.createTempFile("payload-", ".tmp", directory)
        return PreparedStaging(directory, target, temporary, FileOutputStream(temporary))
    } catch (failure: Throwable) {
        directory.deleteRecursively()
        throw failure
    }
}

private fun publishStaging(staging: PreparedStaging): File {
    check(!staging.target.exists() && staging.temporary.renameTo(staging.target)) { "Could not publish the external-share cache copy." }
    check(staging.target.setWritable(false, true) || !staging.target.canWrite()) { "Could not make the external-share copy read-only." }
    return staging.target
}
