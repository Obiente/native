package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncPair
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Provider reads can acquire another account lease, so they must not hold the engine lock. */
internal suspend fun <Result> withRecoveredFileSyncPairSnapshot(
    lock: Mutex,
    snapshot: List<FileSyncPair>,
    readCurrentSnapshot: () -> List<FileSyncPair>,
    reconcile: suspend (FileSyncPair) -> Boolean,
    onRecoveryRejected: () -> Result,
    onSnapshotChanged: () -> Result,
    commit: suspend () -> Result,
): Result {
    for (pair in snapshot) {
        if (!reconcile(pair)) return onRecoveryRejected()
        currentCoroutineContext().ensureActive()
    }
    return lock.withLock {
        if (readCurrentSnapshot() != snapshot) onSnapshotChanged() else commit()
    }
}
