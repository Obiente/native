package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.FileSyncLocalRoot
import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal suspend fun restoreAndroidFileSyncRoot(context: Context, session: NextcloudSession, reference: FileSyncLocalRoot) =
    withContext(Dispatchers.IO) {
        if (reference.savedStateId == null) return@withContext reference
        AndroidFileSyncEngine.ENGINE_LOCK.withLock {
            AndroidFileSyncCapabilityLifecycle(context).restoreSelection(
                AndroidFileSyncCapabilityAccountId(NextcloudDocumentIds.accountKey(session)), reference,
            )
        }
    }

internal suspend fun reconcileFileSyncCapabilitiesAfterRestoration(
    lock: Mutex,
    load: () -> AndroidFileSyncPersistedState,
    capabilities: AndroidFileSyncCapabilityLifecycle,
    waitForRestoration: suspend () -> Unit = { delay(60_000L) },
) {
    reconcileFileSyncCapabilities(lock, load, capabilities)
    waitForRestoration()
    reconcileFileSyncCapabilities(lock, load, capabilities, reclaimUnrestoredReady = true)
}
