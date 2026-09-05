package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val MAX_WINDOWS_CLOUD_FILES_RECOVERY_ROOT_ATTEMPTS = 16

internal data class WindowsCloudFileIdentity(
    val accountId: String,
    val path: String,
    val remoteRevision: String,
    val size: Long,
    val directory: Boolean,
    val lastModifiedEpochMillis: Long? = null,
) {
    init {
        require(accountId.isNotBlank() && accountId.length <= MAX_ACCOUNT_ID_LENGTH)
        if (path.isNotEmpty()) FileOfflineKey(accountId, path)
        require(remoteRevision.isNotBlank() && remoteRevision.length <= MAX_REVISION_LENGTH)
        require(size >= 0L)
        require(!directory || size == 0L)
        require(lastModifiedEpochMillis == null || lastModifiedEpochMillis >= 0L)
    }

    private companion object {
        const val MAX_ACCOUNT_ID_LENGTH = 256
        const val MAX_REVISION_LENGTH = 1_024
    }
}

/** Versioned, checksummed and strictly bounded identity persisted in Windows placeholders. */
internal object WindowsCloudFileIdentityCodec {
    fun encode(identity: WindowsCloudFileIdentity): ByteArray {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeShort(VERSION)
                output.writeBoolean(identity.directory)
                output.writeLong(identity.size)
                output.writeLong(identity.lastModifiedEpochMillis ?: UNKNOWN_MODIFIED_TIME)
                output.writeBoundedUtf8(identity.accountId, MAX_ACCOUNT_BYTES)
                output.writeBoundedUtf8(identity.path, MAX_PATH_BYTES)
                output.writeBoundedUtf8(identity.remoteRevision, MAX_REVISION_BYTES)
            }
            bytes.toByteArray()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return payload + digest
    }

    fun decode(bytes: ByteArray): WindowsCloudFileIdentity {
        require(bytes.size in MIN_IDENTITY_BYTES..MAX_IDENTITY_BYTES) {
            "The Windows placeholder identity has an invalid size."
        }
        val payload = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        val expectedDigest = bytes.copyOfRange(bytes.size - DIGEST_BYTES, bytes.size)
        require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(expectedDigest)) {
            "The Windows placeholder identity checksum is invalid."
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "The Windows placeholder identity type is invalid." }
            val version = input.readUnsignedShort()
            require(version in MINIMUM_SUPPORTED_VERSION..VERSION) {
                "The Windows placeholder identity version is unsupported."
            }
            val directory = input.readBoolean()
            val size = input.readLong()
            val lastModifiedEpochMillis = if (version >= 2) {
                input.readLong().takeUnless { it == UNKNOWN_MODIFIED_TIME }
            } else {
                null
            }
            val accountId = input.readBoundedUtf8(MAX_ACCOUNT_BYTES)
            val path = input.readBoundedUtf8(MAX_PATH_BYTES)
            val revision = input.readBoundedUtf8(MAX_REVISION_BYTES)
            require(input.available() == 0) { "The Windows placeholder identity has trailing data." }
            WindowsCloudFileIdentity(accountId, path, revision, size, directory, lastModifiedEpochMillis)
        }
    }

    private fun DataOutputStream.writeBoundedUtf8(value: String, maximumBytes: Int) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= maximumBytes)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedUtf8(maximumBytes: Int): String {
        val length = readUnsignedShort()
        require(length <= maximumBytes && length <= available()) {
            "The Windows placeholder identity field is invalid."
        }
        val bytes = ByteArray(length).also(::readFully)
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    private const val MAGIC = 0x4E434656 // NCFV
    private const val VERSION = 2
    private const val MINIMUM_SUPPORTED_VERSION = 1
    private const val UNKNOWN_MODIFIED_TIME = -1L
    private const val DIGEST_BYTES = 32
    private const val MAX_ACCOUNT_BYTES = 256
    private const val MAX_PATH_BYTES = 3_072
    private const val MAX_REVISION_BYTES = 1_024
    private const val MAX_IDENTITY_BYTES = 4_096
    private const val MIN_IDENTITY_BYTES = 4 + 2 + 1 + 8 + 2 + 2 + 2 + DIGEST_BYTES
}

internal data class WindowsCloudHydrationRange(val offset: Long, val length: Int) {
    init {
        require(offset >= 0L && offset % WINDOWS_CLOUD_ALIGNMENT == 0L)
        require(length > 0)
    }
}

/** Produces CfExecute-compatible chunks; only the final range may end unaligned at EOF. */
internal fun planWindowsCloudHydration(
    requiredOffset: Long,
    requiredLength: Long,
    fileSize: Long,
    maximumChunkBytes: Int = 4 * 1024 * 1024,
): List<WindowsCloudHydrationRange> {
    require(requiredOffset >= 0L && requiredLength > 0L && fileSize > 0L)
    require(
        maximumChunkBytes.toLong() >= WINDOWS_CLOUD_ALIGNMENT &&
            maximumChunkBytes.toLong() % WINDOWS_CLOUD_ALIGNMENT == 0L,
    )
    val requiredEnd = minOf(fileSize, Math.addExact(requiredOffset, requiredLength))
    require(requiredOffset < requiredEnd)
    val transferEnd = if (requiredEnd == fileSize) {
        fileSize
    } else {
        minOf(fileSize, Math.addExact(requiredEnd, WINDOWS_CLOUD_ALIGNMENT - 1L) / WINDOWS_CLOUD_ALIGNMENT * WINDOWS_CLOUD_ALIGNMENT)
    }
    var cursor = requiredOffset - requiredOffset % WINDOWS_CLOUD_ALIGNMENT
    return buildList {
        while (cursor < transferEnd) {
            val remaining = transferEnd - cursor
            val length = minOf(maximumChunkBytes.toLong(), remaining).toInt()
            add(WindowsCloudHydrationRange(cursor, length))
            cursor += length
        }
    }
}

internal data class WindowsCloudCallbackInfo(
    val connectionKey: Long,
    val transferKey: Long,
    val requestKey: Long,
    val normalizedPath: String,
    val fileIdentity: ByteArray?,
    val fileSize: Long,
    val priorityHint: Int,
)

internal interface WindowsCloudFilesCallbacks {
    fun fetchData(info: WindowsCloudCallbackInfo, requiredOffset: Long, requiredLength: Long)
    fun cancelFetchData(info: WindowsCloudCallbackInfo, offset: Long, length: Long)
    fun fetchPlaceholders(info: WindowsCloudCallbackInfo, pattern: String?)
    fun cancelFetchPlaceholders(info: WindowsCloudCallbackInfo)
    fun closed(info: WindowsCloudCallbackInfo, deleted: Boolean)
    fun deleteRequested(info: WindowsCloudCallbackInfo)
    fun renameRequested(info: WindowsCloudCallbackInfo, targetPath: String)
}

internal data class WindowsCloudPlaceholder(
    val name: String,
    val identity: ByteArray,
    val size: Long,
    val directory: Boolean,
    val lastModifiedEpochMillis: Long? = null,
) {
    init {
        require(name.isNotBlank() && name.none { it == '/' || it == '\\' || it == '\u0000' })
        require(identity.size <= 4_096)
        require(size >= 0L)
        require(lastModifiedEpochMillis == null || lastModifiedEpochMillis >= 0L)
    }
}

internal enum class WindowsCloudPlaceholderState {
    Absent,
    InSync,
    Dirty,
}

internal enum class WindowsCloudPlaceholderEntryState {
    Missing,
    Local,
    InSync,
    Dirty,
    Corrupt,
    Unreadable,
}

internal data class WindowsCloudPlaceholderInspection(
    val state: WindowsCloudPlaceholderEntryState,
    val win32Error: Int? = null,
    val fileAttributes: Int? = null,
    val reparseTag: Int? = null,
) {
    val placeholderState: WindowsCloudPlaceholderState
        get() = when (state) {
            WindowsCloudPlaceholderEntryState.InSync -> WindowsCloudPlaceholderState.InSync
            WindowsCloudPlaceholderEntryState.Dirty -> WindowsCloudPlaceholderState.Dirty
            else -> WindowsCloudPlaceholderState.Absent
        }
}

internal sealed class WindowsCloudFilesStartupRecoveryException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class WindowsCloudFilesCorruptEntryException(
    val inspection: WindowsCloudPlaceholderInspection,
    cause: Throwable? = null,
) : WindowsCloudFilesStartupRecoveryException(
    "Windows Cloud Files found an unreadable placeholder that requires non-destructive root recovery.",
    cause,
)

internal class WindowsCloudFilesUnreadableEntryException(
    val inspection: WindowsCloudPlaceholderInspection,
) : WindowsCloudFilesStartupRecoveryException(
    "Windows could not inspect an existing Cloud Files entry safely" +
        inspection.win32Error?.let { " (Win32 error $it)." }.orEmpty(),
)

private data class WindowsCloudFilesDelayedCorruptRootRecovery(
    val corruption: WindowsCloudFilesCorruptEntryException,
    val generation: Long,
)

internal fun windowsCloudFilesRecoveryRoot(root: Path, recoveryId: String): Path {
    require(recoveryId.length in 8..32 && recoveryId.all { it.isLetterOrDigit() || it == '-' })
    val absoluteRoot = root.toAbsolutePath().normalize()
    return requireNotNull(absoluteRoot.parent).resolve("${absoluteRoot.fileName}.recovery-$recoveryId")
}

internal fun preserveWindowsCloudFilesCorruptRoot(root: Path): Path {
    val absoluteRoot = root.toAbsolutePath().normalize()
    repeat(MAX_WINDOWS_CLOUD_FILES_RECOVERY_ROOT_ATTEMPTS) {
        val recoveryId = UUID.randomUUID().toString().substringBeforeLast('-')
        val recoveryRoot = windowsCloudFilesRecoveryRoot(absoluteRoot, recoveryId)
        try {
            return Files.move(absoluteRoot, recoveryRoot)
        } catch (_: FileAlreadyExistsException) {
            // A recovery root is never overwritten. Generate another private local suffix.
        }
    }
    error("Could not reserve a unique folder for the preserved Windows Cloud Files root.")
}

private fun windowsErrorDiagnosticCode(error: Int): String = "WIN32:0x${error.toUInt().toString(16)}"

private fun WindowsCloudPlaceholderInspection.diagnosticFields(): List<SupportDiagnosticFieldDraft> = buildList {
    add(SupportDiagnosticFieldDraft("inspection_state", state.name.lowercase()))
    win32Error?.let { add(SupportDiagnosticFieldDraft("win32_error", it.toString())) }
    fileAttributes?.let {
        add(SupportDiagnosticFieldDraft("file_attributes", "0x${it.toUInt().toString(16)}"))
    }
    reparseTag?.let { add(SupportDiagnosticFieldDraft("reparse_tag", "0x${it.toUInt().toString(16)}")) }
}

internal interface WindowsCloudFilesApi : AutoCloseable {
    fun registerSyncRoot(root: Path, displayName: String, syncRootIdentity: ByteArray)
    fun unregisterSyncRoot(root: Path)
    fun connect(root: Path, callbacks: WindowsCloudFilesCallbacks): Long
    fun disconnect(connectionKey: Long)
    fun createPlaceholders(baseDirectory: Path, placeholders: List<WindowsCloudPlaceholder>)
    fun transferData(info: WindowsCloudCallbackInfo, offset: Long, bytes: ByteArray)
    fun failData(info: WindowsCloudCallbackInfo, offset: Long, length: Long, message: String)
    fun completePlaceholderFetch(info: WindowsCloudCallbackInfo, placeholders: List<WindowsCloudPlaceholder>)
    fun failPlaceholderFetch(info: WindowsCloudCallbackInfo)
    fun acknowledgeDelete(info: WindowsCloudCallbackInfo, accepted: Boolean)
    fun acknowledgeRename(info: WindowsCloudCallbackInfo, accepted: Boolean)
    fun placeholderState(path: Path): WindowsCloudPlaceholderState
    fun inspectPlaceholder(path: Path): WindowsCloudPlaceholderInspection {
        val state = placeholderState(path)
        return when (state) {
            WindowsCloudPlaceholderState.InSync ->
                WindowsCloudPlaceholderInspection(WindowsCloudPlaceholderEntryState.InSync)
            WindowsCloudPlaceholderState.Dirty ->
                WindowsCloudPlaceholderInspection(WindowsCloudPlaceholderEntryState.Dirty)
            WindowsCloudPlaceholderState.Absent -> WindowsCloudPlaceholderInspection(
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    WindowsCloudPlaceholderEntryState.Local
                } else {
                    WindowsCloudPlaceholderEntryState.Missing
                },
            )
        }
    }
    fun allocatedBytes(path: Path): Long
    fun lastAccessedAtEpochMillis(path: Path): Long
    fun isPinned(path: Path): Boolean
    fun placeholderIdentity(path: Path): ByteArray?
    fun updatePlaceholder(
        path: Path,
        placeholder: WindowsCloudPlaceholder,
        invalidateContent: Boolean = false,
        preserveSyncState: Boolean = false,
    )
    fun convertToPlaceholder(path: Path, placeholder: WindowsCloudPlaceholder)
    fun markInSync(path: Path)
    fun dehydrate(path: Path): Long
}

