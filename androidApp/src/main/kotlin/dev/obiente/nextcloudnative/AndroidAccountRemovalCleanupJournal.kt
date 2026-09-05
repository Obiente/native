package dev.obiente.nextcloudnative

import android.content.SharedPreferences

internal class AndroidAccountRemovalCleanupJournal(
    private val preferences: SharedPreferences,
    private val commit: (SharedPreferences.Editor) -> Unit,
    private val recordMalformed: () -> Unit,
) {
    fun pending(): Set<AndroidPendingAccountRemovalCleanup> = snapshot().cleanups

    fun snapshot(): RestoredAndroidPendingAccountRemovalCleanups {
        val restored = restoreAndroidPendingAccountRemovalCleanups(readEncoded())
        if (restored.malformedEntryCount > 0) runCatching(recordMalformed)
        return restored
    }

    private fun readEncoded(): Set<String> = try {
            preferences.getStringSet(ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY, emptySet()).orEmpty()
        } catch (failure: Exception) {
            runCatching(recordMalformed)
            throw AndroidAccountRemovalCleanupJournalException(
                "The account-removal cleanup journal is unreadable.",
                failure,
            )
        }

    fun prepareEdit(
        editor: SharedPreferences.Editor,
        pendingCleanup: AndroidPendingAccountRemovalCleanup?,
    ): SharedPreferences.Editor = if (pendingCleanup == null) {
        editor
    } else {
        editor.putStringSet(
            ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY,
            replaceAndroidAccountRemovalCleanup(readEncoded(), pendingCleanup, recordMalformed),
        )
    }

    fun clear(accountStorageKey: String) {
        val remaining = removeAndroidAccountRemovalCleanup(readEncoded(), accountStorageKey, recordMalformed)
        val editor = preferences.edit()
        if (remaining.isEmpty()) editor.remove(ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY)
        else editor.putStringSet(ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY, remaining)
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
    }
    return restored.cleanups
}

internal fun requireAndroidAccountRemovalCleanupJournalAllowsActivation(
    snapshot: RestoredAndroidPendingAccountRemovalCleanups,
) {
    check(snapshot.malformedEntryCount == 0) {
        "Reset the malformed account-removal cleanup state before signing in again."
    }
}

internal fun replaceAndroidAccountRemovalCleanup(
    encoded: Set<String>,
    replacement: AndroidPendingAccountRemovalCleanup,
    recordMalformed: () -> Unit,
): Set<String> = removeAndroidAccountRemovalCleanup(
    encoded,
    replacement.accountStorageKey,
    recordMalformed,
) + encodeAndroidPendingAccountRemovalCleanup(replacement)

internal fun removeAndroidAccountRemovalCleanup(
    encoded: Set<String>,
    accountStorageKey: String,
    recordMalformed: () -> Unit,
): Set<String> {
    var malformedFound = false
    val remaining = encoded.filterTo(linkedSetOf()) { entry ->
        val cleanup = decodeAndroidPendingAccountRemovalCleanup(entry)
        if (cleanup == null) {
            malformedFound = true
            true
        } else {
            cleanup.accountStorageKey != accountStorageKey
        }
    }
    if (malformedFound) runCatching(recordMalformed)
    return remaining
}
