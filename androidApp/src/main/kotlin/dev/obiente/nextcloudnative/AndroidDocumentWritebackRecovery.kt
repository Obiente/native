package dev.obiente.nextcloudnative

import android.os.ParcelFileDescriptor
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal const val MAX_ANDROID_DOCUMENT_WRITEBACK_BYTES = Long.MAX_VALUE
internal const val MIN_ANDROID_DOCUMENT_FREE_BYTES = 512L * 1024L * 1024L

internal fun descriptorMode(mode: String): Int = when (mode) {
    "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY
    "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
    "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_APPEND
    "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
    "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
    else -> error("Unsupported writable mode: $mode")
}

internal fun acquireAndroidDocumentWritebackAccountLease(
    session: NextcloudSession,
    remotePath: String,
    loadCurrentSession: () -> NextcloudSession?,
): AndroidAccountOperationLease {
    val lease = acquireAndroidDocumentMutationAccountLease(session, loadCurrentSession)
    return try {
        reserveAndroidDocumentWritebackPath(session, remotePath)
        lease
    } catch (failure: Throwable) {
        lease.close()
        throw failure
    }
}

internal fun acquireAndroidDocumentMutationAccountLease(
    session: NextcloudSession,
    loadCurrentSession: () -> NextcloudSession?,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
): AndroidAccountOperationLease {
    val lease = guard.acquireBlocking(NextcloudDocumentIds.accountKey(session))
    return try {
        if (!androidDocumentWritebackSessionIsCurrent(session, loadCurrentSession())) {
            throw FileNotFoundException("The active Nextcloud account changed before the document mutation could start.")
        }
        lease
    } catch (failure: Throwable) {
        lease.close()
        throw failure
    }
}

internal inline fun <Result> withAndroidDocumentMutation(
    session: NextcloudSession,
    noinline loadCurrentSession: () -> NextcloudSession?,
    action: (NextcloudSession) -> Result,
): Result {
    val lease = acquireAndroidDocumentMutationAccountLease(session, loadCurrentSession)
    return try {
        action(session)
    } finally {
        lease.close()
    }
}

internal fun releaseAndroidDocumentWritebackSetup(
    accountLease: AndroidAccountOperationLease,
    releasePath: () -> Unit,
) {
    try {
        releasePath()
    } finally {
        accountLease.close()
    }
}

internal fun requireAndroidDocumentWritebackCapacity(remoteSize: Long, availableBytes: Long) {
    require(remoteSize >= 0L && availableBytes >= 0L)
    require(remoteSize <= (availableBytes - MIN_ANDROID_DOCUMENT_FREE_BYTES).coerceAtLeast(0L)) {
        "There is not enough free space to stage this edit safely."
    }
}

internal fun requireAndroidDocumentStagedWritebackCapacity(stagedBytes: Long, availableBytes: Long) {
    require(stagedBytes >= 0L && availableBytes >= 0L)
    require(availableBytes >= MIN_ANDROID_DOCUMENT_FREE_BYTES) {
        "There is not enough free space to retain this edit safely."
    }
}

internal data class AndroidDocumentPendingWriteback(
    val staging: File,
    val manifest: File,
    val accountId: String,
    val remotePath: String,
    val expectedRemoteEtag: String,
    val conflict: Boolean = false,
) {
    init {
        require(accountId.isNotBlank())
        require(remotePath.isNotBlank() && remotePath.split('/').none { it.isEmpty() || it == "." || it == ".." })
        require(expectedRemoteEtag.isNotBlank() && '\r' !in expectedRemoteEtag && '\n' !in expectedRemoteEtag)
        require(staging.isFile && manifest.isFile)
    }

    fun markReadyAndActive() = synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
        val payload = JSONObject(manifest.readText()).put("ready", true).toString().encodeToByteArray()
        val temporary = File.createTempFile("manifest-", ".tmp", manifest.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    manifest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            ACTIVE_ANDROID_DOCUMENT_WRITEBACKS += manifest.activeWritebackKey()
        } finally {
            temporary.delete()
        }
    }

    fun markConflict(observedRemoteEtag: String?) = synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
        val data = JSONObject(manifest.readText())
            .put("conflict", true)
            .put("observedEtag", observedRemoteEtag ?: JSONObject.NULL)
        val payload = data.toString().encodeToByteArray()
        require(payload.size <= 64 * 1024)
        val temporary = File.createTempFile("manifest-", ".tmp", manifest.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    manifest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    fun complete() = synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
        staging.delete()
        manifest.delete()
        ACTIVE_ANDROID_DOCUMENT_WRITEBACKS -= manifest.activeWritebackKey()
        ACTIVE_ANDROID_DOCUMENT_WRITEBACK_PATHS -= activeWritebackPath()
    }

    fun discard() = synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
        manifest.delete()
        staging.delete()
        ACTIVE_ANDROID_DOCUMENT_WRITEBACKS -= manifest.activeWritebackKey()
        ACTIVE_ANDROID_DOCUMENT_WRITEBACK_PATHS -= activeWritebackPath()
    }

    fun releaseActive() = synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
        ACTIVE_ANDROID_DOCUMENT_WRITEBACKS -= manifest.activeWritebackKey()
        ACTIVE_ANDROID_DOCUMENT_WRITEBACK_PATHS -= activeWritebackPath()
    }

    private fun activeWritebackPath() = ActiveAndroidDocumentWritebackPath(accountId, remotePath)
}

