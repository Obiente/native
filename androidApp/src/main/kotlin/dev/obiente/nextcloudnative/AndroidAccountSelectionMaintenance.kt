package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession

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
