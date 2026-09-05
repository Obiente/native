package dev.obiente.nextcloudnative

import android.content.SharedPreferences

internal class AndroidAccountRemovalCleanupJournal(
    private val preferences: SharedPreferences,
    private val commit: (SharedPreferences.Editor) -> Unit,
    private val recordMalformed: () -> Unit,
) {
    fun pending(): Set<AndroidPendingAccountRemovalCleanup> {
        val encoded = try {
            preferences.getStringSet(ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY, emptySet()).orEmpty()
        } catch (failure: Exception) {
            runCatching(recordMalformed)
            throw AndroidAccountRemovalCleanupJournalException(
                "The account-removal cleanup journal is unreadable.",
                failure,
            )
        }
        return requireValidAndroidAccountRemovalCleanupJournal(encoded, recordMalformed)
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
}

internal class AndroidAccountRemovalCleanupJournalException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun requireValidAndroidAccountRemovalCleanupJournal(
    encoded: Set<String>,
    recordMalformed: () -> Unit,
): Set<AndroidPendingAccountRemovalCleanup> {
    val restored = restoreAndroidPendingAccountRemovalCleanups(encoded)
    if (restored.malformedEntryCount > 0) {
        runCatching(recordMalformed)
        throw AndroidAccountRemovalCleanupJournalException(
            "The account-removal cleanup journal contains a malformed tombstone.",
        )
    }
    return restored.cleanups
}