internal fun androidDocumentPendingWritebackCount(context: android.content.Context, session: NextcloudSession): Int {
    return androidDocumentPendingWritebacks(context, session).size
}

internal fun androidDocumentPendingWritebacks(
    context: android.content.Context,
    session: NextcloudSession,
): List<AndroidDocumentPendingWriteback> = synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
    val root = File(context.filesDir, "documents-recovery")
    if (!root.isDirectory) return emptyList()
    val files = requireInspectableAndroidDocumentWritebackRecovery(root)
    val accountId = NextcloudDocumentIds.accountKey(session)
    return files.mapNotNull { manifest ->
        parseAndroidDocumentWriteback(root, manifest, accountId)
    }.filterNot { writeback ->
        writeback.manifest.activeWritebackKey() in ACTIVE_ANDROID_DOCUMENT_WRITEBACKS
    }.sortedBy { writeback -> writeback.manifest.lastModified() }
}

internal fun androidDocumentPendingWriteback(
    context: android.content.Context?,
    session: NextcloudSession,
    remotePath: String,
): AndroidDocumentPendingWriteback? = synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
    val root = context?.let { File(it.filesDir, "documents-recovery") } ?: return null
    if (!root.isDirectory) return null
    val files = requireInspectableAndroidDocumentWritebackRecovery(root)
    val account = NextcloudDocumentIds.accountKey(session)
    return files.asSequence()
        .mapNotNull { manifest -> parseAndroidDocumentWriteback(root, manifest, account) }
        .filter { writeback -> writeback.remotePath == remotePath }
        .filterNot { writeback ->
            writeback.manifest.activeWritebackKey() in ACTIVE_ANDROID_DOCUMENT_WRITEBACKS
        }
        .maxByOrNull { writeback -> writeback.manifest.lastModified() }
}

internal fun claimAndroidDocumentPendingWriteback(
    context: android.content.Context?,
    session: NextcloudSession,
    remotePath: String,
): AndroidDocumentPendingWriteback? = synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
    androidDocumentPendingWriteback(context, session, remotePath)?.also { writeback ->
        ACTIVE_ANDROID_DOCUMENT_WRITEBACKS += writeback.manifest.activeWritebackKey()
    }
}

internal fun claimAndroidDocumentPendingWritebackForRecovery(
    context: android.content.Context,
    session: NextcloudSession,
    remotePath: String,
): AndroidDocumentPendingWriteback? = synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
    val accountId = NextcloudDocumentIds.accountKey(session)
    if (androidDocumentMutationBlocksWriteback(accountId, remotePath)) return null
    val activePath = ActiveAndroidDocumentWritebackPath(accountId, remotePath)
    if (!ACTIVE_ANDROID_DOCUMENT_WRITEBACK_PATHS.add(activePath)) return null
    val pending = androidDocumentPendingWriteback(context, session, remotePath)
    if (pending == null) {
        ACTIVE_ANDROID_DOCUMENT_WRITEBACK_PATHS -= activePath
        return null
    }
    ACTIVE_ANDROID_DOCUMENT_WRITEBACKS += pending.manifest.activeWritebackKey()
    pending
}

