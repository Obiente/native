package dev.obiente.nextcloudnative

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
    action: suspend () -> Result,
): Result = guard.tryWithAccount(
    accountId = accountIdentity,
    unavailable = { rejectAndroidAccountRemovalForPendingDocumentChanges() },
    action = action,
)

internal suspend fun <Result> withPreparedAndroidAccountRemovalLease(
    accountIdentity: String,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    prepare: suspend () -> Unit,
    revalidate: suspend () -> Unit,
    action: suspend () -> Result,
): Result {
    prepare()
    return withAndroidAccountRemovalLease(accountIdentity, guard) {
        revalidate()
        action()
    }
}

internal suspend fun <Result> withUnavailableAndroidAccountRemovalLease(
    accountIdentity: String,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    preflight: suspend () -> Unit,
    action: suspend () -> Result,
): Result = withPreparedAndroidAccountRemovalLease(
    accountIdentity = accountIdentity,
    guard = guard,
    prepare = preflight,
    revalidate = preflight,
    action = action,
)

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
    accountIdentity: String,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    prepare: suspend () -> Unit,
    revalidate: suspend () -> Unit,
    revoke: suspend () -> Unit,
    removeLocalAccount: suspend () -> Unit,
) = withPreparedAndroidAccountRemovalLease(accountIdentity, guard, prepare, revalidate) {
    revokeAndroidSessionAfterRemovalPreflight({}, revoke, removeLocalAccount)
}

internal class AndroidAccountRemovalLeaseCoordinator(
    context: Context,
    private val guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
) {
    private val appContext = context.applicationContext

    suspend fun <Result> withLease(
        session: NextcloudSession,
        action: suspend () -> Result,
    ): Result = withPreparedAndroidAccountRemovalLease(
        accountIdentity = NextcloudDocumentIds.accountKey(session),
        guard = guard,
        prepare = { prepareAndroidAccountRemoval(appContext, session) },
        revalidate = { preflightAndroidAccountRemoval(appContext, session) },
        action = action,
    )

    // Missing credentials cannot safely repair legacy self-provider downloads before removal.
    // Commit first; durable owned-state cleanup remains fail-closed and can resume after re-add.
    suspend fun <Result> withUnavailableLease(
        session: NextcloudSession,
        action: suspend () -> Result,
    ): Result = withUnavailableAndroidAccountRemovalLease(
        accountIdentity = NextcloudDocumentIds.accountKey(session),
        guard = guard,
        preflight = { preflightAndroidAccountRemoval(appContext, session) },
        action = action,
    )

    suspend fun revoke(
        session: NextcloudSession,
        revoke: suspend () -> Unit,
        removeLocalAccount: suspend () -> Unit,
    ) = revokeAndroidSessionWithAccountLease(
        accountIdentity = NextcloudDocumentIds.accountKey(session),
        guard = guard,
        prepare = { prepareAndroidAccountRemoval(appContext, session) },
        revalidate = { preflightAndroidAccountRemoval(appContext, session) },
        revoke = revoke,
        removeLocalAccount = removeLocalAccount,
    )
}

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

internal suspend fun prepareAndroidAccountRemoval(context: Context, session: NextcloudSession) {
    preflightAndroidAccountRemoval(context, session)
    reconcileAndroidFileSyncAccountDownloadsBeforeCredentialRemoval(
        context,
        NextcloudDocumentIds.accountKey(session),
        session,
    )
}

internal fun revokeAndroidAccountDocumentGrants(context: Context, accountIdentity: String) {
    AndroidAccountDocumentGrantScope.entries.forEach { scope ->
        context.revokeUriPermission(
            scope.uri(nextcloudDocumentsAuthority(context.packageName), NextcloudDocumentIds.rootId(accountIdentity)),
            NEXTCLOUD_DOCUMENTS_URI_GRANT_FLAGS,
        )
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
