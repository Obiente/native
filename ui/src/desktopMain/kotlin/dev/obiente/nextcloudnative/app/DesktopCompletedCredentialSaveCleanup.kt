package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences

internal const val DESKTOP_CREDENTIAL_SAVE_CLEANUP_COMPLETED = "cleanup-completed"

/** The terminal marker keeps partially erased legacy identity fields restart-safe. */
internal fun clearDesktopCompletedCredentialSave(preferences: Preferences, flush: () -> Unit) {
    preferences.put("accountCredentialSavePhase", DESKTOP_CREDENTIAL_SAVE_CLEANUP_COMPLETED)
    flush()
    preferences.remove("accountCredentialSaveServer")
    preferences.remove("accountCredentialSaveLogin")
    flush()
    preferences.remove("accountCredentialSavePhase")
    flush()
}