internal interface WindowsCloudFileReadHandle : AutoCloseable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

internal data class WindowsCloudFilesSummary(
    val cachedBytes: Long,
    val reclaimableBytes: Long,
    val pinnedBytes: Long,
    val hydratedFileCount: Int,
    val pinnedFileCount: Int,
    val availableFreeBytes: Long,
    val pendingWritebackCount: Int,
    val failedWritebackCount: Int,
)

internal interface WindowsCloudFilesBackend {
    val accountId: String
    val displayName: String
    fun resolve(path: String): WindowsCloudFileIdentity?
    fun list(path: String): List<WindowsCloudFileIdentity>
    fun open(identity: WindowsCloudFileIdentity): WindowsCloudFileReadHandle
    fun upload(path: String, localFile: File, expectedRemoteRevision: String?): WindowsCloudFileIdentity
    fun createDirectory(path: String): WindowsCloudFileIdentity
    fun delete(identity: WindowsCloudFileIdentity)
    fun move(identity: WindowsCloudFileIdentity, destinationPath: String): WindowsCloudFileIdentity
}

internal class DesktopNextcloudWindowsCloudFilesBackend(
    private val session: NextcloudSession,
    private val userId: String,
    private val services: NextcloudPlatformServices,
    private val tree: DesktopFileSyncRemoteTree = DesktopFileSyncRemoteTree(session, userId, ""),
) : WindowsCloudFilesBackend {
    override val accountId: String = desktopFileCacheAccountId(session)
    override val displayName: String = windowsCloudShellDisplayName(session)

    override fun resolve(path: String): WindowsCloudFileIdentity? =
        tree.resolve(path.windowsCloudPath())?.toWindowsIdentity()

    override fun list(path: String): List<WindowsCloudFileIdentity> =
        tree.list(path.windowsCloudPath()).map { it.toWindowsIdentity() }

    override fun open(identity: WindowsCloudFileIdentity): WindowsCloudFileReadHandle {
        require(identity.accountId == accountId && !identity.directory && identity.size > 0L)
        val source = services.openFileRangeSession(
            session = session,
            userId = userId,
            path = identity.path,
            size = identity.size,
            expectedEtag = identity.remoteRevision,
        )
        return object : WindowsCloudFileReadHandle {
            override val size: Long = source.size
            override fun read(offset: Long, length: Int): ByteArray =
                runBlocking(Dispatchers.IO) { source.read(offset, length) }
            override fun close() = source.close()
        }
    }

    override fun upload(
        path: String,
        localFile: File,
        expectedRemoteRevision: String?,
    ): WindowsCloudFileIdentity = tree.writeFile(path.windowsCloudPath(), localFile, expectedRemoteRevision)
        .let { uploaded ->
            WindowsCloudFileIdentity(accountId, uploaded.relativePath, uploaded.etag, uploaded.size ?: localFile.length(), false)
        }

    override fun createDirectory(path: String): WindowsCloudFileIdentity {
        val normalized = path.windowsCloudPath()
        tree.createDirectory(normalized, expectedRemoteEtag = null)
        return requireNotNull(resolve(normalized))
    }

    override fun delete(identity: WindowsCloudFileIdentity) {
        require(identity.accountId == accountId)
        tree.delete(identity.path, identity.remoteRevision)
    }

    override fun move(identity: WindowsCloudFileIdentity, destinationPath: String): WindowsCloudFileIdentity {
        require(identity.accountId == accountId)
        val destination = destinationPath.windowsCloudPath()
        tree.move(identity.path, destination, identity.remoteRevision)
        return requireNotNull(resolve(destination))
    }

    private fun DesktopRemoteSyncDocument.toWindowsIdentity(): WindowsCloudFileIdentity =
        WindowsCloudFileIdentity(
            accountId = accountId,
            path = entry.relativePath,
            remoteRevision = entry.etag,
            size = entry.size ?: 0L,
            directory = isDirectory,
            lastModifiedEpochMillis = lastModifiedEpochMillis,
        )
}

/**
 * Windows Cloud Files provider lifecycle and callback coordinator.
 *
 * Native callbacks are dispatched away from the Cloud Filter thread. Hydration is generation
 * pinned, random-seek capable, cancellable, and transferred in 4 KiB aligned chunks. Namespace
 * mutations are accepted only after the corresponding ETag-guarded WebDAV operation succeeds.
 */