internal fun reserveAndroidDocumentWritebackPath(session: NextcloudSession, remotePath: String) =
    synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
        val accountId = NextcloudDocumentIds.accountKey(session)
        check(!androidDocumentMutationBlocksWriteback(accountId, remotePath)) {
            "This document is already being changed by another local operation."
        }
        val active = ActiveAndroidDocumentWritebackPath(accountId, remotePath)
        check(ACTIVE_ANDROID_DOCUMENT_WRITEBACK_PATHS.add(active)) {
            "This document already has an active local edit."
        }
    }

internal fun releaseAndroidDocumentWritebackPath(session: NextcloudSession, remotePath: String) =
    synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
        ACTIVE_ANDROID_DOCUMENT_WRITEBACK_PATHS -=
            ActiveAndroidDocumentWritebackPath(NextcloudDocumentIds.accountKey(session), remotePath)
    }

internal fun <T> withNoBlockingAndroidDocumentWriteback(
    context: android.content.Context?,
    session: NextcloudSession,
    vararg remotePaths: String,
    operation: () -> T,
): T {
    val providerContext = requireNotNull(context) { "Provider context is unavailable." }
    val accountId = NextcloudDocumentIds.accountKey(session)
    val paths = remotePaths.toSet()
    require(paths.isNotEmpty() && paths.none(String::isBlank))
    val reservation = synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
        val activePaths = ACTIVE_ANDROID_DOCUMENT_WRITEBACK_PATHS.asSequence()
            .filter { active -> active.accountId == accountId }
            .map(ActiveAndroidDocumentWritebackPath::remotePath)
        val retainedPaths = androidDocumentPendingWritebacks(providerContext, session)
            .asSequence()
            .map(AndroidDocumentPendingWriteback::remotePath)
        check(!androidDocumentWritebacksBlockMutation(activePaths, retainedPaths, *remotePaths)) {
            "This document cannot be changed while a local edit still needs recovery."
        }
        check(
            ACTIVE_ANDROID_DOCUMENT_MUTATIONS.none { active ->
                active.accountId == accountId && androidDocumentMutationPathsOverlap(active.remotePaths, paths)
            },
        ) {
            "This document is already being changed by another local operation."
        }
        ActiveAndroidDocumentMutation(accountId, paths).also(ACTIVE_ANDROID_DOCUMENT_MUTATIONS::add)
    }
    return try {
        operation()
    } finally {
        synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
            check(ACTIVE_ANDROID_DOCUMENT_MUTATIONS.remove(reservation))
        }
    }
}

internal fun androidDocumentWritebacksBlockMutation(
    activePaths: Sequence<String>,
    retainedPaths: Sequence<String>,
    vararg mutationPaths: String,
): Boolean = (activePaths + retainedPaths).any { path ->
    androidDocumentWritebackPathBlocksMutation(path, *mutationPaths)
}

internal fun androidDocumentWritebackPathBlocksMutation(
    activePath: String,
    vararg mutationPaths: String,
): Boolean = mutationPaths.any { path -> activePath == path || activePath.startsWith("$path/") }

internal fun androidDocumentMutationPathsOverlap(first: Set<String>, second: Set<String>): Boolean =
    first.any { left ->
        second.any { right ->
            left == right || left.startsWith("$right/") || right.startsWith("$left/")
        }
    }

private fun androidDocumentMutationBlocksWriteback(accountId: String, remotePath: String): Boolean =
    ACTIVE_ANDROID_DOCUMENT_MUTATIONS.any { active ->
        active.accountId == accountId &&
            androidDocumentWritebackPathBlocksMutation(remotePath, *active.remotePaths.toTypedArray())
    }

internal inline fun handleAndroidDocumentWritebackRecoveryFailure(
    failure: Throwable,
    release: () -> Unit,
) {
    release()
    if (failure is CancellationException) throw failure
}

private data class AndroidDocumentWritebackManifest(
    val pending: AndroidDocumentPendingWriteback,
    val ready: Boolean,
)

