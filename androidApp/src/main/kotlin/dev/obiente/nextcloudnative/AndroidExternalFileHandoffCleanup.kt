package dev.obiente.nextcloudnative

import android.content.SharedPreferences

internal const val ANDROID_PENDING_EXTERNAL_HANDOFF_CLEANUP_KEY = "pending_external_handoff_cleanup_v1"
private const val ANDROID_PENDING_EXTERNAL_HANDOFF_CLEANUP = "pending"

internal fun prepareAndroidExternalHandoffCleanup(
    editor: SharedPreferences.Editor,
): SharedPreferences.Editor = editor.putString(
    ANDROID_PENDING_EXTERNAL_HANDOFF_CLEANUP_KEY,
    ANDROID_PENDING_EXTERNAL_HANDOFF_CLEANUP,
)

internal class AndroidExternalFileHandoffCleanup(
    context: android.content.Context,
    private val preferences: SharedPreferences,
    private val commit: (SharedPreferences.Editor) -> Unit,
) {
    private val appContext = context.applicationContext

    fun prepare(editor: SharedPreferences.Editor): SharedPreferences.Editor =
        prepareAndroidExternalHandoffCleanup(editor)

    fun pending(): Boolean = preferences.contains(ANDROID_PENDING_EXTERNAL_HANDOFF_CLEANUP_KEY)

    fun complete() {
        clearHandoffs()
        clearJournal()
    }

    fun clearHandoffs() {
        AndroidExternalFileHandoffRegistry.clearPersisted(AndroidExternalFileHandoffStore(appContext))
    }

    fun clearJournal() {
        commit(preferences.edit().remove(ANDROID_PENDING_EXTERNAL_HANDOFF_CLEANUP_KEY))
    }
}

internal fun hasPendingAndroidExternalHandoffCleanup(preferences: SharedPreferences): Boolean =
    preferences.contains(ANDROID_PENDING_EXTERNAL_HANDOFF_CLEANUP_KEY)

internal fun retryPendingAndroidExternalHandoffCleanup(
    pending: Boolean,
    clearHandoffs: () -> Unit,
    clearJournal: () -> Unit,
    recordFailure: (Exception) -> Unit,
): Boolean {
    if (!pending) return true
    return try {
        clearHandoffs()
        clearJournal()
        true
    } catch (failure: Exception) {
        recordFailure(failure)
        false
    }
}
