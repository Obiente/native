package dev.obiente.nextcloudnative

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal val NEXTCLOUD_DOCUMENTS_URI_GRANT_FLAGS: Int =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

internal fun requireAndroidAccountRemovalWritebacksResolved(resolved: Boolean) {
    if (!resolved) rejectAndroidAccountRemovalForPendingDocumentChanges()
}

internal fun rejectAndroidAccountRemovalForPendingDocumentChanges(): Nothing =
    error("Finish or discard pending document changes before removing this account.")

internal suspend fun <Result> withAndroidAccountRemovalLease(
    accountIdentity: String,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    lifetimeGuard: AndroidAccountRemovalLifetimeGuard = ANDROID_ACCOUNT_REMOVAL_LIFETIME_GUARD,
    lifetimeAccountIdentity: String = accountIdentity,
    action: suspend () -> Result,
): Result = lifetimeGuard.withRemoval(lifetimeAccountIdentity) {
    guard.tryWithAccount(
        accountId = accountIdentity,
        unavailable = { rejectAndroidAccountRemovalForPendingDocumentChanges() },
        action = action,
    )
}

internal suspend fun <Result> withAndroidAccountRemovalLease(
    session: NextcloudSession,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    lifetimeGuard: AndroidAccountRemovalLifetimeGuard = ANDROID_ACCOUNT_REMOVAL_LIFETIME_GUARD,
    action: suspend () -> Result,
): Result = lifetimeGuard.withRemoval(session.documentProviderIncarnationAccountIdentity()) {
    guard.tryWithAccounts(
        accountIds = androidAccountOperationIdentities(session),
        unavailable = { rejectAndroidAccountRemovalForPendingDocumentChanges() },
        action = action,
    )
}

internal suspend fun revokeAndroidSessionAfterRemovalPreflight(
    preflight: suspend () -> Unit,
    revoke: suspend () -> Unit,
    removeLocalAccount: suspend () -> Unit,
) {
    preflight()
    val revocationFailure: Exception? = try {
        revoke()
        null
    } catch (cancelled: CancellationException) {
        cancelled
    } catch (failure: Exception) {
        failure
    }
    val localRemovalFailure = try {
        withContext(NonCancellable) { removeLocalAccount() }
        null
    } catch (failure: Exception) {
        failure
    }
    if (revocationFailure is CancellationException) {
        localRemovalFailure?.let(revocationFailure::addSuppressed)
        throw revocationFailure
    }
    if (localRemovalFailure != null) {
        revocationFailure?.let(localRemovalFailure::addSuppressed)
        throw localRemovalFailure
    }
    revocationFailure?.let { throw it }
}

internal suspend fun revokeAndroidSessionWithAccountLease(
    expectedSession: NextcloudSession,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    lifetimeGuard: AndroidAccountRemovalLifetimeGuard = ANDROID_ACCOUNT_REMOVAL_LIFETIME_GUARD,
    preflight: suspend () -> Unit,
    revoke: suspend () -> Unit,
    removeLocalAccount: suspend () -> Unit,
) = withAndroidAccountRemovalLease(expectedSession, guard, lifetimeGuard) {
    revokeAndroidSessionAfterRemovalPreflight(preflight, revoke, removeLocalAccount)
}

internal class AndroidAccountRemovalLifetimeGuard {
    private val monitor = Any()
    private val accounts = mutableMapOf<String, AccountLifetime>()
    private val resetAdmissionGate = Mutex()

    fun acquireReadBlocking(accountIdentity: String): AndroidAccountOperationLease =
        runBlocking { acquireRead(accountIdentity) }

    suspend fun <Result> withRemoval(accountIdentity: String, action: suspend () -> Result): Result {
        return withRemovals(listOf(accountIdentity), action)
    }

    suspend fun <Result> withRemovals(
        accountIdentities: Collection<String>,
        action: suspend () -> Result,
    ): Result {
        val leases = mutableListOf<AndroidAccountOperationLease>()
        return try {
            accountIdentities.distinct().sorted().forEach { accountIdentity ->
                leases += acquireRemoval(accountIdentity)
            }
            action()
        } finally {
            leases.asReversed().forEach(AndroidAccountOperationLease::close)
        }
    }

    suspend fun <Result> withCredentialReset(
        accountIdentities: Collection<String>,
        action: suspend () -> Result,
    ): Result {
        resetAdmissionGate.lock()
        val leases = mutableListOf<AndroidAccountOperationLease>()
        return try {
            val trackedAccountIdentities = synchronized(monitor) { accounts.keys.toList() }
            (accountIdentities + trackedAccountIdentities).distinct().sorted().forEach { accountIdentity ->
                leases += acquireRemovalAfterAdmission(accountIdentity)
            }
            action()
        } finally {
            leases.asReversed().forEach(AndroidAccountOperationLease::close)
            resetAdmissionGate.unlock()
        }
    }

    private suspend fun acquireRead(accountIdentity: String): AndroidAccountOperationLease {
        val lifetime = referenceWithResetAdmission(accountIdentity)
        var gateAcquired = false
        try {
            lifetime.removalGate.lock()
            gateAcquired = true
            synchronized(monitor) { lifetime.readers += 1 }
            lifetime.removalGate.unlock()
            gateAcquired = false
        } catch (failure: Throwable) {
            if (gateAcquired) lifetime.removalGate.unlock()
            releaseReference(accountIdentity, lifetime)
            throw failure
        }
        return AndroidAccountOperationLease {
            val readersDrained = synchronized(monitor) {
                lifetime.readers -= 1
                check(lifetime.readers >= 0)
                lifetime.readersDrained.takeIf { lifetime.readers == 0 }?.also {
                    lifetime.readersDrained = null
                }
            }
            readersDrained?.complete(Unit)
            releaseReference(accountIdentity, lifetime)
        }
    }

