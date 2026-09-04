package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession

internal fun commitAndroidAccountTransitionBeforeHandoffCleanup(
    commitTransition: () -> Unit,
    clearHandoffs: () -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    commitTransition()
    try {
        clearHandoffs()
    } catch (failure: Exception) {
        runCatching { recordFailure(failure) }
    }
}

internal fun clearAndroidPreviousPreviewAfterCommittedSelection(
    previousSession: NextcloudSession?,
    selectedSession: NextcloudSession,
    clearPreviewAccount: (String) -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    if (previousSession == null || previousSession.accountId == selectedSession.accountId) return
    try {
        clearPreviewAccount(NextcloudDocumentIds.cacheAccountId(previousSession))
    } catch (failure: Exception) {
        runCatching { recordFailure(failure) }
    }
}

internal fun clearAndroidPreviewAfterCommittedRemoval(
    accountCacheId: String,
    clearPreviewAccount: (String) -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    try {
        clearPreviewAccount(accountCacheId)
    } catch (failure: Exception) {
        runCatching { recordFailure(failure) }
    }
}
