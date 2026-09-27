package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncCenterActionResult
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.cleanupJvmFileSyncOwnedUploads
import dev.obiente.nextcloudnative.app.fileSyncOwnedUploads
import dev.obiente.nextcloudnative.app.removeFileSyncPair
import kotlinx.coroutines.sync.withLock

internal suspend fun removeAndroidConfiguredFileSyncPair(
    appContext: Context,
    store: AndroidFileSyncStore,
    webDav: NextcloudDocumentWebDav,
    scheduler: AndroidFileSyncScheduler,
    capabilities: AndroidFileSyncCapabilityLifecycle,
    session: NextcloudSession,
    userId: String,
    pairId: String,
): FileSyncCenterActionResult = run {
    val pair = AndroidFileSyncEngine.ENGINE_LOCK.withLock { store.load().coordinator.pairs.firstOrNull { it.id == pairId } }
        ?: return@run FileSyncCenterActionResult.Rejected(
            "The folder sync pair no longer exists.",
        )
    if (pair.accountId != NextcloudDocumentIds.accountKey(session)) {
        return@run FileSyncCenterActionResult.Rejected(
            "This folder sync pair belongs to another account.",
        )
    }
    withRecoveredFileSyncPairSnapshot(
        lock = AndroidFileSyncEngine.ENGINE_LOCK,
        snapshot = listOf(pair),
        readCurrentSnapshot = { store.load().coordinator.pairs.filter { it.id == pairId } },
        reconcile = { selected ->
            reconcileSafDownloadsBeforePairRemoval(
                appContext, selected.localRootId, androidSafOwnedDownloadRecoveryPaths(selected), providerRecoverySession = session,
            )
        },
        onRecoveryRejected = { FileSyncCenterActionResult.Rejected("A local download still needs safe recovery. Run this folder sync before removing it.") },
        onSnapshotChanged = { FileSyncCenterActionResult.Rejected("Folder sync changed during recovery. Review it before removing it.") },
    ) {
        val current = store.load()
        capabilities.reconcile(current)
        var cleanedCoordinator: FileSyncCoordinatorState? = null
        var remoteCleanupRejected = false
        val removed = removeConfiguredFileSyncPair(
            reconcileLocalDownloads = { true },
            cleanRemoteUploads = {
                val cleanupResult = cleanupJvmFileSyncOwnedUploads(
                    androidFileSyncOwnedRemoteTree(session, userId, pair, webDav, context = appContext),
                    current.coordinator, pairId, fileSyncOwnedUploads(pair),
                )
                remoteCleanupRejected = cleanupResult.unresolvedUploads.isNotEmpty()
                if (!remoteCleanupRejected) cleanedCoordinator = cleanupResult.state
                !remoteCleanupRejected
            },
            cleanLedger = {
                val mediaStore = createAndroidMediaBackupLedgerStore(
                    context = appContext,
                    recoverInterruptedTransfers = false,
                )
                try {
                    mediaStore.deleteUnfinishedSource(
                        accountId = pair.accountId,
                        sourceId = pair.id,
                        legacyLocalKeys = (pair.baselines.asSequence().map(FileSyncBaseline::relativePath) +
                            pair.workItems.asSequence().map { work -> work.relativePath })
                            .distinct()
                            .map { relativePath ->
                                legacyMediaBackupLocalKey(pair.localRootId, relativePath)
                            }
                            .toList(),
                    )
                } finally {
                    mediaStore.close()
                }
            },
            persistRemoval = {
                capabilities.preparePairCleanup(pairId)
                val remaining = removeFileSyncPair(requireNotNull(cleanedCoordinator), pairId)
                capabilities.persistPairRemoval(pairId, store::loadAndReconcileUploadCleanups) {
                    store.save(current.copy(coordinator = remaining, localDisplayNames = current.localDisplayNames - pairId))
                }
            },
            cancelSchedule = { scheduler.cancel(pairId) },
            releaseLocalGrant = {
                capabilities.finishPairCleanupOrRetry(pairId, allowDeferredCleanup = true, load = store::load)
            },
        )
        if (!removed) {
            return@withRecoveredFileSyncPairSnapshot FileSyncCenterActionResult.Rejected(if (remoteCleanupRejected) {
                "A previous upload still needs safe recovery. Run this folder sync before removing it."
            } else "A local download still needs safe recovery. Run this folder sync before removing it.")
        }
        FileSyncCenterActionResult.Completed("Folder sync pair removed. No local or server files were deleted.")
    }
}
