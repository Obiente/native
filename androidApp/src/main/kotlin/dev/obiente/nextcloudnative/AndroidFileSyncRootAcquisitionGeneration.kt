package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncLocalRoot
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class AndroidFileSyncRootAcquisitionGeneration internal constructor(
    internal val accountId: AndroidFileSyncCapabilityAccountId,
    internal val canonicalAccountId: String? = null,
    internal val canonicalGeneration: AndroidFileSyncCanonicalAcquisitionGeneration? = null,
    internal val rawGeneration: AndroidFileSyncRootAcquisitionGeneration? = null,
)

internal class AndroidFileSyncCanonicalAcquisitionGeneration

internal class AndroidFileSyncRootAcquisitionRevokedException : IllegalStateException(
    "The account changed before folder access could be saved. Select the folder again.",
)

/** Process-local producer identity; durable capability records still own grant recovery. */
internal object AndroidFileSyncRootAcquisitionGenerations {
    private val current = ConcurrentHashMap<AndroidFileSyncCapabilityAccountId, AndroidFileSyncRootAcquisitionGeneration>()

    private val canonical = ConcurrentHashMap<String, AndroidFileSyncCanonicalAcquisitionGeneration>()

    fun capture(accountId: AndroidFileSyncCapabilityAccountId, canonicalAccountId: String? = null): AndroidFileSyncRootAcquisitionGeneration {
        val raw = current.computeIfAbsent(accountId) { AndroidFileSyncRootAcquisitionGeneration(it) }
        if (canonicalAccountId == null) return raw
        return AndroidFileSyncRootAcquisitionGeneration(accountId, canonicalAccountId,
            canonical.computeIfAbsent(canonicalAccountId) { AndroidFileSyncCanonicalAcquisitionGeneration() }, raw)
    }

    fun retireCanonical(accountStorageKey: String) {
        canonical[accountStorageKey] = AndroidFileSyncCanonicalAcquisitionGeneration()
    }

    fun retire(accountId: AndroidFileSyncCapabilityAccountId) {
        current[accountId] = AndroidFileSyncRootAcquisitionGeneration(accountId)
    }

    // Raw retirement shares the lifecycle lock; canonical retirement shares the account lease.
    fun requireCurrent(accountId: AndroidFileSyncCapabilityAccountId, generation: AndroidFileSyncRootAcquisitionGeneration) {
        if (generation.accountId != accountId || current[accountId] !== (generation.rawGeneration ?: generation) ||
            (generation.canonicalAccountId != null && canonical[generation.canonicalAccountId] !== generation.canonicalGeneration)) {
            throw AndroidFileSyncRootAcquisitionRevokedException()
        }
    }
}

/** Querying the provider never holds an account lease; only the grant acquisition does. */
internal suspend fun acquireCurrentAccountFileSyncRoot(
    expectedSession: NextcloudSession,
    resolveSession: suspend () -> NextcloudSession?,
    isRequestActive: () -> Boolean,
    queryDisplayName: () -> String,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    acquire: (String) -> FileSyncLocalRoot,
): FileSyncLocalRoot {
    val displayName = queryDisplayName()
    currentCoroutineContext().ensureActive()
    if (!isRequestActive()) throw CancellationException("The folder selection was cancelled.")
    return guard.withAccounts(androidAccountOperationIdentities(expectedSession)) {
        if (!isRequestActive()) throw CancellationException("The folder selection was cancelled.")
        if (resolveSession() != expectedSession) throw AndroidFileSyncRootAcquisitionRevokedException()
        currentCoroutineContext().ensureActive()
        if (!isRequestActive()) throw CancellationException("The folder selection was cancelled.")
        acquire(displayName)
    }
}
