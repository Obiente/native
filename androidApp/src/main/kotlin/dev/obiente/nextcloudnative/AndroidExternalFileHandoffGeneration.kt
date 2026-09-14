package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession

internal class AndroidExternalFileHandoffGeneration internal constructor()
internal class AndroidExternalFileHandoffRevokedException : IllegalStateException("This file handoff is no longer active.")

/** Bind the producer before remote probing or staging can suspend across an account transition. */
internal suspend fun captureAndroidExternalFileHandoffGeneration(
    session: NextcloudSession,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    resolveSession: suspend () -> NextcloudSession?,
): AndroidExternalFileHandoffGeneration = withRetainedAndroidAccountFileRead(
    expectedSession = session,
    resolveSession = resolveSession,
    guard = guard,
) { AndroidExternalFileHandoffRegistry.captureGeneration() }