internal class WindowsCloudFilesProvider(
    private val root: Path,
    private val backend: WindowsCloudFilesBackend,
    private val api: WindowsCloudFilesApi,
    private val executor: ExecutorService = Executors.newFixedThreadPool(4) { work ->
        Thread(work, "nextcloud-windows-cloud-files").apply { isDaemon = true }
    },
    private val writebackRetryDelayMillis: (attempt: Int) -> Long = ::windowsWritebackRetryDelayMillis,
    private val directoryRefreshRetryDelayMillis: (attempt: Int) -> Long =
        ::windowsDirectoryRefreshRetryDelayMillis,
    private val recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit = {},
    private val preserveCorruptRoot: (Path) -> Path = ::preserveWindowsCloudFilesCorruptRoot,
    private val recordPreservedCorruptRoot: (Path) -> Unit = {},
    private val onRuntimeFailure: (Throwable) -> Unit = {},
) : AutoCloseable, WindowsCloudFilesCallbacks {
    private val connection = AtomicLongState()
    private val apiClosed = AtomicBoolean(false)
    private val cancelledRequests = ConcurrentHashMap<Long, AtomicBoolean>()
    private val knownIdentities = ConcurrentHashMap<String, WindowsCloudFileIdentity>()
    private val pathOperations = ConcurrentHashMap.newKeySet<String>()
    private val queuedPathOperations = ConcurrentHashMap<String, () -> Unit>()
    private val pendingWritebacks = ConcurrentHashMap.newKeySet<String>()
    private val failedWritebacks = ConcurrentHashMap.newKeySet<String>()
    private val writebackAttempts = ConcurrentHashMap<String, Int>()
    private val namespaceMutationLock = Any()
    private val callbacksPaused = AtomicBoolean(false)
    private val accountRemovalPaused = AtomicBoolean(false)
    private val corruptRootRecoveryLifecycleLock = Any()
    private val corruptRootStableAccessLock = Any()
    private val corruptRootRecoveryClaimed = AtomicBoolean(false)
    private val corruptRootRecoveryGeneration = AtomicLong(0L)
    private val pendingActivationDirectoryRefreshChecks = AtomicInteger()
    private val pendingDelayedCorruptRootRecoveries = ConcurrentHashMap.newKeySet<WindowsCloudFilesDelayedCorruptRootRecovery>()
    private val runtimeRecoveryFailure = AtomicReference<Throwable?>(null)
    private val runtimeStopping = AtomicBoolean(false)
    private val destructiveCallbackOperations = AtomicInteger()
    private val localChangeScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { work ->
        Thread(work, "nextcloud-windows-local-changes").apply { isDaemon = true }
    }
    private val pendingLocalChanges = ConcurrentHashMap<Path, ScheduledFuture<*>>()
    private val deferredLocalChanges = ConcurrentHashMap.newKeySet<Path>()
    private val initialPopulationStarted = AtomicBoolean(false)
    private val initialPopulationFinished = CountDownLatch(1)
    @Volatile private var initialPopulationSucceeded = false
    private val runtimeStarted = CountDownLatch(1)
    private val initialRecoveryFinished = CountDownLatch(1)
    @Volatile private var initialRecoveryFailure: WindowsCloudFilesStartupRecoveryException? = null
    @Volatile var preservedRecoveryRoot: Path? = null
        private set
    @Volatile private var watchService: WatchService? = null
    @Volatile private var watcherThread: Thread? = null
    private val accountRemovalQuiescence = WindowsCloudFilesRemovalQuiescence(
        ::pauseCallbacksForAccountRemoval, ::accountRemovalMutationState, ::resumeCallbacksAndReplayLocalChanges,
    )

    fun start() {
        check(connection.get() == 0L) { "The Windows Cloud Files provider is already connected." }
        check(initialPopulationStarted.compareAndSet(false, true)) {
            "The Windows Cloud Files provider startup has already been attempted."
        }
        prepareRootDirectory()
        val rootIdentity = WindowsCloudFileIdentity(backend.accountId, "", "root", 0L, true)
        val encodedRootIdentity = WindowsCloudFileIdentityCodec.encode(rootIdentity)
        api.registerSyncRoot(root, backend.displayName, encodedRootIdentity)
        connection.set(connectWindowsCloudFilesWithRegistrationRecovery(root, this, api) {
            prepareRootDirectory()
            api.registerSyncRoot(root, backend.displayName, encodedRootIdentity)
        })
        try {
            try {
                populateDirectory("", root)
            } catch (corruption: WindowsCloudFilesCorruptEntryException) {
                recoverCorruptRoot(encodedRootIdentity, corruption)
            }
            initialPopulationSucceeded = true
            initialPopulationFinished.countDown()
            if (watchService == null) startLocalWatcher()
            executor.execute {
                try {
                    recoverLocalChanges()
                } catch (failure: WindowsCloudFilesStartupRecoveryException) {
                    initialRecoveryFailure = failure
                } finally {
                    initialRecoveryFinished.countDown()
                }
            }
            runtimeStarted.countDown()
        } catch (failure: Throwable) {
            callbacksPaused.set(true)
            initialPopulationFinished.countDown()
            runtimeStarted.countDown()
            stopLocalWatcher()
            val key = connection.get()
            if (key != 0L && runCatching { api.disconnect(key) }.isSuccess) {
                connection.compareAndSet(key, 0L)
            }
            throw failure
        }
    }

    private fun recoverCorruptRoot(
        syncRootIdentity: ByteArray,
        corruption: WindowsCloudFilesCorruptEntryException,
        quiescenceTimeoutSeconds: Long = DEFAULT_CORRUPT_ROOT_QUIESCENCE_TIMEOUT_SECONDS,
        expectedGeneration: Long = corruptRootRecoveryGeneration.get(),
    ) {
        require(quiescenceTimeoutSeconds > 0L)
        val claimDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(quiescenceTimeoutSeconds)
        while (!claimCorruptRootRecovery()) {
            check(!runtimeStopping.get()) { "Windows Cloud Files is stopping." }
            check(!accountRemovalPaused.get()) { "Windows Cloud Files is paused for account removal." }
            runtimeRecoveryFailure.get()?.let { throw it }
            if (corruptRootRecoveryGeneration.get() != expectedGeneration) return
            check(System.nanoTime() < claimDeadline) {
                "Timed out waiting for the active Windows Cloud Files corrupt-root recovery."
            }
            try {
                Thread.sleep(25L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException(
                    "Interrupted while waiting for Windows Cloud Files corrupt-root recovery.",
                    interrupted,
                )
            }
        }
        try {
            runtimeRecoveryFailure.get()?.let { throw it }
            if (corruptRootRecoveryGeneration.get() != expectedGeneration) return
            performCorruptRootRecovery(syncRootIdentity, corruption, quiescenceTimeoutSeconds)
            corruptRootRecoveryGeneration.incrementAndGet()
        } catch (failure: Throwable) {
            runtimeRecoveryFailure.compareAndSet(null, failure)
            throw failure
        } finally {
            corruptRootRecoveryClaimed.set(false)
        }
    }

    private fun performCorruptRootRecovery(
        syncRootIdentity: ByteArray,
        corruption: WindowsCloudFilesCorruptEntryException,
        quiescenceTimeoutSeconds: Long,
    ) = synchronized(corruptRootStableAccessLock) {
        performCorruptRootRecoveryWithExclusiveRootAccess(
            syncRootIdentity,
            corruption,
            quiescenceTimeoutSeconds,
        )
    }

    private fun performCorruptRootRecoveryWithExclusiveRootAccess(
        syncRootIdentity: ByteArray,
        corruption: WindowsCloudFilesCorruptEntryException,
        quiescenceTimeoutSeconds: Long,
    ) {
        require(quiescenceTimeoutSeconds > 0L)
        if (watchService != null) stopLocalWatcher()
        synchronized(namespaceMutationLock) {
            callbacksPaused.set(true)
        }
        val key = connection.get()
        if (key != 0L) {
            api.disconnect(key)
            check(connection.compareAndSet(key, 0L)) {
                "The Windows Cloud Files connection changed during corrupt-root recovery."
            }
        }
        awaitWindowsCloudFilesPathOperationQuiescence(
            System.nanoTime() + TimeUnit.SECONDS.toNanos(quiescenceTimeoutSeconds),
            ::accountRemovalMutationState,
        )
        api.unregisterSyncRoot(root)
        val preserved = try {
            preserveCorruptRoot(root)
        } catch (failure: Throwable) {
            runCatching {
                prepareRootDirectory()
                api.registerSyncRoot(root, backend.displayName, syncRootIdentity)
            }.exceptionOrNull()?.let(failure::addSuppressed)
            failure.addSuppressed(corruption)
            throw IllegalStateException(
                "Could not preserve the unreadable Windows Cloud Files root for automatic recovery.",
                failure,
            )
        }
        try {
            recordPreservedCorruptRoot(preserved)
        } catch (persistenceFailure: Throwable) {
            runCatching {
                Files.move(preserved, root)
                prepareRootDirectory()
                api.registerSyncRoot(root, backend.displayName, syncRootIdentity)
            }.exceptionOrNull()?.let(persistenceFailure::addSuppressed)
            persistenceFailure.addSuppressed(corruption)
            throw IllegalStateException(
                "Could not durably record the preserved Windows Cloud Files root.",
                persistenceFailure,
            )
        }
        preservedRecoveryRoot = preserved
        knownIdentities.clear()
        pendingWritebacks.clear()
        failedWritebacks.clear()
        writebackAttempts.clear()
        recordPlaceholderDiagnostic(
            severity = SupportDiagnosticSeverity.Warning,
            operation = "cloud-files.corrupt-root-recovery",
            outcome = "corrupt-root-preserved",
            localDirectory = root,
            identity = null,
            fields = corruption.inspection.diagnosticFields() + SupportDiagnosticFieldDraft(
                "preserved_root",
                preserved.toString(),
                SupportDiagnosticValuePrivacy.LocalPath,
            ),
        )
        try {
            prepareRootDirectory()
            api.registerSyncRoot(root, backend.displayName, syncRootIdentity)
            connection.set(api.connect(root, this))
            populateDirectory("", root)
            if (!runtimeStopping.get()) startLocalWatcher()
            recoverUnmanagedLocalEntries(failClosed = true)
            resumeCallbacksAndReplayLocalChanges()
        } catch (retryFailure: Throwable) {
            retryFailure.addSuppressed(corruption)
            throw retryFailure
        }
        recordPlaceholderDiagnostic(
            severity = SupportDiagnosticSeverity.Info,
            operation = "cloud-files.corrupt-root-recovery",
            outcome = "corrupt-root-recovered",
            localDirectory = root,
            identity = null,
            fields = listOf(
                SupportDiagnosticFieldDraft(
                    "preserved_root",
                    preserved.toString(),
                    SupportDiagnosticValuePrivacy.LocalPath,
                ),
            ),
        )
    }

    private fun recoverCorruptRootAfterDelayedRefresh(
        recovery: WindowsCloudFilesDelayedCorruptRootRecovery,
    ) {
        var claimed = false
        try {
            if (runtimeStopping.get()) return
            if (runtimeRecoveryFailure.get() != null) return
            if (recovery.generation != corruptRootRecoveryGeneration.get()) return
            if (!claimCorruptRootRecovery()) {
                recordPlaceholderDiagnostic(
                    severity = SupportDiagnosticSeverity.Info,
                    operation = "cloud-files.corrupt-root-recovery",
                    outcome = "corrupt-root-recovery-coalesced",
                    localDirectory = root,
                    identity = null,
                )
                return
            }
            claimed = true
            if (recovery.generation != corruptRootRecoveryGeneration.get()) return
            val rootIdentity = WindowsCloudFileIdentity(backend.accountId, "", "root", 0L, true)
            performCorruptRootRecovery(
                WindowsCloudFileIdentityCodec.encode(rootIdentity),
                recovery.corruption,
                DEFAULT_CORRUPT_ROOT_QUIESCENCE_TIMEOUT_SECONDS,
            )
            corruptRootRecoveryGeneration.incrementAndGet()
            // The completed initial scan describes the preserved root generation. Once the
            // replacement root has been populated and scanned successfully, none of its
            // failures can safely be applied to the new generation.
            initialRecoveryFailure = null
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure.suppressed.none { it === recovery.corruption }) {
                failure.addSuppressed(recovery.corruption)
            }
            callbacksPaused.set(true)
            runtimeRecoveryFailure.compareAndSet(null, failure)
            recordPlaceholderDiagnostic(
                severity = SupportDiagnosticSeverity.Error,
                operation = "cloud-files.corrupt-root-recovery",
                outcome = "corrupt-root-recovery-failed",
                code = windowsCloudFilesDiagnosticCode(failure),
                localDirectory = root,
                identity = null,
            )
            runCatching { onRuntimeFailure(failure) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
        } finally {
            if (claimed) corruptRootRecoveryClaimed.set(false)
        }
    }

    fun runtimeRecoveryFailure(): Throwable? = runtimeRecoveryFailure.get()

    internal fun isCorruptRootRecoveryInProgress(): Boolean = corruptRootRecoveryClaimed.get()

    private fun claimCorruptRootRecovery(): Boolean = synchronized(corruptRootRecoveryLifecycleLock) {
        !runtimeStopping.get() && !accountRemovalPaused.get() && corruptRootRecoveryClaimed.compareAndSet(false, true)
    }

    private fun scheduleCorruptRootRecoveryAfterStartup(
        recovery: WindowsCloudFilesDelayedCorruptRootRecovery,
    ) {
        if (!pendingDelayedCorruptRootRecoveries.add(recovery)) return
        scheduleRegisteredCorruptRootRecoveryAfterStartup(recovery)
    }

    private fun scheduleRegisteredCorruptRootRecoveryAfterStartup(
        recovery: WindowsCloudFilesDelayedCorruptRootRecovery,
    ) {
        if (callbacksPaused.get() || runtimeStopping.get() || runtimeRecoveryFailure.get() != null) {
            pendingDelayedCorruptRootRecoveries.remove(recovery)
            return
        }
        try {
            localChangeScheduler.schedule(
                {
                    if (callbacksPaused.get() || runtimeStopping.get() || runtimeRecoveryFailure.get() != null) {
                        pendingDelayedCorruptRootRecoveries.remove(recovery)
                        return@schedule
                    }
                    if (runtimeStarted.count > 0L || initialRecoveryFinished.count > 0L) {
                        scheduleRegisteredCorruptRootRecoveryAfterStartup(recovery)
                        return@schedule
                    }
                    try {
                        executor.execute {
                            try {
                                recoverCorruptRootAfterDelayedRefresh(recovery)
                            } finally {
                                pendingDelayedCorruptRootRecoveries.remove(recovery)
                            }
                        }
                    } catch (failure: Throwable) {
                        failScheduledCorruptRootRecovery(recovery, failure)
                    }
                },
                DELAYED_CORRUPT_ROOT_RECOVERY_POLL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        } catch (failure: Throwable) {
            failScheduledCorruptRootRecovery(recovery, failure)
        }
    }

    private fun failScheduledCorruptRootRecovery(
        recovery: WindowsCloudFilesDelayedCorruptRootRecovery,
        failure: Throwable,
    ) {
        pendingDelayedCorruptRootRecoveries.remove(recovery)
        if (runtimeStopping.get()) return
        if (failure.suppressed.none { it === recovery.corruption }) {
            failure.addSuppressed(recovery.corruption)
        }
        callbacksPaused.set(true)
        runtimeRecoveryFailure.compareAndSet(null, failure)
        recordPlaceholderDiagnostic(
            severity = SupportDiagnosticSeverity.Error,
            operation = "cloud-files.corrupt-root-recovery",
            outcome = "corrupt-root-recovery-scheduling-failed",
            code = windowsCloudFilesDiagnosticCode(failure),
            localDirectory = root,
            identity = null,
        )
        runCatching { onRuntimeFailure(failure) }
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
    }

    private fun prepareRootDirectory() {
        Files.createDirectories(root)
        check(!Files.isSymbolicLink(root)) { "The Windows Cloud Files root cannot be a symlink." }
    }

    /** Repairs the legacy namespace and flushes recoverable local writes before changing root generations. */
    fun recoverBeforeRootMigration(timeoutSeconds: Long = 120L) {
        require(timeoutSeconds > 0L)
        var deferredCorruption: WindowsCloudFilesCorruptEntryException? = null
        var deferredCorruptionGeneration: Long? = null
        synchronized(corruptRootStableAccessLock) {
            val inspectedGeneration = corruptRootRecoveryGeneration.get()
            runtimeRecoveryFailure.get()?.let { throw it }
            try {
                repairRemotePlaceholderTree()
            } catch (corruption: WindowsCloudFilesCorruptEntryException) {
                deferredCorruption = corruption
                deferredCorruptionGeneration = inspectedGeneration
            }
            if (deferredCorruption == null) {
                try {
                    recoverLocalPlaceholders(failClosed = true)
                    recoverUnmanagedLocalEntries(failClosed = true)
                } catch (corruption: WindowsCloudFilesCorruptEntryException) {
                    deferredCorruption = corruption
                    deferredCorruptionGeneration = inspectedGeneration
                }
            }
            check(initialRecoveryFinished.await(timeoutSeconds, TimeUnit.SECONDS)) {
                "Timed out while checking the legacy Windows Cloud Files root for local edits."
            }
            runtimeRecoveryFailure.get()?.let { throw it }
            when (val failure = initialRecoveryFailure) {
                is WindowsCloudFilesCorruptEntryException -> if (deferredCorruption == null) {
                    deferredCorruption = failure
                    deferredCorruptionGeneration = inspectedGeneration
                }
                null -> Unit
                else -> throw failure
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            awaitWindowsCloudFilesWritebackRecovery(deadline, ::accountRemovalMutationState)
        }
        deferredCorruption?.let { corruption ->
            val expectedGeneration = requireNotNull(deferredCorruptionGeneration)
            val rootIdentity = WindowsCloudFileIdentity(backend.accountId, "", "root", 0L, true)
            recoverCorruptRoot(
                WindowsCloudFileIdentityCodec.encode(rootIdentity),
                corruption,
                quiescenceTimeoutSeconds = timeoutSeconds,
                expectedGeneration = expectedGeneration,
            )
            initialRecoveryFailure = null
            synchronized(corruptRootStableAccessLock) {
                repairRemotePlaceholderTree()
            }
        }
    }

    /** Completes the initial local scan and repairs corruption before this provider is published as active. */
    fun recoverAfterStartup(timeoutSeconds: Long = 120L) {
        require(timeoutSeconds > 0L)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        check(initialRecoveryFinished.await(timeoutSeconds, TimeUnit.SECONDS)) {
            "Timed out while checking the Windows Cloud Files root for local metadata corruption."
        }
        awaitActivationDirectoryRefreshChecks(deadline)
        awaitPendingDelayedCorruptRootRecoveries(deadline)
        runtimeRecoveryFailure.get()?.let { throw it }
        when (val failure = initialRecoveryFailure) {
            is WindowsCloudFilesCorruptEntryException -> {
                val rootIdentity = WindowsCloudFileIdentity(backend.accountId, "", "root", 0L, true)
                recoverCorruptRoot(
                    WindowsCloudFileIdentityCodec.encode(rootIdentity),
                    failure,
                    quiescenceTimeoutSeconds = timeoutSeconds,
                )
                initialRecoveryFailure = null
            }
            null -> Unit
            else -> throw failure
        }
    }

    private fun awaitActivationDirectoryRefreshChecks(deadline: Long) {
        while (pendingActivationDirectoryRefreshChecks.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException(
                    "Interrupted while verifying Windows Cloud Files metadata before activation.",
                    interrupted,
                )
            }
        }
        check(pendingActivationDirectoryRefreshChecks.get() == 0) {
            "Timed out while verifying delayed Windows Cloud Files metadata before activation."
        }
    }

    private fun awaitPendingDelayedCorruptRootRecoveries(deadline: Long) {
        while (pendingDelayedCorruptRootRecoveries.isNotEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException(
                    "Interrupted while waiting for Windows Cloud Files recovery before activation.",
                    interrupted,
                )
            }
        }
        check(pendingDelayedCorruptRootRecoveries.isEmpty()) {
            "Timed out while recovering corrupt Windows Cloud Files metadata before activation."
        }
    }

    override fun fetchData(info: WindowsCloudCallbackInfo, requiredOffset: Long, requiredLength: Long) {
        if (callbacksPaused.get()) return
        val cancellation = AtomicBoolean(false)
        cancelledRequests[info.requestKey] = cancellation
        executor.execute {
            val identity = runCatching { requireIdentity(info, expectDirectory = false) }.getOrElse { failure ->
                api.failData(info, requiredOffset, requiredLength, failure.message ?: "Invalid placeholder identity")
                cancelledRequests.remove(info.requestKey)
                return@execute
            }
            try {
                backend.open(identity).use { source ->
                    check(source.size == identity.size) { "The remote file generation has a different size." }
                    planWindowsCloudHydration(requiredOffset, requiredLength, identity.size).forEach { range ->
                        if (cancellation.get()) return@execute
                        val bytes = source.read(range.offset, range.length)
                        check(bytes.size == range.length) { "The remote file returned an incomplete range." }
                        api.transferData(info, range.offset, bytes)
                    }
                }
            } catch (failure: Throwable) {
                if (!cancellation.get()) {
                    api.failData(info, requiredOffset, requiredLength, failure.message ?: "Hydration failed")
                }
            } finally {
                cancelledRequests.remove(info.requestKey)
            }
        }
    }

    override fun cancelFetchData(info: WindowsCloudCallbackInfo, offset: Long, length: Long) {
        cancelledRequests[info.requestKey]?.set(true)
    }

    override fun fetchPlaceholders(info: WindowsCloudCallbackInfo, pattern: String?) {
        if (callbacksPaused.get()) return
        val cancellation = AtomicBoolean(false)
        cancelledRequests[info.requestKey] = cancellation
        executor.execute {
            try {
                if (initialPopulationStarted.get()) {
                    check(
                        initialPopulationFinished.await(
                            DEFAULT_INITIAL_POPULATION_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS,
                        ),
                    ) { "Timed out while waiting for the initial Windows Cloud Files population." }
                    check(initialPopulationSucceeded && !callbacksPaused.get()) {
                        "The initial Windows Cloud Files population did not complete successfully."
                    }
                }
                if (cancellation.get()) return@execute
                val directory = requireIdentity(info, expectDirectory = true)
                // CFAPI permits returning entries beyond the requested pattern. Returning the complete
                // directory lets this transfer safely mark on-demand population as finished.
                val identities = backend.list(directory.path)
                    .filter { !cancellation.get() }
                identities.forEach { identity -> knownIdentities[identity.path] = identity }
                val placeholders = identities.map(::placeholder)
                if (!cancellation.get()) api.completePlaceholderFetch(info, placeholders)
            } catch (_: Throwable) {
                if (!cancellation.get()) api.failPlaceholderFetch(info)
            } finally {
                cancelledRequests.remove(info.requestKey)
            }
        }
    }

    override fun cancelFetchPlaceholders(info: WindowsCloudCallbackInfo) {
        cancelledRequests[info.requestKey]?.set(true)
    }

    override fun closed(info: WindowsCloudCallbackInfo, deleted: Boolean) {
        if (callbacksPaused.get() || deleted || info.fileIdentity == null) return
        val identity = runCatching { requireIdentity(info, expectDirectory = null) }.getOrNull() ?: return
        if (identity.directory) return
        val localPath = root.resolve(identity.path.replace('/', File.separatorChar)).normalize()
        if (!localPath.startsWith(root) || !Files.exists(localPath)) return
        synchronized(namespaceMutationLock) {
            if (callbacksPaused.get()) return
            if (api.placeholderState(localPath) != WindowsCloudPlaceholderState.Dirty) return
            pendingWritebacks += identity.path
            submitPathOperation(identity.path) {
                val current = api.placeholderIdentity(localPath)
                    ?.let { encoded -> runCatching { WindowsCloudFileIdentityCodec.decode(encoded) }.getOrNull() }
                    ?.takeIf { it.accountId == backend.accountId && it.path == identity.path && !it.directory }
                    ?: knownIdentities[identity.path]
                    ?: identity
                val uploaded = backend.upload(identity.path, localPath.toFile(), current.remoteRevision)
                knownIdentities[uploaded.path] = uploaded
                if (updatePlaceholderAfterRemoteMutation(localPath, uploaded)) {
                    api.markInSync(localPath)
                }
            }
        }
    }

    override fun deleteRequested(info: WindowsCloudCallbackInfo) {
        submitDestructiveCallback(
            onRejected = { api.acknowledgeDelete(info, false) },
        ) {
            val accepted = synchronized(namespaceMutationLock) {
                runCatching {
                    val identity = requireIdentity(info, expectDirectory = null)
                    backend.delete(identity)
                    knownIdentities.remove(identity.path)
                }.isSuccess
            }
            api.acknowledgeDelete(info, accepted)
        }
    }

    override fun renameRequested(info: WindowsCloudCallbackInfo, targetPath: String) {
        submitDestructiveCallback(
            onRejected = { api.acknowledgeRename(info, false) },
        ) {
            val accepted = synchronized(namespaceMutationLock) {
                runCatching {
                    val identity = requireIdentity(info, expectDirectory = null)
                    val destination = relativePath(targetPath)
                    val destinationPath = root.resolve(destination.replace('/', File.separatorChar)).normalize()
                    require(!hasUncommittedChangeWithin(identity.path, destinationPath)) {
                        "The Windows placeholder cannot be renamed until its local changes are uploaded."
                    }
                    val moved = backend.move(identity, destination)
                    knownIdentities.remove(identity.path)
                    knownIdentities[moved.path] = moved
                    if (
                        updatePlaceholderAfterRemoteMutation(
                            destinationPath,
                            moved,
                            preserveSyncState = true,
                        ) && identity.directory
                    ) {
                        rebindMovedDescendants(identity, moved, destinationPath)
                    }
                }.isSuccess
            }
            api.acknowledgeRename(info, accepted)
        }
    }

    private fun submitDestructiveCallback(
        onRejected: () -> Unit,
        operation: () -> Unit,
    ) {
        val accepted = synchronized(namespaceMutationLock) {
            if (callbacksPaused.get()) {
                false
            } else {
                destructiveCallbackOperations.incrementAndGet()
                true
            }
        }
        if (!accepted) {
            onRejected()
            return
        }
        try {
            executor.execute {
                try {
                    operation()
                } finally {
                    check(destructiveCallbackOperations.decrementAndGet() >= 0)
                }
            }
        } catch (failure: Throwable) {
            check(destructiveCallbackOperations.decrementAndGet() >= 0)
            onRejected()
            throw failure
        }
    }

    /** Handles local files or complete directory trees that do not have Cloud Files identities yet. */
    fun localEntryChanged(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(root.toAbsolutePath().normalize()) || normalized == root) return
        val deferred = synchronized(namespaceMutationLock) {
            if (callbacksPaused.get()) {
                if (!runtimeStopping.get()) deferredLocalChanges.add(normalized)
                true
            } else {
                false
            }
        }
        if (deferred) return
        if (!Files.exists(normalized) || api.placeholderState(normalized) != WindowsCloudPlaceholderState.Absent) return
        val relative = root.toAbsolutePath().normalize().relativize(normalized)
            .joinToString("/") { it.toString() }.windowsCloudPath()
        submitPathOperation(
            relative,
            deferredWhenPaused = { if (!runtimeStopping.get()) deferredLocalChanges.add(normalized) },
        ) {
            if (Files.isDirectory(normalized)) uploadLocalTree(normalized) else uploadLocalEntry(normalized, relative)
        }
    }

    fun freeUpSpace(requestedBytes: Long): Long = synchronized(corruptRootStableAccessLock) {
        if (corruptRootRecoveryClaimed.get() || runtimeStopping.get()) return@synchronized 0L
        freeUpSpaceWithStableRoot(requestedBytes)
    }

    private fun freeUpSpaceWithStableRoot(requestedBytes: Long): Long {
        require(requestedBytes >= 0L)
        var freed = 0L
        knownIdentities.values.asSequence()
            .filter { !it.directory }
            .filter { identity -> !api.isPinned(localPath(identity)) }
            .sortedBy { identity -> api.lastAccessedAtEpochMillis(localPath(identity)) }
            .forEach { identity ->
                if (requestedBytes > 0L && freed >= requestedBytes) return@forEach
                val path = localPath(identity)
                if (path.startsWith(root) && api.placeholderState(path) == WindowsCloudPlaceholderState.InSync) {
                    val allocated = api.allocatedBytes(path)
                    api.dehydrate(path)
                    freed += allocated
                }
            }
        return freed
    }

    fun enforcePolicy(
        policy: VirtualFileCachePolicy,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Long = synchronized(corruptRootStableAccessLock) {
        if (corruptRootRecoveryClaimed.get() || runtimeStopping.get()) return@synchronized 0L
        enforcePolicyWithStableRoot(policy, nowEpochMillis)
    }

    private fun enforcePolicyWithStableRoot(policy: VirtualFileCachePolicy, nowEpochMillis: Long): Long {
        if (!policy.automaticCleanup) return 0L
        val entries = knownIdentities.values.mapNotNull { identity ->
            if (identity.directory) return@mapNotNull null
            val path = localPath(identity)
            val allocated = api.allocatedBytes(path)
            if (allocated <= 0L) return@mapNotNull null
            val accessed = api.lastAccessedAtEpochMillis(path).coerceAtLeast(0L)
            VirtualFileCacheEntry(
                key = FileOfflineKey(backend.accountId, identity.path),
                remoteRevision = identity.remoteRevision,
                localRevision = identity.remoteRevision,
                sizeBytes = allocated,
                cachedAtEpochMillis = accessed,
                lastAccessedAtEpochMillis = accessed,
                retention = if (api.isPinned(path)) VirtualFileRetention.Pinned else VirtualFileRetention.Automatic,
                dirty = api.placeholderState(path) == WindowsCloudPlaceholderState.Dirty,
            )
        }
        val plan = planVirtualFileEviction(
            entries = entries,
            policy = policy,
            availableFreeBytes = Files.getFileStore(root).usableSpace,
            nowEpochMillis = nowEpochMillis,
        )
        var freed = 0L
        plan.evictions.forEach { eviction ->
            val identity = knownIdentities[eviction.key.relativePath] ?: return@forEach
            val path = localPath(identity)
            if (
                !api.isPinned(path) &&
                api.placeholderState(path) == WindowsCloudPlaceholderState.InSync &&
                api.allocatedBytes(path) == eviction.sizeBytes
            ) {
                api.dehydrate(path)
                freed += eviction.sizeBytes
            }
        }
        return freed
    }

    fun summary(): WindowsCloudFilesSummary = synchronized(corruptRootStableAccessLock) {
        summaryWithStableRoot()
    }

    private fun summaryWithStableRoot(): WindowsCloudFilesSummary {
        var cached = 0L
        var reclaimable = 0L
        var pinned = 0L
        var hydratedCount = 0
        var pinnedCount = 0
        knownIdentities.values.forEach { identity ->
            if (identity.directory) return@forEach
            val path = localPath(identity)
            val allocated = api.allocatedBytes(path).coerceAtLeast(0L)
            if (allocated > 0L) hydratedCount += 1
            cached += allocated
            if (api.isPinned(path)) {
                pinned += allocated
                pinnedCount += 1
            } else if (api.placeholderState(path) == WindowsCloudPlaceholderState.InSync) {
                reclaimable += allocated
            }
        }
        return WindowsCloudFilesSummary(
            cachedBytes = cached,
            reclaimableBytes = reclaimable,
            pinnedBytes = pinned,
            hydratedFileCount = hydratedCount,
            pinnedFileCount = pinnedCount,
            availableFreeBytes = Files.getFileStore(root).usableSpace,
            pendingWritebackCount = pendingWritebacks.size,
            failedWritebackCount = failedWritebacks.size,
        )
    }

    fun removeSyncRoot() {
        stopRuntime()
        api.unregisterSyncRoot(root)
        closeApi()
    }

    internal fun quiesceWritesForAccountRemoval(timeoutSeconds: Long = DEFAULT_CORRUPT_ROOT_QUIESCENCE_TIMEOUT_SECONDS) =
        accountRemovalQuiescence.tryQuiesce(timeoutSeconds)
    internal fun resumeWritesAfterAccountRemovalFailure() = resumeCallbacksAndReplayLocalChanges()

    override fun close() {
        stopRuntime()
        closeApi()
    }

    private fun stopRuntime() {
        synchronized(corruptRootRecoveryLifecycleLock) {
            runtimeStopping.set(true)
            accountRemovalPaused.set(false)
            callbacksPaused.set(true)
        }
        awaitCorruptRootRecoveryCompletion(
            System.nanoTime() + TimeUnit.SECONDS.toNanos(DEFAULT_CORRUPT_ROOT_QUIESCENCE_TIMEOUT_SECONDS),
        )
        localChangeScheduler.shutdownNow()
        val key = connection.get()
        if (key != 0L) {
            api.disconnect(key)
            connection.compareAndSet(key, 0L)
        }
        cancelledRequests.values.forEach { it.set(true) }
        cancelledRequests.clear()
        stopLocalWatcher()
        queuedPathOperations.clear()
        executor.shutdownNow()
    }

    private fun awaitCorruptRootRecoveryCompletion(deadline: Long) {
        while (corruptRootRecoveryClaimed.get() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException(
                    "Interrupted while waiting for Windows Cloud Files recovery to stop.",
                    interrupted,
                )
            }
        }
        check(!corruptRootRecoveryClaimed.get()) {
            "Timed out while waiting for Windows Cloud Files recovery to stop."
        }
    }

    private fun stopLocalWatcher() {
        runCatching { watchService?.close() }
        watcherThread?.interrupt()
        watcherThread = null
        watchService = null
        pendingLocalChanges.values.forEach { it.cancel(false) }
        pendingLocalChanges.clear()
        deferredLocalChanges.clear()
    }

    private fun resumeCallbacksAndReplayLocalChanges() {
        if (connection.get() == 0L && !runtimeStopping.get()) connection.set(api.connect(root, this))
        if (watchService == null && initialPopulationSucceeded && !runtimeStopping.get()) startLocalWatcher()
        val replay = synchronized(corruptRootRecoveryLifecycleLock) {
            synchronized(namespaceMutationLock) {
                if (runtimeStopping.get()) return
                accountRemovalPaused.set(false)
                callbacksPaused.set(false)
                deferredLocalChanges.toList().also(deferredLocalChanges::removeAll)
            }
        }
        replay.forEach(::scheduleLocalChange)
    }

    private fun pauseCallbacksForAccountRemoval(): Boolean {
        val paused = synchronized(corruptRootRecoveryLifecycleLock) {
            if (
                runtimeStopping.get() || corruptRootRecoveryClaimed.get() || runtimeRecoveryFailure.get() != null
            ) return@synchronized false
            synchronized(namespaceMutationLock) {
                if (callbacksPaused.get()) false else true.also {
                    accountRemovalPaused.set(it)
                    callbacksPaused.set(it)
                }
            }
        }
        if (!paused) return false
        val key = connection.get()
        if (key != 0L) {
            api.disconnect(key)
            check(connection.compareAndSet(key, 0L)) {
                "The Windows Cloud Files connection changed during account removal."
            }
        }
        stopLocalWatcherForAccountRemoval()
        val deadline = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(DEFAULT_CORRUPT_ROOT_QUIESCENCE_TIMEOUT_SECONDS)
        awaitWindowsCloudFilesPathOperationQuiescence(deadline, ::accountRemovalMutationState)
        recoverLocalPlaceholders(failClosed = true, allowWhilePaused = true)
        awaitWindowsCloudFilesWritebackRecovery(deadline, ::accountRemovalMutationState)
        recoverUnmanagedLocalEntries(failClosed = true)
        synchronized(namespaceMutationLock) { deferredLocalChanges.clear() }
        return true
    }

    private fun accountRemovalMutationState() = synchronized(queuedPathOperations) {
        WindowsCloudFilesMutationState(
            pendingWritebacks.size, failedWritebacks.size, pathOperations.size,
            queuedPathOperations.size, destructiveCallbackOperations.get(),
            pendingLocalChanges.size, deferredLocalChanges.size,
        )
    }

    private fun stopLocalWatcherForAccountRemoval() {
        runCatching { watchService?.close() }
        watcherThread?.interrupt()
        watcherThread = null
        watchService = null
        val scheduled = synchronized(namespaceMutationLock) {
            pendingLocalChanges.values.toList().also { pendingLocalChanges.clear() }
        }
        scheduled.forEach { it.cancel(false) }
        localChangeScheduler.submit(Runnable {}).get(
            DEFAULT_CORRUPT_ROOT_QUIESCENCE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun closeApi() {
        if (apiClosed.compareAndSet(false, true)) api.close()
    }
    private fun populateDirectory(relativePath: String, localDirectory: Path) {
        val identities = backend.list(relativePath)
        val missing = ArrayList<WindowsCloudFileIdentity>()
        identities.forEach { identity ->
            val localPath = localDirectory.resolve(identity.path.substringAfterLast('/'))
            val inspection = api.inspectPlaceholder(localPath)
            when (inspection.state) {
                WindowsCloudPlaceholderEntryState.Missing -> missing += identity
                WindowsCloudPlaceholderEntryState.Local -> Unit
                WindowsCloudPlaceholderEntryState.InSync,
                WindowsCloudPlaceholderEntryState.Dirty,
                -> reconcileExistingPlaceholder(identity, localPath, inspection.placeholderState)
                WindowsCloudPlaceholderEntryState.Corrupt -> throw corruptPlaceholder(identity, localDirectory, inspection)
                WindowsCloudPlaceholderEntryState.Unreadable -> {
                    recordPlaceholderDiagnostic(
                        severity = SupportDiagnosticSeverity.Error,
                        operation = "cloud-files.placeholder-inspection",
                        outcome = "inspection-failed",
                        code = inspection.win32Error?.let(::windowsErrorDiagnosticCode),
                        localDirectory = localDirectory,
                        identity = identity,
                        fields = inspection.diagnosticFields(),
                    )
                    throw IllegalStateException(
                        "Windows could not inspect an existing Cloud Files entry safely" +
                            inspection.win32Error?.let { " (Win32 error $it)." }.orEmpty(),
                    )
                }
            }
        }
        createMissingPlaceholders(relativePath, localDirectory, missing)
    }

    private fun createMissingPlaceholders(
        relativePath: String,
        localDirectory: Path,
        identities: List<WindowsCloudFileIdentity>,
    ) {
        if (identities.isEmpty()) return
        try {
            api.createPlaceholders(localDirectory, identities.map(::placeholder))
            identities.forEach { identity -> knownIdentities[identity.path] = identity }
        } catch (failure: WindowsCloudFilesOperationException) {
            if (!isWindowsCloudFilesPlaceholderAlreadyExistsResult(failure.hResult)) throw failure
            recordPlaceholderDiagnostic(
                severity = SupportDiagnosticSeverity.Warning,
                outcome = "collision-detected",
                code = "HRESULT:0x${failure.hResult.toUInt().toString(16)}",
                localDirectory = localDirectory,
                identity = null,
                fields = listOf(SupportDiagnosticFieldDraft("batch_size", identities.size.toString())),
            )
            val refreshed = backend.list(relativePath).associateBy { identity -> identity.path }
            identities.forEach { identity ->
                reconcilePlaceholderCreationCollision(
                    localDirectory = localDirectory,
                    listedIdentity = identity,
                    authoritativeIdentity = refreshed[identity.path],
                    initialFailure = failure,
                )
            }
        }
    }

    private fun reconcilePlaceholderCreationCollision(
        localDirectory: Path,
        listedIdentity: WindowsCloudFileIdentity,
        authoritativeIdentity: WindowsCloudFileIdentity?,
        initialFailure: WindowsCloudFilesOperationException,
    ) {
        val localPath = localDirectory.resolve(listedIdentity.path.substringAfterLast('/'))
        var collision = initialFailure
        var identityUnavailable = false
        repeat(MAX_PLACEHOLDER_COLLISION_RETRIES) {
            val inspection = api.inspectPlaceholder(localPath)
            if (inspection.state == WindowsCloudPlaceholderEntryState.Corrupt) {
                throw corruptPlaceholder(listedIdentity, localDirectory, inspection, collision)
            }
            if (inspection.state == WindowsCloudPlaceholderEntryState.Unreadable) {
                recordPlaceholderDiagnostic(
                    severity = SupportDiagnosticSeverity.Error,
                    operation = "cloud-files.placeholder-inspection",
                    outcome = "inspection-failed",
                    code = inspection.win32Error?.let(::windowsErrorDiagnosticCode),
                    localDirectory = localDirectory,
                    identity = listedIdentity,
                    fields = inspection.diagnosticFields(),
                )
                throw IllegalStateException(
                    "Windows could not inspect a collided Cloud Files entry safely" +
                        inspection.win32Error?.let { " (Win32 error $it)." }.orEmpty(),
                    collision,
                )
            }
            val state = inspection.placeholderState
            if (
                inspection.state == WindowsCloudPlaceholderEntryState.InSync ||
                inspection.state == WindowsCloudPlaceholderEntryState.Dirty
            ) {
                if (
                    reconcileCollidedPlaceholder(
                        listedIdentity,
                        authoritativeIdentity,
                        localPath,
                        state,
                    )
                ) {
                    recordPlaceholderDiagnostic(
                        severity = SupportDiagnosticSeverity.Info,
                        outcome = "collision-reconciled",
                        localDirectory = localDirectory,
                        identity = listedIdentity,
                        fields = listOf(SupportDiagnosticFieldDraft("placeholder_state", state.name.lowercase())),
                    )
                    return
                }
                identityUnavailable = true
                Thread.sleep(PLACEHOLDER_COLLISION_RETRY_DELAY_MILLIS)
                return@repeat
            }
            if (inspection.state == WindowsCloudPlaceholderEntryState.Local) {
                // Preserve an ordinary local entry. Startup recovery will import it with
                // create-only remote semantics instead of replacing either copy.
                recordPlaceholderDiagnostic(
                    severity = SupportDiagnosticSeverity.Warning,
                    outcome = "local-entry-preserved",
                    localDirectory = localDirectory,
                    identity = listedIdentity,
                )
                return
            }
            val creationIdentity = authoritativeIdentity ?: run {
                recordPlaceholderDiagnostic(
                    severity = SupportDiagnosticSeverity.Warning,
                    outcome = "remote-entry-missing",
                    localDirectory = localDirectory,
                    identity = listedIdentity,
                )
                return
            }
            try {
                api.createPlaceholders(localDirectory, listOf(placeholder(creationIdentity)))
                knownIdentities[creationIdentity.path] = creationIdentity
                recordPlaceholderDiagnostic(
                    severity = SupportDiagnosticSeverity.Info,
                    outcome = "created-on-retry",
                    localDirectory = localDirectory,
                    identity = creationIdentity,
                )
                return
            } catch (failure: WindowsCloudFilesOperationException) {
                if (!isWindowsCloudFilesPlaceholderAlreadyExistsResult(failure.hResult)) throw failure
                collision = failure
            }
        }
        if (identityUnavailable) {
            recordPlaceholderDiagnostic(
                severity = SupportDiagnosticSeverity.Error,
                outcome = "identity-unavailable",
                code = "HRESULT:0x${collision.hResult.toUInt().toString(16)}",
                localDirectory = localDirectory,
                identity = listedIdentity,
            )
            throw IllegalStateException(
                "Could not verify the existing Windows Cloud Files placeholder after a creation collision.",
                collision,
            )
        }
        recordPlaceholderDiagnostic(
            severity = SupportDiagnosticSeverity.Error,
            outcome = "collision-retry-exhausted",
            code = "HRESULT:0x${collision.hResult.toUInt().toString(16)}",
            localDirectory = localDirectory,
            identity = listedIdentity,
        )
        throw collision
    }

    private fun recordPlaceholderDiagnostic(
        severity: SupportDiagnosticSeverity,
        operation: String = "cloud-files.placeholder-collision",
        outcome: String,
        localDirectory: Path,
        identity: WindowsCloudFileIdentity?,
        code: String? = null,
        fields: List<SupportDiagnosticFieldDraft> = emptyList(),
    ) {
        runCatching {
            recordDiagnostic(
                SupportDiagnosticEventDraft(
                    severity = severity,
                    component = SupportDiagnosticComponent.VirtualFiles,
                    operation = operation,
                    outcome = outcome,
                    code = code,
                    fields = buildList {
                        add(
                            SupportDiagnosticFieldDraft(
                                "local_directory",
                                localDirectory.toAbsolutePath().toString(),
                                SupportDiagnosticValuePrivacy.LocalPath,
                            ),
                        )
                        identity?.let {
                            add(
                                SupportDiagnosticFieldDraft(
                                    "account",
                                    it.accountId,
                                    SupportDiagnosticValuePrivacy.Identifier,
                                ),
                            )
                            add(
                                SupportDiagnosticFieldDraft(
                                    "remote_path",
                                    it.path,
                                    SupportDiagnosticValuePrivacy.RemotePath,
                                ),
                            )
                            add(SupportDiagnosticFieldDraft("directory", it.directory.toString()))
                        }
                        addAll(fields)
                    },
                ),
            )
        }
    }

    private fun corruptPlaceholder(
        identity: WindowsCloudFileIdentity?,
        localDirectory: Path,
        inspection: WindowsCloudPlaceholderInspection,
        cause: Throwable? = null,
    ): WindowsCloudFilesCorruptEntryException {
        recordPlaceholderDiagnostic(
            severity = SupportDiagnosticSeverity.Error,
            operation = "cloud-files.placeholder-inspection",
            outcome = "corrupt-entry-detected",
            code = inspection.win32Error?.let(::windowsErrorDiagnosticCode),
            localDirectory = localDirectory,
            identity = identity,
            fields = inspection.diagnosticFields(),
        )
        return WindowsCloudFilesCorruptEntryException(inspection, cause)
    }

    private fun reconcileCollidedPlaceholder(
        listedIdentity: WindowsCloudFileIdentity,
        authoritativeIdentity: WindowsCloudFileIdentity?,
        localPath: Path,
        state: WindowsCloudPlaceholderState,
    ): Boolean {
        val existing = api.placeholderIdentity(localPath)
            ?.let { encoded -> runCatching { WindowsCloudFileIdentityCodec.decode(encoded) }.getOrNull() }
            ?: return false
        check(existing.accountId == backend.accountId && existing.path == listedIdentity.path) {
            "The Windows Cloud Files directory contains remote entries that resolve to the same Windows path."
        }
        if (state == WindowsCloudPlaceholderState.Dirty) {
            knownIdentities[existing.path] = existing
            return true
        }
        check(state == WindowsCloudPlaceholderState.InSync)
        var authoritative = requireNotNull(authoritativeIdentity) {
            "The remote item disappeared while reconciling a Windows placeholder collision."
        }.also { current ->
            require(current.accountId == backend.accountId && current.path == listedIdentity.path) {
                "The resolved Windows placeholder identity does not match the requested remote item."
            }
        }
        if (existing != authoritative) {
            authoritative = requireNotNull(backend.resolve(listedIdentity.path)) {
                "The remote item disappeared while revalidating a Windows placeholder collision."
            }.also { current ->
                require(current.accountId == backend.accountId && current.path == listedIdentity.path) {
                    "The revalidated Windows placeholder identity does not match the requested remote item."
                }
            }
            val rechecked = api.placeholderIdentity(localPath)
                ?.let { encoded -> runCatching { WindowsCloudFileIdentityCodec.decode(encoded) }.getOrNull() }
                ?: return false
            if (rechecked != existing) return false
        }
        val contentChanged = placeholderContentChanged(existing, authoritative)
        if (existing != authoritative || authoritative.directory) {
            updateExistingPlaceholder(
                localPath,
                previous = existing,
                current = authoritative,
                contentChanged = contentChanged,
            )
        }
        knownIdentities[authoritative.path] = authoritative
        return true
    }

    private fun reconcileExistingPlaceholder(
        identity: WindowsCloudFileIdentity,
        localPath: Path,
        state: WindowsCloudPlaceholderState,
    ) {
        val previous = api.placeholderIdentity(localPath)
            ?.let { encoded -> runCatching { WindowsCloudFileIdentityCodec.decode(encoded) }.getOrNull() }
        when (state) {
            WindowsCloudPlaceholderState.InSync -> {
                check(previous == null || previous.accountId == backend.accountId && previous.path == identity.path) {
                    "The Windows Cloud Files directory contains remote entries that resolve to the same Windows path."
                }
                val contentChanged = previous == null || placeholderContentChanged(previous, identity)
                if (previous != identity || identity.directory) {
                    updateExistingPlaceholder(
                        localPath,
                        previous = previous,
                        current = identity,
                        contentChanged = contentChanged,
                    )
                }
                knownIdentities[identity.path] = identity
            }
            WindowsCloudPlaceholderState.Dirty -> {
                check(previous != null && previous.accountId == backend.accountId && previous.path == identity.path) {
                    "The dirty Windows Cloud Files placeholder does not have a verified identity."
                }
                knownIdentities[identity.path] = previous
            }
            WindowsCloudPlaceholderState.Absent -> Unit
        }
    }

    private fun updateExistingPlaceholder(
        localPath: Path,
        previous: WindowsCloudFileIdentity?,
        current: WindowsCloudFileIdentity,
        contentChanged: Boolean,
    ) {
        try {
            api.updatePlaceholder(
                localPath,
                placeholder(current),
                invalidateContent = contentChanged && !current.directory,
            )
        } catch (failure: WindowsCloudFilesOperationException) {
            val unchangedDirectoryRefresh = previous == current && current.directory
            val corruptMetadata = isWindowsCloudFilesPlaceholderMetadataCorruptResult(failure.hResult)
            recordPlaceholderDiagnostic(
                severity = if (unchangedDirectoryRefresh && !corruptMetadata) {
                    SupportDiagnosticSeverity.Warning
                } else {
                    SupportDiagnosticSeverity.Error
                },
                operation = "cloud-files.placeholder-update",
                outcome = when {
                    corruptMetadata -> "corrupt-metadata-detected"
                    unchangedDirectoryRefresh -> "unchanged-refresh-skipped"
                    else -> "failed"
                },
                code = "HRESULT:0x${failure.hResult.toUInt().toString(16)}",
                localDirectory = localPath.parent ?: root,
                identity = current,
                fields = listOf(
                    SupportDiagnosticFieldDraft("content_changed", contentChanged.toString()),
                    SupportDiagnosticFieldDraft("identity_changed", (previous != current).toString()),
                ),
            )
            if (corruptMetadata) {
                throw corruptPlaceholderUpdate(localPath, current, failure)
            }
            if (!unchangedDirectoryRefresh) throw failure
            scheduleUnchangedDirectoryRefresh(localPath, current, attempt = 1)
        }
    }

    private fun scheduleUnchangedDirectoryRefresh(
        localPath: Path,
        identity: WindowsCloudFileIdentity,
        attempt: Int,
    ) {
        if (callbacksPaused.get()) return
        val gatesActivation = attempt == 1 && runtimeStarted.count > 0L
        if (gatesActivation) pendingActivationDirectoryRefreshChecks.incrementAndGet()
        fun completeActivationGate() {
            if (gatesActivation) check(pendingActivationDirectoryRefreshChecks.decrementAndGet() >= 0)
        }
        fun failScheduling(failure: Throwable) {
            completeActivationGate()
            recordPlaceholderDiagnostic(
                severity = SupportDiagnosticSeverity.Warning,
                operation = "cloud-files.placeholder-update",
                outcome = "unchanged-refresh-scheduling-failed",
                code = windowsCloudFilesDiagnosticCode(failure),
                localDirectory = localPath.parent ?: root,
                identity = identity,
                fields = listOf(SupportDiagnosticFieldDraft("attempt", attempt.toString())),
            )
        }
        val delayMillis = directoryRefreshRetryDelayMillis(attempt).coerceAtLeast(0L)
        try {
            localChangeScheduler.schedule(
                {
                    if (callbacksPaused.get()) {
                        completeActivationGate()
                        return@schedule
                    }
                    try {
                        executor.execute {
                            try {
                                if (!callbacksPaused.get()) {
                                    retryUnchangedDirectoryRefresh(localPath, identity, attempt)
                                }
                            } finally {
                                completeActivationGate()
                            }
                        }
                    } catch (failure: Throwable) {
                        failScheduling(failure)
                    }
                },
                delayMillis,
                TimeUnit.MILLISECONDS,
            )
        } catch (failure: Throwable) {
            failScheduling(failure)
        }
    }

    private fun retryUnchangedDirectoryRefresh(
        localPath: Path,
        identity: WindowsCloudFileIdentity,
        attempt: Int,
    ) {
        synchronized(namespaceMutationLock) {
            if (callbacksPaused.get()) return
            val recoveryGeneration = corruptRootRecoveryGeneration.get()
            val inspection = api.inspectPlaceholder(localPath)
            if (inspection.state == WindowsCloudPlaceholderEntryState.Corrupt) {
                scheduleCorruptRootRecoveryAfterStartup(
                    WindowsCloudFilesDelayedCorruptRootRecovery(
                        corruptPlaceholder(identity, localPath.parent ?: root, inspection),
                        recoveryGeneration,
                    ),
                )
                return
            }
            val currentIdentity = if (
                inspection.state == WindowsCloudPlaceholderEntryState.InSync &&
                inspection.placeholderState == WindowsCloudPlaceholderState.InSync
            ) {
                api.placeholderIdentity(localPath)
                    ?.let { encoded -> runCatching { WindowsCloudFileIdentityCodec.decode(encoded) }.getOrNull() }
            } else {
                null
            }
            if (currentIdentity != identity) {
                recordPlaceholderDiagnostic(
                    severity = SupportDiagnosticSeverity.Warning,
                    operation = "cloud-files.placeholder-update",
                    outcome = "unchanged-refresh-abandoned",
                    localDirectory = localPath.parent ?: root,
                    identity = identity,
                    fields = inspection.diagnosticFields() + listOf(
                        SupportDiagnosticFieldDraft("attempt", attempt.toString()),
                        SupportDiagnosticFieldDraft("identity_matches", (currentIdentity == identity).toString()),
                    ),
                )
                return
            }
            retryVerifiedUnchangedDirectoryRefresh(localPath, identity, attempt)?.let { corruption ->
                scheduleCorruptRootRecoveryAfterStartup(
                    WindowsCloudFilesDelayedCorruptRootRecovery(corruption, recoveryGeneration),
                )
            }
        }
    }

    private fun retryVerifiedUnchangedDirectoryRefresh(
        localPath: Path,
        identity: WindowsCloudFileIdentity,
        attempt: Int,
    ): WindowsCloudFilesCorruptEntryException? {
        try {
            api.updatePlaceholder(localPath, placeholder(identity), invalidateContent = false)
            recordPlaceholderDiagnostic(
                severity = SupportDiagnosticSeverity.Info,
                operation = "cloud-files.placeholder-update",
                outcome = "unchanged-refresh-recovered",
                localDirectory = localPath.parent ?: root,
                identity = identity,
                fields = listOf(SupportDiagnosticFieldDraft("attempt", attempt.toString())),
            )
            return null
        } catch (failure: WindowsCloudFilesOperationException) {
            val corruptMetadata = isWindowsCloudFilesPlaceholderMetadataCorruptResult(failure.hResult)
            val exhausted = attempt >= MAX_WINDOWS_DIRECTORY_REFRESH_ATTEMPTS
            recordPlaceholderDiagnostic(
                severity = if (corruptMetadata) {
                    SupportDiagnosticSeverity.Error
                } else {
                    SupportDiagnosticSeverity.Warning
                },
                operation = "cloud-files.placeholder-update",
                outcome = when {
                    corruptMetadata -> "corrupt-metadata-detected"
                    exhausted -> "unchanged-refresh-stale"
                    else -> "unchanged-refresh-retry-failed"
                },
                code = "HRESULT:0x${failure.hResult.toUInt().toString(16)}",
                localDirectory = localPath.parent ?: root,
                identity = identity,
                fields = listOf(SupportDiagnosticFieldDraft("attempt", attempt.toString())),
            )
            if (corruptMetadata) {
                return corruptPlaceholderUpdate(localPath, identity, failure)
            }
            if (!exhausted) scheduleUnchangedDirectoryRefresh(localPath, identity, attempt + 1)
            return null
        }
    }

    private fun corruptPlaceholderUpdate(
        localPath: Path,
        identity: WindowsCloudFileIdentity,
        failure: WindowsCloudFilesOperationException,
    ): WindowsCloudFilesCorruptEntryException = corruptPlaceholder(
        identity = identity,
        localDirectory = localPath.parent ?: root,
        inspection = WindowsCloudPlaceholderInspection(
            state = WindowsCloudPlaceholderEntryState.Corrupt,
            win32Error = WINDOWS_ERROR_CLOUD_FILE_METADATA_CORRUPT,
        ),
        cause = failure,
    )

    /**
     * Commits a remote mutation to its existing placeholder. A corrupt CFAPI update cannot
     * undo the already-completed server mutation, so finish the path operation and rebuild the
     * root from the authoritative backend instead of replaying that mutation on retry.
     */
    private fun updatePlaceholderAfterRemoteMutation(
        localPath: Path,
        identity: WindowsCloudFileIdentity,
        preserveSyncState: Boolean = false,
    ): Boolean {
        try {
            api.updatePlaceholder(
                localPath,
                placeholder(identity),
                preserveSyncState = preserveSyncState,
            )
            return true
        } catch (failure: WindowsCloudFilesOperationException) {
            if (!isWindowsCloudFilesPlaceholderMetadataCorruptResult(failure.hResult)) throw failure
            recordPlaceholderDiagnostic(
                severity = SupportDiagnosticSeverity.Error,
                operation = "cloud-files.placeholder-update",
                outcome = "corrupt-metadata-detected",
                code = "HRESULT:0x${failure.hResult.toUInt().toString(16)}",
                localDirectory = localPath.parent ?: root,
                identity = identity,
                fields = listOf(SupportDiagnosticFieldDraft("remote_mutation_completed", "true")),
            )
            scheduleCorruptRootRecoveryAfterStartup(
                WindowsCloudFilesDelayedCorruptRootRecovery(
                    corruptPlaceholderUpdate(localPath, identity, failure),
                    corruptRootRecoveryGeneration.get(),
                ),
            )
            return false
        }
    }

    private fun placeholderContentChanged(
        previous: WindowsCloudFileIdentity,
        current: WindowsCloudFileIdentity,
    ): Boolean = previous.remoteRevision != current.remoteRevision ||
        previous.size != current.size ||
        previous.directory != current.directory

    private fun requireIdentity(
        info: WindowsCloudCallbackInfo,
        expectDirectory: Boolean?,
    ): WindowsCloudFileIdentity {
        val bytes = requireNotNull(info.fileIdentity) { "The Cloud Files callback has no identity." }
        val identity = WindowsCloudFileIdentityCodec.decode(bytes)
        require(identity.accountId == backend.accountId) { "The Cloud Files callback belongs to another account." }
        requireWindowsCloudCallbackPath(root, info.normalizedPath, identity.path)
        if (expectDirectory != null) require(identity.directory == expectDirectory)
        require(identity.size == info.fileSize || identity.directory) { "The Cloud Files callback size is stale." }
        knownIdentities[identity.path] = identity
        return identity
    }

    private fun placeholder(identity: WindowsCloudFileIdentity): WindowsCloudPlaceholder = WindowsCloudPlaceholder(
        name = identity.path.substringAfterLast('/').ifBlank { "nati.ve" },
        identity = WindowsCloudFileIdentityCodec.encode(identity),
        size = identity.size,
        directory = identity.directory,
        lastModifiedEpochMillis = identity.lastModifiedEpochMillis,
    )

    private fun localPath(identity: WindowsCloudFileIdentity): Path =
        root.resolve(identity.path.replace('/', File.separatorChar)).normalize()

    private fun relativePath(absoluteTarget: String): String {
        val target = Path.of(absoluteTarget).toAbsolutePath().normalize()
        val absoluteRoot = root.toAbsolutePath().normalize()
        require(target.startsWith(absoluteRoot) && target != absoluteRoot)
        return absoluteRoot.relativize(target).joinToString("/") { it.toString() }.windowsCloudPath()
    }

    private fun submitPathOperation(
        path: String,
        deferredWhenPaused: () -> Unit = {},
        allowWhilePaused: Boolean = false,
        block: () -> Unit,
    ) {
        val shouldSchedule = synchronized(namespaceMutationLock) {
            if (callbacksPaused.get() && !allowWhilePaused) {
                deferredWhenPaused()
                return
            }
            failedWritebacks -= path
            writebackAttempts.remove(path)
            synchronized(queuedPathOperations) {
                queuedPathOperations[path] = block
                pathOperations.add(path)
            }
        }
        if (shouldSchedule) schedulePathOperationDrain(path)
    }

    private fun schedulePathOperationDrain(path: String, delayMillis: Long = 0L) {
        require(delayMillis >= 0L)
        val drain = {
            var failedOperation: (() -> Unit)? = null
            try {
                while (true) {
                    val next = synchronized(queuedPathOperations) {
                        queuedPathOperations.remove(path)
                    } ?: break
                    try {
                        next()
                        failedWritebacks -= path
                        writebackAttempts.remove(path)
                    } catch (_: Throwable) {
                        failedOperation = next
                        break
                    }
                }
            } finally {
                var retryDelay: Long? = null
                var rescheduleImmediately = false
                val shouldReschedule = synchronized(queuedPathOperations) {
                    pathOperations.remove(path)
                    if (failedOperation != null) {
                        val attempt = writebackAttempts.merge(path, 1, Int::plus) ?: 1
                        failedWritebacks += path
                        if (attempt < MAX_WINDOWS_WRITEBACK_ATTEMPTS) {
                            queuedPathOperations.putIfAbsent(path, requireNotNull(failedOperation))
                            retryDelay = writebackRetryDelayMillis(attempt).coerceAtLeast(0L)
                            pathOperations.add(path)
                        } else if (queuedPathOperations.containsKey(path)) {
                            writebackAttempts.remove(path)
                            failedWritebacks -= path
                            rescheduleImmediately = pathOperations.add(path)
                        }
                        false
                    } else if (queuedPathOperations.containsKey(path)) {
                        pathOperations.add(path)
                    } else {
                        pendingWritebacks -= path
                        failedWritebacks -= path
                        writebackAttempts.remove(path)
                        false
                    }
                }
                if (shouldReschedule) schedulePathOperationDrain(path)
                if (rescheduleImmediately) schedulePathOperationDrain(path)
                retryDelay?.let { delay -> schedulePathOperationDrain(path, delay) }
            }
        }
        if (delayMillis == 0L) {
            runCatching { executor.execute(drain) }
        } else {
            runCatching {
                localChangeScheduler.schedule(
                    { runCatching { executor.execute(drain) } },
                    delayMillis,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    private fun startLocalWatcher() {
        val watcher = root.fileSystem.newWatchService()
        Files.walk(root).use { paths ->
            paths.filter(Files::isDirectory).forEach { directory -> directory.registerForWindowsCloudChanges(watcher) }
        }
        watchService = watcher
        watcherThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val key = try {
                    watcher.take()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    return@Thread
                }
                val directory = key.watchable() as? Path
                if (directory != null) {
                    key.pollEvents().forEach { event ->
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) return@forEach
                        val child = directory.resolve(event.context() as Path).toAbsolutePath().normalize()
                        if (
                            event.kind() == StandardWatchEventKinds.ENTRY_CREATE &&
                            Files.isDirectory(child) &&
                            !Files.isSymbolicLink(child)
                        ) {
                            runCatching {
                                Files.walk(child).use { descendants ->
                                    descendants.filter { path -> Files.isDirectory(path) && !Files.isSymbolicLink(path) }
                                        .forEach { descendant -> descendant.registerForWindowsCloudChanges(watcher) }
                                }
                            }
                        }
                        if (event.kind() != StandardWatchEventKinds.ENTRY_DELETE) scheduleLocalChange(child)
                    }
                }
                if (!key.reset()) continue
            }
        }, "nextcloud-windows-cloud-files-watcher").apply {
            isDaemon = true
            start()
        }
    }

    private fun scheduleLocalChange(path: Path) {
        synchronized(namespaceMutationLock) {
            if (callbacksPaused.get()) {
                if (!runtimeStopping.get()) deferredLocalChanges.add(path)
                return
            }
            pendingLocalChanges.remove(path)?.cancel(false)
            pendingLocalChanges[path] = localChangeScheduler.schedule(
                {
                    pendingLocalChanges.remove(path)
                    runCatching { localEntryChanged(path) }
                },
                LOCAL_CHANGE_SETTLE_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun recoverLocalChanges() {
        recoverLocalPlaceholders()
        val pendingDirectories = ArrayDeque<String>()
        pendingDirectories += ""
        var discovered = 0
        while (pendingDirectories.isNotEmpty() && discovered < MAX_RECOVERY_IDENTITIES) {
            val directory = pendingDirectories.removeFirst()
            val children = runCatching { backend.list(directory) }.getOrElse { emptyList() }
            children.forEach { identity ->
                knownIdentities[identity.path] = identity
                discovered += 1
                if (identity.directory) pendingDirectories += identity.path
            }
        }
        recoverUnmanagedLocalEntries()
    }

    private fun recoverUnmanagedLocalEntries(failClosed: Boolean = false) {
        val recover = {
            val unmanaged = Files.walk(root).use { paths ->
                paths.filter { path -> path != root && Files.exists(path) }
                    .filter { path ->
                        inspectPlaceholderForRecovery(path).state == WindowsCloudPlaceholderEntryState.Local
                    }
                    .sorted(compareBy<Path> { it.nameCount })
                    .toList()
            }
            unmanaged.forEach { path ->
                val relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                    .joinToString("/") { it.toString() }.windowsCloudPath()
                if (failClosed) uploadLocalEntry(path, relative)
                else runCatching { uploadLocalEntry(path, relative) }
                    .onFailure {
                        // The ordinary entry is the durable recovery copy. Rebuild its
                        // visible failure state on every startup until reconciliation succeeds.
                        failedWritebacks += relative
                    }
            }
        }
        if (failClosed) {
            recover()
        } else {
            try {
                recover()
            } catch (failure: WindowsCloudFilesStartupRecoveryException) {
                throw failure
            } catch (_: Throwable) {
                // Ordinary startup recovery is retried on the next launch.
            }
        }
    }

    private fun repairRemotePlaceholderTree() {
        val pendingDirectories = ArrayDeque<Pair<String, Path>>()
        pendingDirectories += "" to root
        var repaired = 0
        while (pendingDirectories.isNotEmpty()) {
            val (relativePath, localDirectory) = pendingDirectories.removeFirst()
            populateDirectory(relativePath, localDirectory)
            val children = backend.list(relativePath)
            repaired += children.size
            check(repaired <= MAX_RECOVERY_IDENTITIES) {
                "The legacy Windows Cloud Files root contains too many remote entries to migrate safely."
            }
            children.filter { it.directory }.forEach { child ->
                pendingDirectories += child.path to localPath(child)
            }
        }
    }

    private fun recoverLocalPlaceholders(
        failClosed: Boolean = false,
        allowWhilePaused: Boolean = false,
    ) {
        val recover = {
            Files.walk(root).use { paths ->
                paths.filter { path -> path != root && !Files.isSymbolicLink(path) }.forEach { local ->
                    val inspection = inspectPlaceholderForRecovery(local)
                    val state = inspection.placeholderState
                    if (
                        inspection.state == WindowsCloudPlaceholderEntryState.Missing ||
                        inspection.state == WindowsCloudPlaceholderEntryState.Local
                    ) return@forEach
                    val directory = Files.isDirectory(local, LinkOption.NOFOLLOW_LINKS)
                    if (!directory && !Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    val original = api.placeholderIdentity(local)
                        ?.let { encoded -> runCatching { WindowsCloudFileIdentityCodec.decode(encoded) }.getOrNull() }
                        ?.takeIf { identity ->
                            identity.accountId == backend.accountId &&
                                identity.directory == directory &&
                                localPath(identity).toAbsolutePath().normalize() == local.toAbsolutePath().normalize()
                        }
                        ?: return@forEach
                    knownIdentities[original.path] = original
                    if (state != WindowsCloudPlaceholderState.Dirty || original.directory) return@forEach
                    if (!pendingWritebacks.add(original.path)) return@forEach
                    submitPathOperation(
                        original.path,
                        deferredWhenPaused = { if (!runtimeStopping.get()) deferredLocalChanges.add(local) },
                        allowWhilePaused = allowWhilePaused,
                    ) {
                        val current = requireNotNull(api.placeholderIdentity(local)) {
                            "The dirty Windows placeholder has no recoverable identity."
                        }.let(WindowsCloudFileIdentityCodec::decode)
                        require(
                            current.accountId == backend.accountId &&
                                current.path == original.path &&
                                !current.directory,
                        ) { "The dirty Windows placeholder identity is not safe to recover." }
                        val uploaded = backend.upload(current.path, local.toFile(), current.remoteRevision)
                        knownIdentities[uploaded.path] = uploaded
                        if (updatePlaceholderAfterRemoteMutation(local, uploaded)) {
                            api.markInSync(local)
                        }
                    }
                }
            }
        }
        if (failClosed) {
            recover()
        } else {
            try {
                recover()
            } catch (failure: WindowsCloudFilesStartupRecoveryException) {
                throw failure
            } catch (_: Throwable) {
                // Ordinary startup recovery is retried on the next launch.
            }
        }
    }

    private fun inspectPlaceholderForRecovery(path: Path): WindowsCloudPlaceholderInspection {
        val inspection = api.inspectPlaceholder(path)
        when (inspection.state) {
            WindowsCloudPlaceholderEntryState.Corrupt -> throw corruptPlaceholder(
                identity = null,
                localDirectory = path.parent ?: root,
                inspection = inspection,
            )
            WindowsCloudPlaceholderEntryState.Unreadable -> {
                recordPlaceholderDiagnostic(
                    severity = SupportDiagnosticSeverity.Error,
                    operation = "cloud-files.placeholder-inspection",
                    outcome = "inspection-failed",
                    code = inspection.win32Error?.let(::windowsErrorDiagnosticCode),
                    localDirectory = path.parent ?: root,
                    identity = null,
                    fields = inspection.diagnosticFields(),
                )
                throw WindowsCloudFilesUnreadableEntryException(inspection)
            }
            else -> Unit
        }
        return inspection
    }

    private fun rebindMovedDescendants(
        originalDirectory: WindowsCloudFileIdentity,
        movedDirectory: WindowsCloudFileIdentity,
        localDirectory: Path,
    ) {
        if (!Files.isDirectory(localDirectory) || Files.isSymbolicLink(localDirectory)) return
        Files.walk(localDirectory).use { paths ->
            val descendants = paths.iterator()
            while (descendants.hasNext()) {
                val descendant = descendants.next()
                if (descendant == localDirectory || Files.isSymbolicLink(descendant)) continue
                val suffix = localDirectory.relativize(descendant).joinToString("/") { it.toString() }.windowsCloudPath()
                val expectedOriginalPath = "${originalDirectory.path}/$suffix"
                val previous = api.placeholderIdentity(descendant)
                    ?.let { encoded -> runCatching { WindowsCloudFileIdentityCodec.decode(encoded) }.getOrNull() }
                    ?.takeIf { identity ->
                        identity.accountId == backend.accountId && identity.path == expectedOriginalPath
                    }
                    ?: continue
                val rebound = previous.copy(path = "${movedDirectory.path}/$suffix")
                knownIdentities.remove(previous.path)
                knownIdentities[rebound.path] = rebound
                if (
                    !updatePlaceholderAfterRemoteMutation(
                        descendant,
                        rebound,
                        preserveSyncState = true,
                    )
                ) {
                    break
                }
            }
        }
    }

    private fun hasUncommittedChangeWithin(sourcePath: String, localDestination: Path): Boolean {
        val pending = sequenceOf(
            pendingWritebacks.asSequence(),
            failedWritebacks.asSequence(),
            pathOperations.asSequence(),
            synchronized(queuedPathOperations) { queuedPathOperations.keys.toList().asSequence() },
        ).flatten().any { path -> path == sourcePath || path.startsWith("$sourcePath/") }
        if (pending) return true
        if (!Files.exists(localDestination, LinkOption.NOFOLLOW_LINKS)) return false
        if (!Files.isDirectory(localDestination, LinkOption.NOFOLLOW_LINKS)) {
            return api.placeholderState(localDestination) == WindowsCloudPlaceholderState.Dirty
        }
        return Files.walk(localDestination).use { paths ->
            paths.anyMatch { path ->
                !Files.isSymbolicLink(path) && api.placeholderState(path) == WindowsCloudPlaceholderState.Dirty
            }
        }
    }

    private fun uploadLocalEntry(localPath: Path, relativePath: String) {
        require(localPath.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
        require(!Files.isSymbolicLink(localPath)) { "Windows Cloud Files does not import symbolic links." }
        require(Files.isDirectory(localPath) || Files.isRegularFile(localPath)) {
            "Windows Cloud Files only imports regular files and folders."
        }
        failedWritebacks -= relativePath
        pendingWritebacks += relativePath
        var completed = false
        try {
            val uploaded = try {
                if (Files.isDirectory(localPath)) {
                    backend.createDirectory(relativePath)
                } else {
                    backend.upload(relativePath, localPath.toFile(), expectedRemoteRevision = null)
                }
            } catch (failure: Throwable) {
                val reconciled = runCatching { backend.resolve(relativePath) }.getOrNull()
                    ?.takeIf { identity -> localEntryMatches(identity, localPath, relativePath) }
                if (reconciled == null) throw failure
                reconciled
            }
            knownIdentities[relativePath] = uploaded
            api.convertToPlaceholder(localPath, placeholder(uploaded))
            api.markInSync(localPath)
            completed = true
        } finally {
            if (completed) pendingWritebacks -= relativePath
        }
    }

    private fun uploadLocalTree(localDirectory: Path) {
        val absoluteRoot = root.toAbsolutePath().normalize()
        val normalizedDirectory = localDirectory.toAbsolutePath().normalize()
        require(normalizedDirectory.startsWith(absoluteRoot) && normalizedDirectory != absoluteRoot)
        val entries = Files.walk(normalizedDirectory).use { paths ->
            paths.limit(MAX_RECOVERY_IDENTITIES.toLong() + 1L)
                .sorted(compareBy<Path> { it.nameCount })
                .toList()
        }
        require(entries.size <= MAX_RECOVERY_IDENTITIES) {
            "The new Windows folder contains too many entries to import safely."
        }
        entries.forEach { entry ->
            val normalized = entry.toAbsolutePath().normalize()
            require(normalized.startsWith(normalizedDirectory) && !Files.isSymbolicLink(normalized)) {
                "The new Windows folder contains an unsafe entry."
            }
            if (Files.exists(normalized) && api.placeholderState(normalized) == WindowsCloudPlaceholderState.Absent) {
                val relative = absoluteRoot.relativize(normalized)
                    .joinToString("/") { it.toString() }.windowsCloudPath()
                uploadLocalEntry(normalized, relative)
            }
        }
    }

    private fun localEntryMatches(
        identity: WindowsCloudFileIdentity,
        localPath: Path,
        relativePath: String,
    ): Boolean {
        if (identity.accountId != backend.accountId || identity.path != relativePath) return false
        val localDirectory = Files.isDirectory(localPath)
        if (identity.directory != localDirectory) return false
        if (localDirectory) return true
        if (!Files.isRegularFile(localPath) || identity.size != Files.size(localPath)) return false
        if (identity.size == 0L) return true
        return runCatching {
            backend.open(identity).use remoteUse@ { remote ->
                if (remote.size != identity.size) return@remoteUse false
                Files.newInputStream(localPath).buffered().use localUse@ { local ->
                    var offset = 0L
                    val localBuffer = ByteArray(RECONCILIATION_CHUNK_BYTES)
                    while (offset < identity.size) {
                        val length = minOf(localBuffer.size.toLong(), identity.size - offset).toInt()
                        var localCount = 0
                        while (localCount < length) {
                            val read = local.read(localBuffer, localCount, length - localCount)
                            if (read < 0) return@localUse false
                            localCount += read
                        }
                        val remoteBytes = remote.read(offset, length)
                        if (remoteBytes.size != length || !remoteBytes.contentEquals(localBuffer.copyOf(length))) {
                            return@localUse false
                        }
                        offset += length
                    }
                    local.read() == -1
                }
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val LOCAL_CHANGE_SETTLE_MILLIS = 750L
        const val MAX_PLACEHOLDER_COLLISION_RETRIES = 3
        const val MAX_RECOVERY_IDENTITIES = 20_000
        const val PLACEHOLDER_COLLISION_RETRY_DELAY_MILLIS = 25L
        const val RECONCILIATION_CHUNK_BYTES = 1024 * 1024
    }
}

private const val WINDOWS_CLOUD_ALIGNMENT = 4 * 1024L
private const val MAX_WINDOWS_WRITEBACK_ATTEMPTS = 5
private const val MAX_WINDOWS_DIRECTORY_REFRESH_ATTEMPTS = 4
private const val DELAYED_CORRUPT_ROOT_RECOVERY_POLL_MILLIS = 25L
private const val DEFAULT_CORRUPT_ROOT_QUIESCENCE_TIMEOUT_SECONDS = 120L
private const val DEFAULT_INITIAL_POPULATION_TIMEOUT_SECONDS = 120L

private fun windowsWritebackRetryDelayMillis(attempt: Int): Long {
    require(attempt in 1 until MAX_WINDOWS_WRITEBACK_ATTEMPTS)
    return (250L shl (attempt - 1)).coerceAtMost(30_000L)
}

private fun windowsDirectoryRefreshRetryDelayMillis(attempt: Int): Long {
    require(attempt in 1..MAX_WINDOWS_DIRECTORY_REFRESH_ATTEMPTS)
    return (1_000L shl (attempt - 1)).coerceAtMost(30_000L)
}

private fun Path.registerForWindowsCloudChanges(watcher: WatchService) {
    register(
        watcher,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.ENTRY_DELETE,
    )
}