    private suspend fun acquireRemoval(accountIdentity: String): AndroidAccountOperationLease {
        val lifetime = referenceWithResetAdmission(accountIdentity)
        return acquireRemoval(accountIdentity, lifetime)
    }

    private suspend fun acquireRemovalAfterAdmission(accountIdentity: String): AndroidAccountOperationLease =
        acquireRemoval(accountIdentity, reference(accountIdentity))

    private suspend fun acquireRemoval(
        accountIdentity: String,
        lifetime: AccountLifetime,
    ): AndroidAccountOperationLease {
        var gateAcquired = false
        try {
            lifetime.removalGate.lock()
            gateAcquired = true
            val readersDrained = synchronized(monitor) {
                if (lifetime.readers == 0) null else CompletableDeferred<Unit>().also {
                    check(lifetime.readersDrained == null)
                    lifetime.readersDrained = it
                }
            }
            readersDrained?.await()
        } catch (failure: Throwable) {
            synchronized(monitor) { lifetime.readersDrained = null }
            if (gateAcquired) lifetime.removalGate.unlock()
            releaseReference(accountIdentity, lifetime)
            throw failure
        }
        return AndroidAccountOperationLease {
            lifetime.removalGate.unlock()
            releaseReference(accountIdentity, lifetime)
        }
    }

    private suspend fun referenceWithResetAdmission(accountIdentity: String): AccountLifetime =
        resetAdmissionGate.withLock { reference(accountIdentity) }

    private fun reference(accountIdentity: String): AccountLifetime {
        require(accountIdentity.isNotBlank())
        return synchronized(monitor) {
            accounts.getOrPut(accountIdentity, ::AccountLifetime).also { it.references += 1 }
        }
    }

    private fun releaseReference(accountIdentity: String, lifetime: AccountLifetime) {
        synchronized(monitor) {
            lifetime.references -= 1
            if (lifetime.references == 0) accounts.remove(accountIdentity, lifetime)
        }
    }

    private class AccountLifetime(
        val removalGate: Mutex = Mutex(),
        var readers: Int = 0,
        var readersDrained: CompletableDeferred<Unit>? = null,
        var references: Int = 0,
    )
}

internal val ANDROID_ACCOUNT_REMOVAL_LIFETIME_GUARD = AndroidAccountRemovalLifetimeGuard()

internal enum class AndroidAccountDocumentGrantScope(val pathSegment: String) {
    Document("document"),
    Tree("tree"),
}

internal fun AndroidAccountDocumentGrantScope.uri(authority: String, rootId: String) = when (this) {
    AndroidAccountDocumentGrantScope.Document -> DocumentsContract.buildDocumentUri(authority, rootId)
    AndroidAccountDocumentGrantScope.Tree -> DocumentsContract.buildTreeDocumentUri(authority, rootId)
}

internal suspend fun preflightAndroidAccountRemoval(context: Context, session: NextcloudSession) {
    requireAndroidAccountRemovalWritebacksResolved(androidDocumentPendingWritebacks(context, session).isEmpty())
    requireAndroidFileSyncAccountRemovalReady(context, NextcloudDocumentIds.accountKey(session))
}

internal suspend fun prepareAndroidAccountRemoval(
    context: Context,
    session: NextcloudSession,
): AndroidDocumentProviderIncarnationRetirement {
    preflightAndroidAccountRemoval(context, session)
    ANDROID_FILE_RANGE_SESSION_COORDINATOR.quiesce(NextcloudDocumentIds.accountKey(session))
    return AndroidDocumentProviderIncarnationStore(context)
        .retireForRemoval(session.documentProviderIncarnationAccountIdentity())
}

internal fun rollbackAndroidAccountRemoval(
    context: Context,
    retirement: AndroidDocumentProviderIncarnationRetirement,
) = AndroidDocumentProviderIncarnationStore(context).rollback(retirement)

internal fun revokeAndroidAccountDocumentGrants(
    context: Context,
    accountIdentity: String,
    accountStorageKey: String,
) {
    val retired = AndroidDocumentProviderIncarnationStore(context).retiredIncarnation(accountStorageKey)
        ?: NextcloudDocumentIncarnation.Legacy
    val rootIds = listOf(
        NextcloudDocumentIds.rootId(accountIdentity, NextcloudDocumentIncarnation.Legacy),
        NextcloudDocumentIds.rootId(accountIdentity, retired),
    ).distinct()
    rootIds.forEach { rootId ->
        AndroidAccountDocumentGrantScope.entries.forEach { scope ->
            context.revokeUriPermission(
                scope.uri(nextcloudDocumentsAuthority(context.packageName), rootId),
                NEXTCLOUD_DOCUMENTS_URI_GRANT_FLAGS,
            )
        }
    }
}

internal suspend fun runAndroidAccountRemovalCleanups(
    cleanups: List<suspend () -> Unit>,
) {
    var firstFailure: Exception? = null
    cleanups.forEach { cleanup ->
        try {
            cleanup()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
        }
    }
    firstFailure?.let { throw it }
}
