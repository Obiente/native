package dev.obiente.nextcloudnative.app

/** Removes process-local private state after credential removal has committed. */
object AccountPrivateMemoryCleanup {
    fun removeAccount(accountStorageKey: String) = AccountPrivateMemoryLifecycle.retireAccount(accountStorageKey)

    internal fun purgeRetiredAccount(accountStorageKey: String) {
        require(accountStorageKey.length == 64 && accountStorageKey.all { it in '0'..'9' || it in 'a'..'f' })
        PreviewMemoryCache.purgeRetiredAccount(accountStorageKey)
        sharedNextcloudNotesCache.purgeRetiredAccount(accountStorageKey)
        sharedDynamicNativeMemoryCache.retireAccount(accountStorageKey)
        sharedDashboardStatusMemoryCache.purgeRetiredAccount(accountStorageKey)
        ContactsWorkspaceMemoryCache.purgeRetiredAccount(accountStorageKey)
        DeckWorkspaceMemoryCache.purgeRetiredAccount(accountStorageKey)
        sharedDocumentEditingCapabilitiesCache.purgeRetiredAccount(accountStorageKey)
        SupportSettingsDraftRegistry.removeAccount(accountStorageKey)
        removeCalendarWorkspaceMemory(accountStorageKey)
        removeUserStatusWorkspaceMemory(accountStorageKey)
        removeNextcloudNativeWorkspaceMemory(accountStorageKey)
    }
}
