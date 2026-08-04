package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durable manual desktop executor. The common coordinator owns all planning and conflict rules. */
internal class DesktopFileSyncEngine(
    private val store: DesktopFileSyncStore = DesktopFileSyncStore(),
    private val stagingRoot: File = desktopFileSyncStagingDirectory(),
    private val minimumFreeSpaceBytes: () -> Long = { 0L },
    private val folderPicker: DesktopSystemFolderPicker = DesktopSystemFolderPicker(),
    private val onRemoteMutationCommitted: (session: NextcloudSession, userId: String, path: String) -> Unit =
        { _, _, _ -> },
) {
    private val selectedRoots = ConcurrentHashMap<String, File>()
    private val lock = Mutex()

    suspend fun chooseLocalRoot(initialRootHint: String?): FileSyncLocalRoot? = withContext(Dispatchers.IO) {
        val initialDirectory = initialRootHint?.let(selectedRoots::get)?.takeIf(File::isDirectory)
        val chosen = folderPicker.choose(initialDirectory) ?: return@withContext null
        val selected = chosen.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(selected)) {
            "Choose a regular local folder, not a symbolic link."
        }
        val token = "desktop-selection:${UUID.randomUUID()}"
        selectedRoots[token] = selected.toFile()
        FileSyncLocalRoot(token, selected.fileName?.toString()?.takeIf(String::isNotBlank) ?: "Selected folder")
    }

    suspend fun loadCenter(session: NextcloudSession): FileSyncCenterSnapshot = lock.withLock {
        store.withExclusiveAccess {
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
    }

    suspend fun loadTrayActivities(
        session: NextcloudSession,
        limit: Int = MAX_TRAY_ACTIVITY_ITEMS,
    ): List<DesktopFileSyncTrayActivity> = lock.withLock {
        store.withExclusiveAccess {
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
    }

    suspend fun addPair(
        session: NextcloudSession,
        localRoot: FileSyncLocalRoot,
        remoteRootPath: String,
        configuration: FileSyncConfiguration,
    ): FileSyncCenterActionResult = lock.withLock {
        store.withExclusiveAccess transaction@ {
            val selected = selectedRoots[localRoot.localRootId]
                ?: return@transaction FileSyncCenterActionResult.Rejected("Choose the local folder again.")
            val canonical = selected.canonicalFile
            DesktopFileSyncLocalTree(canonical)
            val normalizedRemote = normalizeRemoteRoot(remoteRootPath)
            val accountId = desktopFileCacheAccountId(session)
            val current = store.load()
            if (current.coordinator.pairs.any { pair ->
                    val existingRoot = current.roots.firstOrNull { it.id == pair.localRootId } ?: return@any true
                    desktopSyncMappingsOverlap(
                        existingAccountId = pair.accountId,
                        requestedAccountId = accountId,
                        existingLocalRoot = existingRoot.absolutePath,
                        requestedLocalRoot = canonical.absolutePath,
                        existingRemoteRoot = pair.remoteRootPath,
                        requestedRemoteRoot = normalizedRemote,
                    )
                }) {
                return@transaction FileSyncCenterActionResult.Rejected(
                    "This folder overlaps another local or Nextcloud sync mapping. Choose separate roots.",
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
    }

    suspend fun removePair(session: NextcloudSession, pairId: String): FileSyncCenterActionResult = lock.withLock {
        store.withExclusiveAccess transaction@ {
            val current = store.load()
            val pair = current.coordinator.pairs.firstOrNull { it.id == pairId }
                ?: return@transaction FileSyncCenterActionResult.Rejected("The folder sync pair no longer exists.")
            if (pair.accountId != desktopFileCacheAccountId(session)) {
                return@transaction FileSyncCenterActionResult.Rejected(
                    "This folder sync pair belongs to another account.",
                )
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
    }

    suspend fun runPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        onProgress: (DesktopFileSyncProgressEvent) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        resetExhaustedFailures: Boolean = false,
    ): FileSyncCenterActionResult = lock.withLock {
        store.withExclusiveAccess {
            runPairLocked(session, userId, pairId, onProgress, shouldContinue, resetExhaustedFailures)
        }
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
        store.withExclusiveAccess transaction@ {
            val current = store.load()
            val pair = current.coordinator.pairs.firstOrNull { it.id == pairId }
                ?: return@transaction FileSyncCenterActionResult.Rejected("The folder sync pair no longer exists.")
            if (pair.accountId != desktopFileCacheAccountId(session)) {
                return@transaction FileSyncCenterActionResult.Rejected(
                    "This folder sync pair belongs to another account.",
                )
            }
            val resolved = runCatching {
                resolveFileSyncDecision(current.coordinator, pairId, workId, choice)
            }.getOrElse { failure ->
                return@transaction FileSyncCenterActionResult.Rejected(
                    safeFailureMessage(failure, "That conflict decision is no longer valid. Scan again."),
                )
            }
            store.save(current.copy(coordinator = resolved))
            runPairLocked(
                session,
                userId,
                pairId,
                onProgress,
                shouldContinue,
                resetExhaustedFailures = true,
            )
        }
    }

    private fun runPairLocked(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        onProgress: (DesktopFileSyncProgressEvent) -> Unit,
        shouldContinue: () -> Boolean,
        resetExhaustedFailures: Boolean,
    ): FileSyncCenterActionResult {
        reclaimDesktopFileSyncStages(stagingRoot)
        var persisted = store.load()
        val initialPair = persisted.coordinator.pairs.firstOrNull { it.id == pairId }
            ?: return FileSyncCenterActionResult.Rejected("The folder sync pair no longer exists.")
        if (initialPair.accountId != desktopFileCacheAccountId(session)) {
            return FileSyncCenterActionResult.Rejected("This folder sync pair belongs to another account.")
        }
        val root = persisted.roots.firstOrNull { it.id == initialPair.localRootId }
            ?: return FileSyncCenterActionResult.Rejected("The local folder record is missing.")
        val local = DesktopFileSyncLocalTree(File(root.absolutePath))
        val remote = DesktopFileSyncRemoteTree(
            session = session,
            userId = userId,
            remoteRootPath = initialPair.remoteRootPath,
            onMutationCommitted = { relativePath ->
                val path = desktopFileSyncRemoteMutationPath(initialPair.remoteRootPath, relativePath)
                runCatching { onRemoteMutationCommitted(session, userId, path) }
            },
        )
        val includes: (String, SyncEntryKind) -> Boolean = { path, kind ->
            initialPair.configuration.includesSyncPath(path, kind)
        }
        val cachedLocalRevisions = initialPair.baselines.mapNotNull { baseline ->
            baseline.localRevision?.let { revision -> baseline.relativePath to revision }
        }.toMap()
        val localEntries = local.scan(cachedLocalRevisions, includes).map(DesktopLocalSyncDocument::entry)
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
        if (resetExhaustedFailures) {
            persisted = persisted.copy(
                coordinator = resetExhaustedFileSyncOperations(persisted.coordinator, pairId),
            )
        }
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
                val replacingType = work.observedRemote?.kind?.let { it != source.kind } == true
                var exactLocal: LocalSyncEntry? = null
                var exactRemote: RemoteSyncEntry? = null
                if (source.kind == SyncEntryKind.Directory && replacingType) {
                    remote.replaceWithDirectory(
                        operation.relativePath,
                        requireNotNull(operation.expectedRemoteEtag),
                    )
                } else if (source.kind == SyncEntryKind.Directory) {
                    remote.createDirectory(operation.relativePath, operation.expectedRemoteEtag)
                } else {
                    withStagingFile("upload") { staged ->
                        exactLocal = local.stageForUpload(operation.relativePath, staged, MAX_SYNC_FILE_BYTES)
                        val uploaded = if (replacingType) {
                            remote.replaceWithFile(
                                operation.relativePath,
                                staged,
                                requireNotNull(operation.expectedRemoteEtag),
                            )
                        } else {
                            remote.writeFile(operation.relativePath, staged, operation.expectedRemoteEtag)
                        }
                        withStagingFile("verify-upload") { verified ->
                            exactRemote = remote.stageDownload(
                                operation.relativePath,
                                uploaded.etag,
                                verified,
                                MAX_SYNC_FILE_BYTES,
                            )
                            require(filesMatch(staged, verified)) {
                                "The uploaded server file does not match the staged local generation."
                            }
                        }
                    }
                }
                if (source.kind == SyncEntryKind.File) {
                    FileSyncExecutionSuccess(
                        synchronizedBaselines = listOf(
                            FileSyncBaseline(
                                operation.relativePath,
                                SyncEntryKind.File,
                                requireNotNull(exactLocal).revision,
                                requireNotNull(exactRemote).etag,
                            ),
                        ),
                    )
                } else {
                    synchronizedResult(operation.relativePath, local, remote)
                }
            }
            is FileSyncOperation.Download -> {
                val source = requireNotNull(work.observedRemote)
                val replacingType = work.observedLocal?.kind?.let { it != source.kind } == true
                var exactLocal: LocalSyncEntry? = null
                var exactRemote: RemoteSyncEntry? = null
                if (source.kind == SyncEntryKind.Directory && replacingType) {
                    local.replaceWithDirectory(
                        operation.relativePath,
                        requireNotNull(operation.expectedLocalRevision),
                    )
                } else if (source.kind == SyncEntryKind.Directory) {
                    local.createDirectory(operation.relativePath, operation.expectedLocalRevision)
                } else {
                    source.size?.let { size -> requireDownloadCapacity(local, operation.relativePath, size) }
                    withStagingFile("download") { staged ->
                        exactRemote = remote.stageDownload(
                            operation.relativePath,
                            source.etag,
                            staged,
                            MAX_SYNC_FILE_BYTES,
                        ) { declaredBytes ->
                            requireDownloadCapacity(
                                local,
                                operation.relativePath,
                                declaredBytes ?: source.size ?: MAX_SYNC_FILE_BYTES,
                            )
                        }
                        exactLocal = if (replacingType) {
                            local.replaceWithFile(
                                operation.relativePath,
                                staged,
                                requireNotNull(operation.expectedLocalRevision),
                            )
                        } else {
                            local.writeFile(operation.relativePath, staged, operation.expectedLocalRevision)
                        }
                    }
                }
                if (source.kind == SyncEntryKind.File) {
                    FileSyncExecutionSuccess(
                        synchronizedBaselines = listOf(
                            FileSyncBaseline(
                                operation.relativePath,
                                SyncEntryKind.File,
                                requireNotNull(exactLocal).revision,
                                requireNotNull(exactRemote).etag,
                            ),
                        ),
                    )
                } else {
                    synchronizedResult(operation.relativePath, local, remote)
                }
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
                val currentOriginal = local.resolve(operation.relativePath)
                val preservedLocalPath = if (currentOriginal?.entry?.revision == localSource.revision) {
                    operation.relativePath
                } else {
                    operation.localConflictPath
                }
                local.stageForUpload(preservedLocalPath, localBytes, MAX_SYNC_FILE_BYTES)
                remote.stageDownload(operation.relativePath, remoteSource.etag, remoteBytes, MAX_SYNC_FILE_BYTES)
                ensureLocalFile(operation.localConflictPath, localBytes, local)
                ensureRemoteFile(operation.localConflictPath, localBytes, remote)
                ensureLocalFile(operation.remoteConflictPath, remoteBytes, local)
                ensureRemoteFile(operation.remoteConflictPath, remoteBytes, remote)
                replaceLocalOriginalOrVerify(
                    operation.relativePath,
                    remoteBytes,
                    localSource.revision,
                    local,
                )
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

    private fun ensureLocalFile(
        path: String,
        expectedBytes: File,
        local: DesktopFileSyncLocalTree,
    ) {
        val current = local.resolve(path)
        if (current == null) {
            local.writeFile(path, expectedBytes, null)
            return
        }
        require(current.entry.kind == SyncEntryKind.File) { "A conflict-copy path is not a file." }
        withStagingFile("verify-local-conflict") { actualBytes ->
            local.stageForUpload(path, actualBytes, MAX_SYNC_FILE_BYTES)
            require(filesMatch(actualBytes, expectedBytes)) {
                "A conflict-copy path contains different local content."
            }
        }
    }

    private fun ensureRemoteFile(
        path: String,
        expectedBytes: File,
        remote: DesktopFileSyncRemoteTree,
    ) {
        val current = remote.resolve(path)
        if (current == null) {
            remote.writeFile(path, expectedBytes, null)
            return
        }
        require(current.entry.kind == SyncEntryKind.File) { "A conflict-copy path is not a file." }
        withStagingFile("verify-remote-conflict") { actualBytes ->
            remote.stageDownload(path, current.entry.etag, actualBytes, MAX_SYNC_FILE_BYTES)
            require(filesMatch(actualBytes, expectedBytes)) {
                "A conflict-copy path contains different server content."
            }
        }
    }

    private fun replaceLocalOriginalOrVerify(
        path: String,
        expectedBytes: File,
        originalRevision: String,
        local: DesktopFileSyncLocalTree,
    ) {
        val current = requireNotNull(local.resolve(path)) { "The original local file disappeared." }
        require(current.entry.kind == SyncEntryKind.File) { "The original local path is not a file." }
        if (current.entry.revision == originalRevision) {
            local.writeFile(path, expectedBytes, originalRevision)
            return
        }
        withStagingFile("verify-local-original") { actualBytes ->
            local.stageForUpload(path, actualBytes, MAX_SYNC_FILE_BYTES)
            require(filesMatch(actualBytes, expectedBytes)) {
                "The original local file changed while conflict copies were being published."
            }
        }
    }

    private fun filesMatch(first: File, second: File): Boolean =
        first.length() == second.length() && Files.mismatch(first.toPath(), second.toPath()) == -1L

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
        require(prefix in DESKTOP_FILE_SYNC_STAGE_PREFIXES)
        val file = File(stagingRoot, "nextcloud-native-$prefix-${UUID.randomUUID()}.tmp")
        check(file.createNewFile()) { "Could not create sync staging file." }
        return try {
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun requireDownloadCapacity(
        local: DesktopFileSyncLocalTree,
        relativePath: String,
        downloadBytes: Long,
    ) {
        require(downloadBytes in 0L..MAX_SYNC_FILE_BYTES)
        val reserve = minimumFreeSpaceBytes()
        require(reserve >= 0L)
        check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Could not create sync staging storage." }
        val stagingStore = Files.getFileStore(stagingRoot.toPath())
        val destinationStore = local.fileStore(relativePath)
        if (stagingStore == destinationStore) {
            require(
                stagingStore.usableSpace >= requiredDesktopDownloadFreeBytes(downloadBytes, reserve, sameStore = true),
            ) { "There is not enough free space to stage this synchronized file safely." }
        } else {
            val required = requiredDesktopDownloadFreeBytes(downloadBytes, reserve, sameStore = false)
            require(stagingStore.usableSpace >= required) {
                "The sync staging location does not have enough reserved free space."
            }
            require(destinationStore.usableSpace >= required) {
                "The destination folder does not have enough reserved free space."
            }
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

internal fun desktopFileSyncRemoteMutationPath(remoteRootPath: String, relativePath: String): String {
    val relative = relativePath.trim('/')
    requireValidSyncPath(relative)
    val root = remoteRootPath.trim('/')
    if (root.isNotEmpty()) requireValidSyncPath(root)
    return listOf(root, relative).filter(String::isNotBlank).joinToString("/")
}

internal fun reclaimDesktopFileSyncStages(stagingRoot: File): Int {
    if (!stagingRoot.isDirectory) return 0
    return stagingRoot.listFiles().orEmpty().count { candidate ->
        if (!Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return@count false
        val name = candidate.name
        val prefix = DESKTOP_FILE_SYNC_STAGE_PREFIXES.firstOrNull { ownedPrefix ->
            name.startsWith("nextcloud-native-$ownedPrefix-")
        } ?: return@count false
        val token = name.removePrefix("nextcloud-native-$prefix-").removeSuffix(".tmp")
        if (!name.endsWith(".tmp") || runCatching { UUID.fromString(token) }.isFailure) return@count false
        candidate.delete()
    }
}

private val DESKTOP_FILE_SYNC_STAGE_PREFIXES = setOf(
    "upload",
    "verify-upload",
    "download",
    "keep-local",
    "keep-remote",
    "verify-local-conflict",
    "verify-remote-conflict",
    "verify-local-original",
)

internal fun requiredDesktopDownloadFreeBytes(
    downloadBytes: Long,
    reserveBytes: Long,
    sameStore: Boolean,
): Long {
    require(downloadBytes >= 0L && reserveBytes >= 0L)
    val contentBytes = if (sameStore) {
        if (downloadBytes > Long.MAX_VALUE / 2L) Long.MAX_VALUE else downloadBytes * 2L
    } else {
        downloadBytes
    }
    return if (reserveBytes > Long.MAX_VALUE - contentBytes) Long.MAX_VALUE else contentBytes + reserveBytes
}

internal fun desktopSyncRootsOverlap(first: String, second: String): Boolean {
    val firstPath = File(first).toPath().toAbsolutePath().normalize()
    val secondPath = File(second).toPath().toAbsolutePath().normalize()
    return firstPath == secondPath || firstPath.startsWith(secondPath) || secondPath.startsWith(firstPath)
}

internal fun desktopSyncRemoteRootsOverlap(first: String, second: String): Boolean {
    val left = first.trim('/')
    val right = second.trim('/')
    return left.isEmpty() || right.isEmpty() ||
        left == right || left.startsWith("$right/") || right.startsWith("$left/")
}

internal fun desktopSyncMappingsOverlap(
    existingAccountId: String,
    requestedAccountId: String,
    existingLocalRoot: String,
    requestedLocalRoot: String,
    existingRemoteRoot: String,
    requestedRemoteRoot: String,
): Boolean = desktopSyncRootsOverlap(existingLocalRoot, requestedLocalRoot) ||
    (
        existingAccountId == requestedAccountId &&
            desktopSyncRemoteRootsOverlap(existingRemoteRoot, requestedRemoteRoot)
        )

private fun desktopFileSyncStagingDirectory(): File {
    val cacheRoot = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)?.let(::File)
        ?: File(System.getProperty("user.home"), ".cache")
    return File(cacheRoot, "nextcloud-native/file-sync-staging")
}
