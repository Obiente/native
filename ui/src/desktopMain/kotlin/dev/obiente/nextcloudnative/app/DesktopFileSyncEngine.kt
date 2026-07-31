package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JFileChooser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durable manual desktop executor. The common coordinator owns all planning and conflict rules. */
internal class DesktopFileSyncEngine(
    private val store: DesktopFileSyncStore = DesktopFileSyncStore(),
    private val stagingRoot: File = desktopFileSyncStagingDirectory(),
) {
    private val selectedRoots = ConcurrentHashMap<String, File>()
    private val lock = Mutex()

    suspend fun chooseLocalRoot(initialRootHint: String?): FileSyncLocalRoot? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Choose a folder to sync"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            initialRootHint?.let { hint ->
                selectedRoots[hint]?.takeIf(File::isDirectory)?.let { currentDirectory = it }
            }
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext null
        val selected = chooser.selectedFile.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(selected)) {
            "Choose a regular local folder, not a symbolic link."
        }
        val token = "desktop-selection:${UUID.randomUUID()}"
        selectedRoots[token] = selected.toFile()
        FileSyncLocalRoot(token, selected.fileName?.toString()?.takeIf(String::isNotBlank) ?: "Selected folder")
    }

    suspend fun loadCenter(session: NextcloudSession): FileSyncCenterSnapshot = lock.withLock {
        val accountId = desktopFileCacheAccountId(session)
        val state = store.load()
        FileSyncCenterSnapshot(
            support = FileSyncCenterSupport.Available,
            pairs = state.coordinator.pairs.filter { it.accountId == accountId }.map { pair ->
                val root = state.roots.firstOrNull { it.id == pair.localRootId }
                pair.toCenterSummary(
                    localDisplayName = root?.displayName ?: "Selected folder",
                    localRootPath = root?.absolutePath,
                    scheduleDescription = "Automatic sync while Nextcloud Native is running",
                )
            },
            limitation = null,
        )
    }

    suspend fun loadTrayActivities(
        session: NextcloudSession,
        limit: Int = MAX_TRAY_ACTIVITY_ITEMS,
    ): List<DesktopFileSyncTrayActivity> = lock.withLock {
        require(limit in 1..MAX_TRAY_ACTIVITY_ITEMS)
        val accountId = desktopFileCacheAccountId(session)
        val state = store.load()
        state.coordinator.pairs
            .asSequence()
            .filter { it.accountId == accountId }
            .flatMap { pair ->
                val root = state.roots.firstOrNull { it.id == pair.localRootId }
                val pairLabel = syncPairLabel(root?.displayName ?: "Selected folder", pair.remoteRootPath)
                pair.workItems.asSequence()
                    .filter { it.state != FileSyncExecutionState.Skipped }
                    .map { work ->
                        DesktopFileSyncTrayActivity(
                            stableId = "${pair.id}:${work.id}",
                            relativePath = work.relativePath,
                            pairLabel = pairLabel,
                            phase = when (work.state) {
                                FileSyncExecutionState.AwaitingDecision ->
                                    DesktopFileSyncTrayActivityPhase.Conflict
                                FileSyncExecutionState.Failed -> DesktopFileSyncTrayActivityPhase.Failed
                                FileSyncExecutionState.Ready -> DesktopFileSyncTrayActivityPhase.Waiting
                                FileSyncExecutionState.Running -> work.operation.toTrayActivityPhase()
                                FileSyncExecutionState.Skipped -> DesktopFileSyncTrayActivityPhase.Waiting
                            },
                            sizeBytes = work.observedLocal?.size ?: work.observedRemote?.size,
                            detail = work.failureMessage,
                        )
                    }
            }
            .sortedWith(
                compareBy<DesktopFileSyncTrayActivity> {
                    when (it.phase) {
                        DesktopFileSyncTrayActivityPhase.Uploading,
                        DesktopFileSyncTrayActivityPhase.Downloading,
                        DesktopFileSyncTrayActivityPhase.Preparing,
                        -> 0
                        DesktopFileSyncTrayActivityPhase.Conflict,
                        DesktopFileSyncTrayActivityPhase.Failed,
                        -> 1
                        DesktopFileSyncTrayActivityPhase.Waiting -> 2
                        DesktopFileSyncTrayActivityPhase.Completed -> 3
                    }
                }.thenBy(DesktopFileSyncTrayActivity::relativePath),
            )
            .take(limit)
            .toList()
    }

    suspend fun addPair(
        session: NextcloudSession,
        localRoot: FileSyncLocalRoot,
        remoteRootPath: String,
        configuration: FileSyncConfiguration,
    ): FileSyncCenterActionResult = lock.withLock {
        val selected = selectedRoots[localRoot.localRootId]
            ?: return@withLock FileSyncCenterActionResult.Rejected("Choose the local folder again.")
        val canonical = selected.canonicalFile
        DesktopFileSyncLocalTree(canonical)
        val normalizedRemote = normalizeRemoteRoot(remoteRootPath)
        val accountId = desktopFileCacheAccountId(session)
        val current = store.load()
        if (current.coordinator.pairs.any { pair ->
                pair.accountId == accountId && pair.remoteRootPath == normalizedRemote &&
                    current.roots.firstOrNull { it.id == pair.localRootId }?.absolutePath == canonical.absolutePath
            }
        ) {
            return@withLock FileSyncCenterActionResult.Rejected(
                "That local and Nextcloud folder pair already exists.",
            )
        }
        val rootId = UUID.randomUUID().toString()
        val pair = FileSyncPair(
            id = UUID.randomUUID().toString(),
            accountId = accountId,
            localRootId = rootId,
            remoteRootPath = normalizedRemote,
            configuration = configuration,
        )
        store.save(
            current.copy(
                coordinator = addFileSyncPair(current.coordinator, pair),
                roots = current.roots + DesktopFileSyncRootRecord(
                    rootId,
                    canonical.absolutePath,
                    localRoot.displayName,
                ),
            ),
        )
        selectedRoots.remove(localRoot.localRootId)
        FileSyncCenterActionResult.Completed("Folder sync pair added. Run it to review the first sync.")
    }

    suspend fun removePair(session: NextcloudSession, pairId: String): FileSyncCenterActionResult = lock.withLock {
        val current = store.load()
        val pair = current.coordinator.pairs.firstOrNull { it.id == pairId }
            ?: return@withLock FileSyncCenterActionResult.Rejected("The folder sync pair no longer exists.")
        if (pair.accountId != desktopFileCacheAccountId(session)) {
            return@withLock FileSyncCenterActionResult.Rejected("This folder sync pair belongs to another account.")
        }
        val remaining = removeFileSyncPair(current.coordinator, pairId)
        store.save(
            current.copy(
                coordinator = remaining,
                roots = current.roots.filterNot { root ->
                    root.id == pair.localRootId && remaining.pairs.none { it.localRootId == root.id }
                },
            ),
        )
        FileSyncCenterActionResult.Completed("Folder sync pair removed. No local or server files were deleted.")
    }

    suspend fun runPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        onProgress: (DesktopFileSyncProgressEvent) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): FileSyncCenterActionResult = lock.withLock {
        runPairLocked(session, userId, pairId, onProgress, shouldContinue)
    }

    suspend fun resolveConflictAndRun(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        workId: Long,
        choice: FileSyncDecisionChoice,
        onProgress: (DesktopFileSyncProgressEvent) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): FileSyncCenterActionResult = lock.withLock {
        val current = store.load()
        val pair = current.coordinator.pairs.firstOrNull { it.id == pairId }
            ?: return@withLock FileSyncCenterActionResult.Rejected("The folder sync pair no longer exists.")
        if (pair.accountId != desktopFileCacheAccountId(session)) {
            return@withLock FileSyncCenterActionResult.Rejected("This folder sync pair belongs to another account.")
        }
        val resolved = runCatching {
            resolveFileSyncDecision(current.coordinator, pairId, workId, choice)
        }.getOrElse { failure ->
            return@withLock FileSyncCenterActionResult.Rejected(
                safeFailureMessage(failure, "That conflict decision is no longer valid. Scan again."),
            )
        }
        store.save(current.copy(coordinator = resolved))
        runPairLocked(session, userId, pairId, onProgress, shouldContinue)
    }

    private fun runPairLocked(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        onProgress: (DesktopFileSyncProgressEvent) -> Unit,
        shouldContinue: () -> Boolean,
    ): FileSyncCenterActionResult {
        var persisted = store.load()
        val initialPair = persisted.coordinator.pairs.firstOrNull { it.id == pairId }
            ?: return FileSyncCenterActionResult.Rejected("The folder sync pair no longer exists.")
        if (initialPair.accountId != desktopFileCacheAccountId(session)) {
            return FileSyncCenterActionResult.Rejected("This folder sync pair belongs to another account.")
        }
        val root = persisted.roots.firstOrNull { it.id == initialPair.localRootId }
            ?: return FileSyncCenterActionResult.Rejected("The local folder record is missing.")
        val local = DesktopFileSyncLocalTree(File(root.absolutePath))
        val remote = DesktopFileSyncRemoteTree(session, userId, initialPair.remoteRootPath)
        val includes: (String, SyncEntryKind) -> Boolean = { path, kind ->
            initialPair.configuration.includesSyncPath(path, kind)
        }
        val localEntries = local.scan(includes).map(DesktopLocalSyncDocument::entry)
        val remoteEntries = remote.scan(includes).map(DesktopRemoteSyncDocument::entry)
        persisted = persisted.copy(
            coordinator = scanFileSyncPair(
                persisted.coordinator,
                pairId,
                localEntries,
                remoteEntries,
                System.currentTimeMillis(),
            ),
        )
        persisted.coordinator.pairs.first { it.id == pairId }.workItems
            .filter { it.state == FileSyncExecutionState.Failed && it.attemptCount < MAX_FILE_SYNC_ATTEMPTS }
            .forEach { work ->
                persisted = persisted.copy(
                    coordinator = retryFileSyncOperation(persisted.coordinator, pairId, work.id),
                )
            }
        store.save(persisted)

        val pairLabel = syncPairLabel(root.displayName, initialPair.remoteRootPath)
        val totalOperations = persisted.coordinator.pairs.first { it.id == pairId }.workItems.count {
            it.state == FileSyncExecutionState.Ready
        }
        var completed = 0
        while (true) {
            if (!shouldContinue()) break
            val claim = claimNextFileSyncOperation(persisted.coordinator, pairId, System.currentTimeMillis())
            persisted = persisted.copy(coordinator = claim.state)
            store.save(persisted)
            val command = claim.command ?: break
            val runningWork = persisted.coordinator.pairs.first { it.id == pairId }
                .workItems.first { it.id == command.workId }
            val sizeBytes = runningWork.observedLocal?.size ?: runningWork.observedRemote?.size
            onProgress(
                DesktopFileSyncProgressEvent(
                    pairId = pairId,
                    workId = command.workId,
                    relativePath = runningWork.relativePath,
                    pairLabel = pairLabel,
                    operation = command.operation,
                    completedOperations = completed,
                    totalOperations = totalOperations,
                    sizeBytes = sizeBytes,
                    stage = DesktopFileSyncProgressStage.Started,
                ),
            )
            try {
                val success = execute(command, persisted.coordinator, local, remote)
                persisted = persisted.copy(
                    coordinator = completeFileSyncOperation(
                        persisted.coordinator,
                        pairId,
                        command.workId,
                        success,
                    ),
                )
                store.save(persisted)
                completed += 1
                onProgress(
                    DesktopFileSyncProgressEvent(
                        pairId = pairId,
                        workId = command.workId,
                        relativePath = runningWork.relativePath,
                        pairLabel = pairLabel,
                        operation = command.operation,
                        completedOperations = completed,
                        totalOperations = totalOperations,
                        sizeBytes = sizeBytes,
                        stage = DesktopFileSyncProgressStage.Completed,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                val safeMessage = safeFailureMessage(failure, "The sync operation failed.")
                persisted = persisted.copy(
                    coordinator = failFileSyncOperation(
                        persisted.coordinator,
                        pairId,
                        command.workId,
                        safeMessage,
                    ),
                )
                store.save(persisted)
                onProgress(
                    DesktopFileSyncProgressEvent(
                        pairId = pairId,
                        workId = command.workId,
                        relativePath = runningWork.relativePath,
                        pairLabel = pairLabel,
                        operation = command.operation,
                        completedOperations = completed,
                        totalOperations = totalOperations,
                        sizeBytes = sizeBytes,
                        stage = DesktopFileSyncProgressStage.Failed,
                        failureMessage = safeMessage,
                    ),
                )
            }
        }
        val pair = persisted.coordinator.pairs.first { it.id == pairId }
        val conflicts = pair.workItems.count { it.state == FileSyncExecutionState.AwaitingDecision }
        val failures = pair.workItems.count { it.state == FileSyncExecutionState.Failed }
        val message = buildString {
            append(completed).append(" sync operation")
            if (completed != 1) append('s')
            append(" completed.")
            if (conflicts > 0) append(' ').append(conflicts).append(" conflicts need review.")
            if (failures > 0) append(' ').append(failures).append(" operations failed.")
        }
        return if (failures > 0) FileSyncCenterActionResult.Rejected(message)
        else FileSyncCenterActionResult.Completed(message)
    }

    private fun syncPairLabel(localDisplayName: String, remoteRootPath: String): String =
        "$localDisplayName to /${remoteRootPath.ifBlank { "Nextcloud" }}"

    private fun execute(
        command: FileSyncExecutionCommand,
        state: FileSyncCoordinatorState,
        local: DesktopFileSyncLocalTree,
        remote: DesktopFileSyncRemoteTree,
    ): FileSyncExecutionSuccess {
        val pair = state.pairs.first { it.id == command.pairId }
        val work = pair.workItems.first { it.id == command.workId }
        return when (val operation = command.operation) {
            is FileSyncOperation.Upload -> {
                val source = requireNotNull(work.observedLocal)
                if (work.observedRemote?.kind?.let { it != source.kind } == true) {
                    remote.delete(operation.relativePath, requireNotNull(operation.expectedRemoteEtag))
                }
                val expected = operation.expectedRemoteEtag
                    .takeUnless { work.observedRemote?.kind?.let { it != source.kind } == true }
                if (source.kind == SyncEntryKind.Directory) {
                    remote.createDirectory(operation.relativePath, expected)
                } else {
                    withStagingFile("upload") { staged ->
                        local.stageForUpload(operation.relativePath, staged, MAX_SYNC_FILE_BYTES)
                        remote.writeFile(operation.relativePath, staged, expected)
                    }
                }
                synchronizedResult(operation.relativePath, local, remote)
            }
            is FileSyncOperation.Download -> {
                val source = requireNotNull(work.observedRemote)
                if (work.observedLocal?.kind?.let { it != source.kind } == true) {
                    local.delete(operation.relativePath, requireNotNull(operation.expectedLocalRevision))
                }
                val expected = operation.expectedLocalRevision
                    .takeUnless { work.observedLocal?.kind?.let { it != source.kind } == true }
                if (source.kind == SyncEntryKind.Directory) {
                    local.createDirectory(operation.relativePath, expected)
                } else {
                    withStagingFile("download") { staged ->
                        remote.stageDownload(operation.relativePath, source.etag, staged, MAX_SYNC_FILE_BYTES)
                        local.writeFile(operation.relativePath, staged, expected)
                    }
                }
                synchronizedResult(operation.relativePath, local, remote)
            }
            is FileSyncOperation.DeleteLocal -> {
                local.delete(operation.relativePath, operation.expectedLocalRevision)
                require(local.resolve(operation.relativePath) == null && remote.resolve(operation.relativePath) == null)
                FileSyncExecutionSuccess(removedRelativePaths = listOf(operation.relativePath))
            }
            is FileSyncOperation.DeleteRemote -> {
                remote.delete(operation.relativePath, operation.expectedRemoteEtag)
                require(local.resolve(operation.relativePath) == null && remote.resolve(operation.relativePath) == null)
                FileSyncExecutionSuccess(removedRelativePaths = listOf(operation.relativePath))
            }
            is FileSyncOperation.KeepBoth -> executeKeepBoth(operation, work, local, remote)
            is FileSyncOperation.NeedsDecision,
            is FileSyncOperation.Skipped,
            -> error("Non-executable sync work was claimed.")
        }
    }

    private fun executeKeepBoth(
        operation: FileSyncOperation.KeepBoth,
        work: FileSyncWorkItem,
        local: DesktopFileSyncLocalTree,
        remote: DesktopFileSyncRemoteTree,
    ): FileSyncExecutionSuccess {
        val localSource = requireNotNull(work.observedLocal)
        val remoteSource = requireNotNull(work.observedRemote)
        require(localSource.kind == SyncEntryKind.File && remoteSource.kind == SyncEntryKind.File)
        withStagingFile("keep-local") { localBytes ->
            withStagingFile("keep-remote") { remoteBytes ->
                local.stageForUpload(operation.relativePath, localBytes, MAX_SYNC_FILE_BYTES)
                remote.stageDownload(operation.relativePath, remoteSource.etag, remoteBytes, MAX_SYNC_FILE_BYTES)
                remote.writeFile(operation.localConflictPath, localBytes, null)
                local.writeFile(operation.localConflictPath, localBytes, null)
                remote.writeFile(operation.remoteConflictPath, remoteBytes, null)
                local.writeFile(operation.remoteConflictPath, remoteBytes, null)
                local.writeFile(operation.relativePath, remoteBytes, localSource.revision)
            }
        }
        return FileSyncExecutionSuccess(
            synchronizedBaselines = listOf(
                verifiedBaseline(operation.relativePath, local, remote),
                verifiedBaseline(operation.localConflictPath, local, remote),
                verifiedBaseline(operation.remoteConflictPath, local, remote),
            ),
        )
    }

    private fun synchronizedResult(
        path: String,
        local: DesktopFileSyncLocalTree,
        remote: DesktopFileSyncRemoteTree,
    ) = FileSyncExecutionSuccess(synchronizedBaselines = listOf(verifiedBaseline(path, local, remote)))

    private fun verifiedBaseline(
        path: String,
        local: DesktopFileSyncLocalTree,
        remote: DesktopFileSyncRemoteTree,
    ): FileSyncBaseline {
        val localEntry = requireNotNull(local.resolve(path)) { "The local result could not be verified." }.entry
        val remoteEntry = requireNotNull(remote.resolve(path)) { "The server result could not be verified." }.entry
        require(localEntry.kind == remoteEntry.kind) { "The synchronized item types do not match." }
        return FileSyncBaseline(path, localEntry.kind, localEntry.revision, remoteEntry.etag)
    }

    private inline fun <T> withStagingFile(prefix: String, block: (File) -> T): T {
        check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Could not create sync staging storage." }
        val file = File.createTempFile("$prefix-", ".tmp", stagingRoot)
        return try {
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun normalizeRemoteRoot(path: String): String {
        val normalized = path.trim().trim('/')
        if (normalized.isEmpty()) return ""
        require(normalized.length <= MAX_FILE_SYNC_PATH_LENGTH)
        normalized.split('/').forEach { segment ->
            require(segment.isNotBlank() && segment !in setOf(".", "..") && segment.none(Char::isISOControl))
        }
        return normalized
    }

    private fun safeFailureMessage(failure: Throwable, fallback: String): String =
        failure.message?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")
            ?.trim()?.take(MAX_FILE_SYNC_FAILURE_LENGTH)?.takeIf(String::isNotBlank) ?: fallback

    private companion object {
        const val MAX_SYNC_FILE_BYTES = 8L * 1024L * 1024L * 1024L
    }
}

private fun desktopFileSyncStagingDirectory(): File {
    val cacheRoot = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)?.let(::File)
        ?: File(System.getProperty("user.home"), ".cache")
    return File(cacheRoot, "nextcloud-native/file-sync-staging")
}
