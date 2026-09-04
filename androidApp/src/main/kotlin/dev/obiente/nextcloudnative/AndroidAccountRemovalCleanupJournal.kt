package dev.obiente.nextcloudnative

import android.content.SharedPreferences

internal class AndroidAccountRemovalCleanupJournal(
    private val preferences: SharedPreferences,
    private val commit: (SharedPreferences.Editor) -> Unit,
    private val recordMalformed: () -> Unit,
) {
    fun pending(): Set<AndroidPendingAccountRemovalCleanup> {
        val encoded = runCatching {
            preferences.getStringSet(ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY, emptySet()).orEmpty()
        }.getOrElse {
            repair(emptySet())
            return emptySet()
        }
        val restored = restoreAndroidPendingAccountRemovalCleanups(encoded)
        if (restored.malformedEntryCount > 0) repair(restored.cleanups)
        return restored.cleanups
    }

    fun prepareEdit(
        editor: SharedPreferences.Editor,
        pendingCleanup: AndroidPendingAccountRemovalCleanup?,
    ): SharedPreferences.Editor = if (pendingCleanup == null) {
        editor
    } else {
        val retained = pending()
            .filterNot { cleanup -> cleanup.accountStorageKey == pendingCleanup.accountStorageKey }
            .toSet() + pendingCleanup
        editor.putStringSet(
            ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY,
            retained.mapTo(linkedSetOf(), ::encodeAndroidPendingAccountRemovalCleanup),
        )
    }

    fun clear(accountStorageKey: String) {
        val remaining = pending().filterNot { cleanup -> cleanup.accountStorageKey == accountStorageKey }
        val editor = preferences.edit()
        if (remaining.isEmpty()) editor.remove(ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY)
        else editor.putStringSet(
            ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY,
            remaining.mapTo(linkedSetOf(), ::encodeAndroidPendingAccountRemovalCleanup),
        )
        commit(editor)
    }

    private fun repair(retained: Set<AndroidPendingAccountRemovalCleanup>) {
        recordMalformed()
        runCatching {
            val editor = preferences.edit()
            if (retained.isEmpty()) editor.remove(ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY)
            else editor.putStringSet(
                ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY,
                retained.mapTo(linkedSetOf(), ::encodeAndroidPendingAccountRemovalCleanup),
            )
            commit(editor)
        }
    }
}
