package dev.obiente.nextcloudnative

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import dev.obiente.nextcloudnative.app.DeckAttachment
import dev.obiente.nextcloudnative.app.ExternalFileHandoffAction
import dev.obiente.nextcloudnative.app.ExternalFileHandoffCapability
import dev.obiente.nextcloudnative.app.ExternalFileHandoffResult
import dev.obiente.nextcloudnative.app.MAX_EXTERNAL_FILE_HANDOFF_BYTES
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudFileContent
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.canUseSeekableRemoteHandoff
import dev.obiente.nextcloudnative.app.sanitizeExternalFileName
import dev.obiente.nextcloudnative.app.sanitizeExternalMimeType
import dev.obiente.nextcloudnative.app.validateDeckAttachmentHandoff
import dev.obiente.nextcloudnative.app.validateDownloadedExternalFile
import dev.obiente.nextcloudnative.app.validateExternalFileHandoff
import dev.obiente.nextcloudnative.app.verifyDownloadedDeckAttachmentSize
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class AndroidExternalFileHandoff(private val context: Context) {
    init {
        AndroidExternalFileHandoffRegistry.bind(AndroidExternalFileHandoffStore(context))
    }

    suspend fun launchRemote(
        session: NextcloudSession,
        file: NextcloudFile,
        action: ExternalFileHandoffAction,
        capability: ExternalFileHandoffCapability,
    ): ExternalFileHandoffResult {
        validateExternalFileHandoff(file, action, capability)?.let { return it }
        if (!file.canUseSeekableRemoteHandoff(capability)) {
            return ExternalFileHandoffResult.Unsupported(
                "This file does not provide the size and version needed for seekable streaming.",
            )
        }
        return registerAndLaunchRemote(session, file, action)
    }

    private suspend fun registerAndLaunchRemote(
        session: NextcloudSession,
        file: NextcloudFile,
        action: ExternalFileHandoffAction,
        staged: StagedExternalFile.Ready? = null,
    ): ExternalFileHandoffResult {
        val registeredFile = externalHandoffFile(file, staged?.mimeType)
        val record = try {
            withContext(Dispatchers.IO) {
                AndroidExternalFileHandoffRegistry.register(session, registeredFile)
            }
        } catch (_: AndroidExternalFileHandoffStoreException) {
            staged?.file?.parentFile?.deleteRecursively()
            return ExternalFileHandoffResult.Unsupported(
                "Android could not persist this temporary file handoff. Check available storage and try again.",
            )
        } catch (_: IllegalStateException) {
            staged?.file?.parentFile?.deleteRecursively()
            return ExternalFileHandoffResult.Unsupported(
                "Too many files are currently open in other apps. Close one and try again.",
            )
        }
        if (staged != null) {
            try {
                withContext(Dispatchers.IO) {
                    publishLargeExternalHandoffContent(staged.file, record.documentId)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { AndroidExternalFileHandoffRegistry.revoke(record.documentId) }
                    staged.file.parentFile?.deleteRecursively()
                }
                throw cancelled
            } catch (_: Exception) {
                withContext(Dispatchers.IO) {
                    runCatching { AndroidExternalFileHandoffRegistry.revoke(record.documentId) }
                    staged.file.parentFile?.deleteRecursively()
                }
                return ExternalFileHandoffResult.Unsupported(
                    "Android could not publish the temporary file handoff. Check available storage and try again.",
                )
            }
        }
        val authority = nextcloudDocumentsAuthority(context.packageName)
        val uri = try {
            DocumentsContract.buildDocumentUri(authority, record.documentId)
        } catch (_: IllegalArgumentException) {
            withContext(Dispatchers.IO) {
                AndroidExternalFileHandoffRegistry.revoke(record.documentId)
            }
            return ExternalFileHandoffResult.Unsupported(
                "The scoped external-file provider is unavailable.",
            )
        }
        if (uri.scheme != "content" || uri.authority != authority) {
            withContext(Dispatchers.IO) {
                AndroidExternalFileHandoffRegistry.revoke(record.documentId)
            }
            return ExternalFileHandoffResult.Unsupported(
                "The scoped external-file provider is unavailable.",
            )
        }
        return launchUri(
            uri = uri,
            mimeType = sanitizeExternalMimeType(record.file.mimeType),
            displayName = sanitizeExternalFileName(record.file.name),
            action = action,
            onFailure = {
                withContext(Dispatchers.IO) {
                    AndroidExternalFileHandoffRegistry.revoke(record.documentId)
                }
            },
        )
    }

    suspend fun launch(
        file: NextcloudFile,
        action: ExternalFileHandoffAction,
        capability: ExternalFileHandoffCapability,
        download: suspend (maximumBytes: Long) -> NextcloudFileContent,
    ): ExternalFileHandoffResult {
        validateExternalFileHandoff(file, action, capability)?.let { return it }

        val staged = withContext(Dispatchers.IO) {
            val content = download(capability.maximumFileBytes)
            val rejection = validateDownloadedExternalFile(file, content, capability.maximumFileBytes)
            if (rejection != null) {
                return@withContext StagedExternalFile.Rejected(
                    rejection,
                )
            }
            val stagedFile = stagePrivateCopy(file.name, content.bytes)
            val declaredMime = sanitizeExternalMimeType(file.mimeType)
            val responseMime = sanitizeExternalMimeType(content.mimeType)
            val mimeType = declaredMime.takeUnless { it == GENERIC_MIME_TYPE } ?: responseMime
            StagedExternalFile.Ready(stagedFile, mimeType)
        }
        if (staged is StagedExternalFile.Rejected) return staged.result
        staged as StagedExternalFile.Ready
        return launchStaged(staged, action)
    }

    suspend fun launchLargeStagedRemote(
        session: NextcloudSession,
        file: NextcloudFile,
        action: ExternalFileHandoffAction,
        capability: ExternalFileHandoffCapability,
        download: suspend (FileOutputStream, Long) -> AndroidDetachedDownload,
    ): ExternalFileHandoffResult {
        validateExternalFileHandoff(file, action, capability)?.let { return it }
        val expectedBytes = file.size?.takeIf { it > 0L } ?: return ExternalFileHandoffResult.Unsupported(
            "This file does not provide the size needed for a safe temporary copy.",
        )
        val staged = try {
            withContext(Dispatchers.IO) {
                stageLargeRemoteCopy(file, expectedBytes, download)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ExternalFileHandoffResult.Unsupported(
                "The file could not be cached for another app. Check the connection and available storage, then try again.",
            )
        }
        return registerAndLaunchRemote(session, file, action, staged)
    }

    suspend fun launchDetached(
        attachment: DeckAttachment,
        action: ExternalFileHandoffAction,
        capability: ExternalFileHandoffCapability,
        download: suspend (
            output: FileOutputStream,
            maximumBytes: Long,
        ) -> AndroidDetachedDownload,
    ): ExternalFileHandoffResult {
        validateDeckAttachmentHandoff(attachment, action, capability)?.let { return it }
        val staged = withContext(Dispatchers.IO) {
            stageStreamedCopy(
                sourceName = attachment.name,
                declaredMimeType = attachment.mimeType,
                declaredByteCount = attachment.byteCount,
                maximumBytes = capability.maximumFileBytes,
                download = download,
            )
        }
        return launchStaged(staged, action)
    }

    private suspend fun launchStaged(
        staged: StagedExternalFile.Ready,
        action: ExternalFileHandoffAction,
    ): ExternalFileHandoffResult {
        val authority = context.packageName + EXTERNAL_FILE_PROVIDER_AUTHORITY_SUFFIX
        val uri = try {
            FileProvider.getUriForFile(context, authority, staged.file)
        } catch (_: IllegalArgumentException) {
            staged.file.parentFile?.deleteRecursively()
            return ExternalFileHandoffResult.Unsupported(
                "The private external-file provider is unavailable.",
            )
        }
        if (uri.scheme != "content" || uri.authority != authority) {
            staged.file.parentFile?.deleteRecursively()
            return ExternalFileHandoffResult.Unsupported(
                "External files must be exposed through the private app provider.",
            )
        }
        return launchUri(
            uri = uri,
            mimeType = staged.mimeType,
            displayName = staged.file.name,
            action = action,
            onFailure = { staged.file.parentFile?.deleteRecursively() },
        )
    }

    private suspend fun launchUri(
        uri: Uri,
        mimeType: String,
        displayName: String,
        action: ExternalFileHandoffAction,
        onFailure: suspend () -> Unit,
    ): ExternalFileHandoffResult {
        return withContext(Dispatchers.Main) {
            try {
                context.startActivity(buildChooser(action, uri, mimeType, displayName))
                ExternalFileHandoffResult.Launched(action)
            } catch (_: ActivityNotFoundException) {
                onFailure()
                ExternalFileHandoffResult.NoCompatibleApplication(action)
            } catch (_: SecurityException) {
                onFailure()
                ExternalFileHandoffResult.Unsupported(
                    "Android could not grant read access to the selected application.",
                )
            }
        }
    }

    private suspend fun stageStreamedCopy(
        sourceName: String,
        declaredMimeType: String?,
        declaredByteCount: Long?,
        maximumBytes: Long,
        download: suspend (FileOutputStream, Long) -> AndroidDetachedDownload,
    ): StagedExternalFile.Ready {
        require(maximumBytes in 1L..MAX_EXTERNAL_FILE_HANDOFF_BYTES) {
            "The external attachment limit is outside the supported range."
        }
        val root = File(context.cacheDir, EXTERNAL_SHARE_CACHE_DIRECTORY)
        check(root.isDirectory || root.mkdirs()) { "Could not create the private external-share cache." }
        val canonicalRoot = root.canonicalFile
        pruneExternalShareCache(canonicalRoot, maximumBytes)

        val operationDirectory = File(canonicalRoot, UUID.randomUUID().toString())
        check(operationDirectory.mkdir()) { "Could not create a private external-share directory." }
        check(operationDirectory.canonicalFile.parentFile == canonicalRoot) { "Unsafe external-share directory." }
        val target = File(operationDirectory, sanitizeExternalFileName(sourceName))
        check(target.canonicalFile.parentFile == operationDirectory.canonicalFile) { "Unsafe external-share filename." }
        val temporary = File.createTempFile("payload-", ".tmp", operationDirectory)
        try {
            val downloaded = FileOutputStream(temporary).use { output ->
                download(output, maximumBytes).also {
                    output.fd.sync()
                }
            }
            check(downloaded.byteCount in 0L..maximumBytes) {
                "The downloaded attachment is larger than the external handoff limit."
            }
            verifyDownloadedDeckAttachmentSize(declaredByteCount, downloaded.byteCount)
            check(temporary.length() == downloaded.byteCount) {
                "The external-share cache copy is incomplete."
            }
            check(!target.exists() && temporary.renameTo(target)) {
                "Could not publish the external-share cache copy."
            }
            check(target.setWritable(false, true) || !target.canWrite()) {
                "Could not make the detached attachment read-only."
            }
            val declaredMime = sanitizeExternalMimeType(declaredMimeType)
            val responseMime = sanitizeExternalMimeType(downloaded.mimeType)
            val mimeType = declaredMime.takeUnless { it == GENERIC_MIME_TYPE } ?: responseMime
            return StagedExternalFile.Ready(target, mimeType)
        } catch (failure: Throwable) {
            temporary.delete()
            operationDirectory.deleteRecursively()
            throw failure
        }
    }

    private suspend fun stageLargeRemoteCopy(
        file: NextcloudFile,
        expectedBytes: Long,
        download: suspend (FileOutputStream, Long) -> AndroidDetachedDownload,
    ): StagedExternalFile.Ready {
        val root = File(context.cacheDir, EXTERNAL_LARGE_SHARE_CACHE_DIRECTORY)
        val operationDirectory = synchronized(LARGE_EXTERNAL_SHARE_CACHE_LOCK) {
            check(root.isDirectory || root.mkdirs()) { "Could not create the large-file handoff cache." }
            val canonicalRoot = root.canonicalFile
            prepareLargeExternalShareCache(
                root = canonicalRoot,
                requiredBytes = expectedBytes,
                protectedDirectoryNames = AndroidExternalFileHandoffRegistry.activeManagedContentDirectoryNames(),
            )
            val availableAfterReservations = androidLargeExternalHandoffAvailableBytes(
                root = canonicalRoot,
                availableBytes = canonicalRoot.usableSpace.coerceAtLeast(0L),
            )
            check(
                androidLargeExternalHandoffFitsCapacity(
                    requiredBytes = expectedBytes,
                    availableBytes = availableAfterReservations,
                ),
            ) { "There is not enough free space for the temporary external-file copy." }
            createLargeExternalShareOperationDirectory(canonicalRoot, expectedBytes)
        }
        val target = File(operationDirectory, sanitizeExternalFileName(file.name))
        check(target.canonicalFile.parentFile == operationDirectory.canonicalFile) { "Unsafe external-file name." }
        val temporary = File.createTempFile("payload-", ".tmp", operationDirectory)
        try {
            val downloaded = FileOutputStream(temporary).use { output ->
                download(output, expectedBytes).also { output.fd.sync() }
            }
            check(downloaded.byteCount == expectedBytes && temporary.length() == expectedBytes) {
                "The temporary external-file copy is incomplete."
            }
            RandomAccessFile(File(operationDirectory, LARGE_EXTERNAL_SHARE_RESERVATION_FILE), "rw").use { marker ->
                marker.setLength(0L)
                marker.fd.sync()
            }
            check(!target.exists() && temporary.renameTo(target)) {
                "Could not publish the temporary external-file copy."
            }
            check(target.setWritable(false, true) || !target.canWrite()) {
                "Could not make the temporary external-file copy read-only."
            }
            val declaredMime = sanitizeExternalMimeType(file.mimeType)
            val responseMime = sanitizeExternalMimeType(downloaded.mimeType)
            val mimeType = declaredMime.takeUnless { it == GENERIC_MIME_TYPE } ?: responseMime
            return StagedExternalFile.Ready(target, mimeType)
        } catch (failure: Throwable) {
            temporary.delete()
            operationDirectory.deleteRecursively()
            throw failure
        }
    }

    private fun stagePrivateCopy(sourceName: String, bytes: ByteArray): File {
        val root = File(context.cacheDir, EXTERNAL_SHARE_CACHE_DIRECTORY)
        check(root.isDirectory || root.mkdirs()) { "Could not create the private external-share cache." }
        val canonicalRoot = root.canonicalFile
        pruneExternalShareCache(canonicalRoot, bytes.size.toLong())

        val operationDirectory = File(canonicalRoot, UUID.randomUUID().toString())
        check(operationDirectory.mkdir()) { "Could not create a private external-share directory." }
        check(operationDirectory.canonicalFile.parentFile == canonicalRoot) { "Unsafe external-share directory." }

        val target = File(operationDirectory, sanitizeExternalFileName(sourceName))
        check(target.canonicalFile.parentFile == operationDirectory.canonicalFile) { "Unsafe external-share filename." }
        val temporary = File.createTempFile("payload-", ".tmp", operationDirectory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(temporary.length() == bytes.size.toLong()) { "The external-share cache copy is incomplete." }
            check(!target.exists() && temporary.renameTo(target)) { "Could not publish the external-share cache copy." }
            target.setWritable(false, true)
            return target
        } catch (failure: Throwable) {
            temporary.delete()
            operationDirectory.deleteRecursively()
            throw failure
        }
    }

    private fun buildChooser(
        action: ExternalFileHandoffAction,
        uri: Uri,
        mimeType: String,
        displayName: String,
    ): Intent {
        val plan = androidExternalFileIntentPlan(action)
        val clip = ClipData.newUri(context.contentResolver, displayName, uri)
        val target = when {
            plan.attachStream -> Intent(plan.action).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, displayName)
                clipData = clip
            }
            else -> Intent(plan.action).apply {
                setDataAndType(uri, mimeType)
                clipData = clip
            }
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(target, plan.chooserTitle).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    private sealed interface StagedExternalFile {
        data class Ready(val file: File, val mimeType: String) : StagedExternalFile
        data class Rejected(val result: ExternalFileHandoffResult.Rejected) : StagedExternalFile
    }
}

internal data class AndroidDetachedDownload(
    val byteCount: Long,
    val mimeType: String?,
)

internal data class AndroidExternalFileIntentPlan(
    val action: String,
    val chooserTitle: String,
    val attachStream: Boolean,
)

internal fun androidExternalFileIntentPlan(action: ExternalFileHandoffAction): AndroidExternalFileIntentPlan =
    when (action) {
        ExternalFileHandoffAction.Share -> AndroidExternalFileIntentPlan(
            action = Intent.ACTION_SEND,
            chooserTitle = "Share file",
            attachStream = true,
        )
        ExternalFileHandoffAction.OpenWith -> AndroidExternalFileIntentPlan(
            action = Intent.ACTION_VIEW,
            chooserTitle = "Open file with",
            attachStream = false,
        )
    }

internal fun pruneExternalShareCache(root: File, requiredBytes: Long, nowMillis: Long = System.currentTimeMillis()) {
    require(root.isDirectory) { "The external-share cache root is not a directory." }
    require(requiredBytes >= 0L && requiredBytes <= MAX_EXTERNAL_SHARE_CACHE_BYTES) {
        "The external-share cache request is outside its size bound."
    }
    val entries = root.listFiles().orEmpty().sortedBy(File::lastModified).toMutableList()
    entries.filter { nowMillis - it.lastModified() > EXTERNAL_SHARE_CACHE_MAX_AGE_MILLIS }.forEach { expired ->
        expired.deleteRecursively()
        entries.remove(expired)
    }
    var storedBytes = entries.fold(0L) { total, entry -> saturatingAdd(total, recursiveFileBytes(entry)) }
    val iterator = entries.iterator()
    while (saturatingAdd(storedBytes, requiredBytes) > MAX_EXTERNAL_SHARE_CACHE_BYTES && iterator.hasNext()) {
        val oldest = iterator.next()
        val bytes = recursiveFileBytes(oldest)
        if (oldest.deleteRecursively()) storedBytes = (storedBytes - bytes).coerceAtLeast(0L)
    }
    check(saturatingAdd(storedBytes, requiredBytes) <= MAX_EXTERNAL_SHARE_CACHE_BYTES) {
        "There is not enough room in the bounded external-share cache."
    }
}

internal fun androidExternalLargeShareCacheRoot(cacheDirectory: File): File =
    File(cacheDirectory, EXTERNAL_LARGE_SHARE_CACHE_DIRECTORY)

internal fun externalHandoffFile(file: NextcloudFile, stagedMimeType: String?): NextcloudFile =
    stagedMimeType?.let { file.copy(mimeType = sanitizeExternalMimeType(it)) } ?: file

internal fun androidLargeExternalHandoffAvailableBytes(root: File, availableBytes: Long): Long {
    require(root.isDirectory) { "The large external-share cache root is not a directory." }
    require(availableBytes >= 0L) { "Available storage must not be negative." }
    val reservedBytes = root.listFiles().orEmpty().fold(0L) { total, entry ->
        val reservation = File(entry, LARGE_EXTERNAL_SHARE_RESERVATION_FILE)
        if (reservation.isFile) saturatingAdd(total, reservation.length()) else total
    }
    return (availableBytes - reservedBytes).coerceAtLeast(0L)
}

internal fun androidExternalHandoffContentDirectory(root: File, documentId: String): File {
    require(AndroidExternalFileHandoffRegistry.isHandoffDocumentId(documentId)) {
        "The external handoff document ID is invalid."
    }
    val directoryName = androidExternalHandoffContentDirectoryName(documentId)
    val canonicalRoot = root.canonicalFile
    return File(canonicalRoot, directoryName).canonicalFile.also { directory ->
        require(directory.parentFile == canonicalRoot) { "Unsafe external handoff content directory." }
    }
}

internal fun androidExternalHandoffContentDirectoryName(documentId: String): String {
    require(AndroidExternalFileHandoffRegistry.isHandoffDocumentId(documentId)) {
        "The external handoff document ID is invalid."
    }
    return documentId.substringAfter(':')
}

internal fun publishLargeExternalHandoffContent(source: File, documentId: String): File {
    require(source.isFile) { "The staged external handoff content is unavailable." }
    val operationDirectory = requireNotNull(source.parentFile).canonicalFile
    val root = requireNotNull(operationDirectory.parentFile).canonicalFile
    require(root.name == EXTERNAL_LARGE_SHARE_CACHE_DIRECTORY) { "Unsafe external handoff cache root." }
    val destinationDirectory = androidExternalHandoffContentDirectory(root, documentId)
    check(!destinationDirectory.exists()) { "The external handoff content already exists." }
    check(operationDirectory.renameTo(destinationDirectory)) {
        "Could not publish the managed external handoff content."
    }
    check(File(destinationDirectory, LARGE_EXTERNAL_SHARE_RESERVATION_FILE).delete()) {
        "Could not release the managed external handoff cache marker."
    }
    return File(destinationDirectory, source.name).also { published ->
        check(published.isFile && published.canonicalFile.parentFile == destinationDirectory) {
            "The managed external handoff content is unavailable."
        }
    }
}

internal fun resolveLargeExternalHandoffContent(
    cacheDirectory: File,
    record: AndroidExternalFileHandoffRecord,
): File? {
    val root = androidExternalLargeShareCacheRoot(cacheDirectory)
    if (!root.isDirectory) return null
    val directory = androidExternalHandoffContentDirectory(root, record.documentId)
    if (!directory.isDirectory) return null
    val content = File(directory, sanitizeExternalFileName(record.file.name)).canonicalFile
    val expectedSize = record.file.size ?: return null
    return content.takeIf { candidate ->
        candidate.parentFile == directory && candidate.isFile && candidate.length() == expectedSize
    }
}

internal fun prepareLargeExternalShareCache(
    root: File,
    requiredBytes: Long,
    nowMillis: Long = System.currentTimeMillis(),
    maximumAggregateBytes: Long = DEFAULT_LARGE_EXTERNAL_SHARE_CACHE_BYTES,
    minimumRetentionMillis: Long = LARGE_EXTERNAL_SHARE_MINIMUM_RETENTION_MILLIS,
    protectedDirectoryNames: Set<String> = emptySet(),
) {
    require(root.isDirectory) { "The large external-share cache root is not a directory." }
    require(requiredBytes >= 0L && maximumAggregateBytes >= 0L && minimumRetentionMillis >= 0L)
    val budgetBytes = maxOf(requiredBytes, maximumAggregateBytes)
    val entries = root.listFiles().orEmpty().sortedBy(File::lastModified).toMutableList()
    entries.filter { entry ->
        entry.name !in protectedDirectoryNames &&
            elapsedAtLeast(nowMillis, entry.lastModified(), EXTERNAL_SHARE_CACHE_MAX_AGE_MILLIS)
    }
        .forEach { expired ->
            if (expired.deleteRecursively()) entries.remove(expired)
        }
    var storedBytes = entries.fold(0L) { total, entry -> saturatingAdd(total, recursiveFileBytes(entry)) }
    val eligible = entries
        .filter { entry ->
            !File(entry, LARGE_EXTERNAL_SHARE_RESERVATION_FILE).exists() &&
                entry.name !in protectedDirectoryNames &&
                elapsedAtLeast(nowMillis, entry.lastModified(), minimumRetentionMillis)
        }
        .iterator()
    while (saturatingAdd(storedBytes, requiredBytes) > budgetBytes && eligible.hasNext()) {
        val oldest = eligible.next()
        val bytes = recursiveFileBytes(oldest)
        if (oldest.deleteRecursively()) storedBytes = (storedBytes - bytes).coerceAtLeast(0L)
    }
    check(saturatingAdd(storedBytes, requiredBytes) <= budgetBytes) {
        "There is not enough room in the bounded large-file handoff cache."
    }
}

internal fun createLargeExternalShareOperationDirectory(
    canonicalRoot: File,
    expectedBytes: Long,
    reserve: (File, Long) -> Unit = ::reserveLargeExternalShareBytes,
): File {
    require(canonicalRoot.isDirectory) { "The large external-share cache root is not a directory." }
    require(expectedBytes > 0L) { "The large external-share reservation must be positive." }
    val operation = File(canonicalRoot, UUID.randomUUID().toString())
    try {
        check(operation.mkdir()) { "Could not create a private large-file handoff directory." }
        check(operation.canonicalFile.parentFile == canonicalRoot) { "Unsafe external-file directory." }
        reserve(File(operation, LARGE_EXTERNAL_SHARE_RESERVATION_FILE), expectedBytes)
        return operation
    } catch (failure: Throwable) {
        operation.deleteRecursively()
        throw failure
    }
}

private fun reserveLargeExternalShareBytes(reservationFile: File, expectedBytes: Long) {
    RandomAccessFile(reservationFile, "rw").use { reservation ->
        reservation.setLength(expectedBytes)
        reservation.fd.sync()
    }
}

internal fun androidLargeExternalHandoffFitsCapacity(
    requiredBytes: Long,
    availableBytes: Long,
    reserveBytes: Long = LARGE_EXTERNAL_SHARE_FREE_SPACE_RESERVE_BYTES,
): Boolean = requiredBytes >= 0L &&
    availableBytes >= 0L &&
    reserveBytes >= 0L &&
    requiredBytes <= availableBytes &&
    availableBytes - requiredBytes >= reserveBytes

private fun recursiveFileBytes(file: File): Long = when {
    file.isFile -> file.length()
    file.isDirectory -> file.listFiles().orEmpty().fold(0L) { total, child ->
        saturatingAdd(total, recursiveFileBytes(child))
    }
    else -> 0L
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private fun elapsedAtLeast(nowMillis: Long, thenMillis: Long, durationMillis: Long): Boolean =
    nowMillis >= thenMillis && nowMillis - thenMillis >= durationMillis

internal const val EXTERNAL_FILE_PROVIDER_AUTHORITY_SUFFIX = ".sharedfiles"
private const val EXTERNAL_SHARE_CACHE_DIRECTORY = "external-share"
private const val EXTERNAL_LARGE_SHARE_CACHE_DIRECTORY = "external-large-share"
internal const val LARGE_EXTERNAL_SHARE_RESERVATION_FILE = ".reservation"
private const val MAX_EXTERNAL_SHARE_CACHE_BYTES = 256L * 1024L * 1024L
private const val DEFAULT_LARGE_EXTERNAL_SHARE_CACHE_BYTES = 2L * 1024L * 1024L * 1024L
private const val LARGE_EXTERNAL_SHARE_FREE_SPACE_RESERVE_BYTES = 256L * 1024L * 1024L
private const val LARGE_EXTERNAL_SHARE_MINIMUM_RETENTION_MILLIS = 60L * 60L * 1000L
private const val EXTERNAL_SHARE_CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
private const val GENERIC_MIME_TYPE = "application/octet-stream"
private val LARGE_EXTERNAL_SHARE_CACHE_LOCK = Any()
