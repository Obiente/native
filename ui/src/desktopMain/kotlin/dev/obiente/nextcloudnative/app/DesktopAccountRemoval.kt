package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences

internal fun requireDesktopAccountRemovalReady(accountId: String, linuxDesktop: Boolean) {
    if (linuxDesktop) {
        requireDesktopAccountRemovalWritebacksResolved(
            defaultDesktopLinuxWritebackStore(accountId).pendingWritebacks().size,
        )
    }
}

internal fun removeDesktopAccountCredential(
    preferences: Preferences,
    providerAccountId: String?,
    removeCredential: () -> Boolean,
): Boolean {
    val providerKey = providerAccountId?.let(::virtualFileProviderPreferenceKey)
    val providerWasEnabled = providerKey?.let { key -> preferences.getBoolean(key, false) } == true
    return removeDesktopCredentialWithoutProviderReactivation(
        providerWasEnabled = providerWasEnabled,
        clearProviderPreference = {
            providerKey?.let(preferences::remove)
            preferences.flush()
        },
        restoreProviderPreference = { enabled ->
            providerKey?.let { key ->
                if (enabled) preferences.putBoolean(key, true) else preferences.remove(key)
            }
            preferences.flush()
        },
        removeCredential = removeCredential,
    )
}
