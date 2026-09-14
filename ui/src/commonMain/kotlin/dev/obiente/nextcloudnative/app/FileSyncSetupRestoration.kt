package dev.obiente.nextcloudnative.app

internal suspend fun restoreAndReconcileFileSyncRootSetup(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    draft: FileSyncSetupDraftState,
): Boolean = restoreAndReconcileFileSyncRootSetup(
    draft,
    restore = { services.restoreFileSyncLocalRoot(session, it) },
    reconcile = { services.reconcileFileSyncRootSetup(session, it) },
    abandon = services::abandonFileSyncLocalRoot,
)

internal suspend fun restoreAndReconcileFileSyncRootSetup(
    draft: FileSyncSetupDraftState,
    restore: suspend (FileSyncLocalRoot) -> FileSyncLocalRoot?,
    reconcile: suspend (FileSyncLocalRoot?) -> Boolean,
    abandon: (FileSyncLocalRoot) -> Boolean,
): Boolean {
    val previous = draft.localRoot.value
    val restored = previous?.let { restore(it) }
    var retainedByDraft = false
    try {
        if (previous != null && restored == null) {
            tryAbandonFileSyncRoot(previous, abandon)
            return draft.localRoot.value !== previous
        }
        if (draft.localRoot.value !== previous) return true
        val reconciled = reconcile(restored)
        if (draft.localRoot.value !== previous) return true
        draft.localRoot.value = restored
        retainedByDraft = true
        return reconciled
    } finally {
        if (!retainedByDraft && restored != null && draft.localRoot.value !== restored) {
            tryAbandonFileSyncRoot(restored, abandon)
        }
    }
}
