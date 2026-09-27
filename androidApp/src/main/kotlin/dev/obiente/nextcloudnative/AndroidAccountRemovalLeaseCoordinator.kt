package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.NextcloudSession

/** Recover provider content before exclusive removal, then verify it under the removal lease. */
internal class AndroidAccountRemovalLeaseCoordinator(
    context: Context,
    private val guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
) {
    private val appContext = context.applicationContext

    suspend fun <Result> withLease(session: NextcloudSession, action: suspend () -> Result): Result =
        withPreparedAndroidAccountRemovalLease(
            session = session,
            guard = guard,
            prepare = { recoverAndroidAccountDownloadsBeforeRemoval(appContext, session) },
            revalidate = { revalidateAndroidAccountRemoval(appContext, session) },
            action = action,
        )

    suspend fun <Result> withUnavailableLease(session: NextcloudSession, action: suspend () -> Result): Result =
        withPreparedAndroidAccountRemovalLease(
            session = session,
            guard = guard,
            prepare = { preflightAndroidAccountRemoval(appContext, session) },
            revalidate = { preflightAndroidAccountRemoval(appContext, session) },
            action = action,
        )
}

internal suspend fun <Result> withPreparedAndroidAccountRemovalLease(
    session: NextcloudSession,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    lifetimeGuard: AndroidAccountRemovalLifetimeGuard = ANDROID_ACCOUNT_REMOVAL_LIFETIME_GUARD,
    prepare: suspend () -> Unit,
    revalidate: suspend () -> Unit,
    action: suspend () -> Result,
): Result {
    prepare()
    return withAndroidAccountRemovalLease(session, guard, lifetimeGuard) {
        revalidate()
        action()
    }
}

internal suspend fun recoverAndroidAccountDownloadsBeforeRemoval(context: Context, session: NextcloudSession) {
    preflightAndroidAccountRemoval(context, session)
    reconcileAndroidFileSyncAccountDownloadsBeforeCredentialRemoval(
        context, NextcloudDocumentIds.accountKey(session), session,
    )
}

internal suspend fun revalidateAndroidAccountRemoval(context: Context, session: NextcloudSession) {
    preflightAndroidAccountRemoval(context, session)
    reconcileAndroidFileSyncAccountDownloadsBeforeCredentialRemoval(
        context, NextcloudDocumentIds.accountKey(session), session, accountLeaseHeld = true,
    )
    ANDROID_FILE_RANGE_SESSION_COORDINATOR.quiesce(NextcloudDocumentIds.accountKey(session))
}