private fun parseAndroidDocumentWritebackManifest(
    root: File,
    manifest: File,
    expectedAccount: String?,
): AndroidDocumentWritebackManifest? = runCatching {
    require(manifest.isFile && manifest.name.endsWith(".stage.json") && manifest.length() <= 64 * 1024L)
    val data = JSONObject(manifest.readText())
    val stageName = data.getString("stage")
    require(data.getInt("version") == 1)
    val account = data.getString("account")
    require(expectedAccount == null || account == expectedAccount)
    require(data.getLong("startedAt") >= 0L)
    require(stageName.startsWith("writeback-") && stageName.endsWith(".stage"))
    require('/' !in stageName && '\\' !in stageName)
    require(manifest.name == "$stageName.json")
    val stage = File(root, stageName)
    require(stage.isFile)
    AndroidDocumentWritebackManifest(
        pending = AndroidDocumentPendingWriteback(
            staging = stage,
            manifest = manifest,
            accountId = account,
            remotePath = data.getString("path"),
            expectedRemoteEtag = data.getString("etag"),
            conflict = data.optBoolean("conflict", false),
        ),
        ready = data.optBoolean("ready", false),
    )
}.getOrNull()

private fun parseAndroidDocumentWriteback(
    root: File,
    manifest: File,
    expectedAccount: String?,
): AndroidDocumentPendingWriteback? = parseAndroidDocumentWritebackManifest(root, manifest, expectedAccount)
    ?.takeIf(AndroidDocumentWritebackManifest::ready)
    ?.pending

internal fun requireInspectableAndroidDocumentWritebackRecovery(root: File): List<File> {
    if (!root.exists()) return emptyList()
    check(root.isDirectory) { "Document writeback recovery storage is not a directory." }
    val files = requireNotNull(root.listFiles()) { "Document writeback recovery storage could not be inspected." }
        .filter(File::isFile)
    val ambiguousManifest = files.firstOrNull { manifest ->
        manifest.name.startsWith("writeback-") &&
            manifest.name.endsWith(".stage.json") &&
            File(root, manifest.name.removeSuffix(".json")).isFile &&
            parseAndroidDocumentWritebackManifest(root, manifest, expectedAccount = null) == null
    }
    check(ambiguousManifest == null) {
        "A retained document edit has recovery metadata that cannot be inspected safely."
    }
    return files
}

/** Removes writeback transactions that could not reach the close-ready state before process death. */
internal fun cleanupIncompleteAndroidDocumentWritebacks(context: android.content.Context): Int =
    cleanupIncompleteAndroidDocumentWritebacks(File(context.filesDir, "documents-recovery"))

internal fun cleanupIncompleteAndroidDocumentWritebacks(root: File): Int =
    synchronized(ANDROID_DOCUMENT_WRITEBACK_LOCK) {
        if (!root.isDirectory) return 0
        val files = requireNotNull(root.listFiles()) {
            "Document writeback recovery storage could not be inspected."
        }.filter(File::isFile)
        val retainedNames = files.mapNotNull { manifest ->
            parseAndroidDocumentWriteback(root, manifest, expectedAccount = null)
        }.flatMapTo(hashSetOf()) { writeback ->
            listOf(writeback.staging.name, writeback.manifest.name)
        }
        files.filter { manifest ->
            manifest.name.startsWith("writeback-") &&
                manifest.name.endsWith(".stage.json") &&
                File(root, manifest.name.removeSuffix(".json")).isFile &&
                parseAndroidDocumentWritebackManifest(root, manifest, expectedAccount = null) == null
        }.forEach { manifest ->
            retainedNames += manifest.name
            retainedNames += manifest.name.removeSuffix(".json")
        }
        return files.count { file ->
            val owned =
                (file.name.startsWith("writeback-") && file.name.endsWith(".stage")) ||
                    (file.name.startsWith("writeback-") && file.name.endsWith(".stage.json")) ||
                    (file.name.startsWith("manifest-") && file.name.endsWith(".tmp"))
            owned && file.name !in retainedNames && file.delete()
        }
    }

private fun File.activeWritebackKey(): String = absoluteFile.normalize().path

private data class ActiveAndroidDocumentWritebackPath(
    val accountId: String,
    val remotePath: String,
)

private class ActiveAndroidDocumentMutation(
    val accountId: String,
    val remotePaths: Set<String>,
)

private val ANDROID_DOCUMENT_WRITEBACK_LOCK = Any()
private val ACTIVE_ANDROID_DOCUMENT_WRITEBACKS = ConcurrentHashMap.newKeySet<String>()
private val ACTIVE_ANDROID_DOCUMENT_WRITEBACK_PATHS =
    ConcurrentHashMap.newKeySet<ActiveAndroidDocumentWritebackPath>()
private val ACTIVE_ANDROID_DOCUMENT_MUTATIONS = mutableSetOf<ActiveAndroidDocumentMutation>()
