package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

internal suspend fun reconcileDesktopBackgroundSession(
    loadSession: () -> NextcloudSession?,
    reconcile: suspend (NextcloudSession?) -> Unit,
    onFailure: (NextcloudSession?, Throwable) -> Unit = { _, _ -> },
): Boolean {
    val loaded = loadNextcloudSessionSafely(loadSession)
    if (loaded == NextcloudSessionLoadState.SecureStorageUnavailable) return false
    val session = (loaded as NextcloudSessionLoadState.Loaded).session
    try {
        reconcile(session)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        onFailure(session, failure)
    }
    return true
}
