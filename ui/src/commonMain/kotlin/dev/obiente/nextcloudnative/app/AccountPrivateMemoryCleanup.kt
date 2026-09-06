package dev.obiente.nextcloudnative.app

/** Removes process-local private state after credential removal has committed. */
object AccountPrivateMemoryCleanup {
    fun removeAccount(accountStorageKey: String) {
        require(accountStorageKey.length == 64 && accountStorageKey.all { it in '0'..'9' || it in 'a'..'f' })
        PreviewMemoryCache.removeAccount(accountStorageKey)
        sharedNextcloudNotesCache.removeAccount(accountStorageKey)
        sharedDynamicNativeMemoryCache.removeAccount(accountStorageKey)
        sharedDashboardStatusMemoryCache.removeAccount(accountStorageKey)
        ContactsWorkspaceMemoryCache.removeAccount(accountStorageKey)
        DeckWorkspaceMemoryCache.removeAccount(accountStorageKey)
        sharedDocumentEditingCapabilitiesCache.removeAccount(accountStorageKey)
        SupportSettingsDraftRegistry.removeAccount(accountStorageKey)
        removeCalendarWorkspaceMemory(accountStorageKey)
        removeUserStatusWorkspaceMemory(accountStorageKey)
        removeNextcloudNativeWorkspaceMemory(accountStorageKey)
    }
}
