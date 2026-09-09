package dev.obiente.nextcloudnative.app

/** Capability, connection and retained edits are distinct states. */
internal fun VirtualFileStorageSnapshot.virtualStorageStatusLabel(): String = when {
    virtualStorageEditsNeedReview() -> "Edits need review"
    providerState == VirtualFileProviderState.NeedsAttention -> "Connection needs attention"
    folderHydrationStatuses.any { it.phase == VirtualFolderHydrationPhase.Failed || it.refreshFailure != null } -> "Downloads need attention"
    providerState == VirtualFileProviderState.Starting -> "Connecting"
    pendingWritebackCount > 0 -> "Local edits pending"
    providerActive -> "Connected"
    providerState == VirtualFileProviderState.Inactive -> "Not connected"
    support == VirtualFileStorageSupport.CacheOnly -> "App cache"
    support == VirtualFileStorageSupport.Unsupported -> "Unavailable"
    else -> "Integration available"
}

internal fun VirtualFileStorageSnapshot.virtualStorageEditsNeedReview(): Boolean =
    providerRecoveryNotice != null ||
        (pendingWritebackCount > 0 && providerState == VirtualFileProviderState.NeedsAttention)
