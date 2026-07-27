package dev.obiente.nextcloudnative

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.obiente.nextcloudnative.app.DeckAttachment
import dev.obiente.nextcloudnative.app.ExternalFileHandoffAction
import dev.obiente.nextcloudnative.app.ExternalFileHandoffCapability
import dev.obiente.nextcloudnative.app.ExternalFileHandoffResult
import dev.obiente.nextcloudnative.app.MAX_EXTERNAL_FILE_HANDOFF_BYTES
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudFileContent
import dev.obiente.nextcloudnative.app.sanitizeExternalFileName
import dev.obiente.nextcloudnative.app.sanitizeExternalMimeType
import dev.obiente.nextcloudnative.app.validateDeckAttachmentHandoff
import dev.obiente.nextcloudnative.app.validateDownloadedExternalFile
import dev.obiente.nextcloudnative.app.validateExternalFileHandoff
import dev.obiente.nextcloudnative.app.verifyDownloadedDeckAttachmentSize
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidExternalFileHandoff(private val context: Context) {
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
        return withContext(Dispatchers.Main) {
            try {
                context.startActivity(buildChooser(action, uri, staged.mimeType, staged.file.name))
                ExternalFileHandoffResult.Launched(action)
            } catch (_: ActivityNotFoundException) {
                staged.file.parentFile?.deleteRecursively()
                ExternalFileHandoffResult.NoCompatibleApplication(action)
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
    var storedBytes = entries.sumOf(::recursiveFileBytes)
    val iterator = entries.iterator()
    while (storedBytes + requiredBytes > MAX_EXTERNAL_SHARE_CACHE_BYTES && iterator.hasNext()) {
        val oldest = iterator.next()
        val bytes = recursiveFileBytes(oldest)
        if (oldest.deleteRecursively()) storedBytes = (storedBytes - bytes).coerceAtLeast(0L)
    }
    check(storedBytes + requiredBytes <= MAX_EXTERNAL_SHARE_CACHE_BYTES) {
        "There is not enough room in the bounded external-share cache."
    }
}

private fun recursiveFileBytes(file: File): Long = when {
    file.isFile -> file.length()
    file.isDirectory -> file.listFiles().orEmpty().sumOf(::recursiveFileBytes)
    else -> 0L
}

internal const val EXTERNAL_FILE_PROVIDER_AUTHORITY_SUFFIX = ".sharedfiles"
private const val EXTERNAL_SHARE_CACHE_DIRECTORY = "external-share"
private const val MAX_EXTERNAL_SHARE_CACHE_BYTES = 256L * 1024L * 1024L
private const val EXTERNAL_SHARE_CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
private const val GENERIC_MIME_TYPE = "application/octet-stream"
