package dev.obiente.nextcloudnative

import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudSession

internal suspend fun clearUnregisteredAndroidAccountCredentialSlots(
    preferences: SharedPreferences,
    sessionCipher: SessionCipher,
    cleanupJournal: AndroidAccountRemovalCleanupJournal,
    suspectEncrypted: String?,
    prepareAccountRemoval: suspend (NextcloudSession) -> Unit,
    removeAccountOwnedState: suspend (NextcloudSession) -> Unit,
    commitPreferences: (SharedPreferences.Editor) -> Unit,
    recordCleanupFailure: (Exception) -> Unit,
    clearInvalidStore: suspend (String?) -> Unit,
) {
    requireAndroidIndependentCredentialStateCanBeExplicitlyReset(
        preferences.getString(ANDROID_ACCOUNT_REGISTRY_KEY, null),
    )
    val slots = recoverAndroidIndependentCredentialSlotsForReset(
        preferenceKeys = preferences.all.keys,
        readEncrypted = { key -> preferences.getString(key, null) },
        decrypt = sessionCipher::decrypt,
    )
    val cleanupSnapshot = cleanupJournal.snapshot()
    requireAndroidAccountRemovalCleanupJournalAllowsActivation(cleanupSnapshot)
    retireUnregisteredAndroidAccountCredentialSlots(
        slots = slots,
        preexistingCleanupAccountStorageKeys = cleanupSnapshot.cleanups.mapTo(hashSetOf()) { it.accountStorageKey },
        retryPreexistingCleanup = { slot ->
            retryAndroidAccountRemovalCleanup(
                accountOwnedByRegistry = false,
                removeAccountOwnedWork = { removeAccountOwnedState(slot.session) },
                clearCleanup = { cleanupJournal.clear(slot.session.accountId.storageKey) },
            )
        },
        prepareAccountRemoval = prepareAccountRemoval,
        commitSlotRemoval = { slot, cleanup ->
            commitPreferences(
                cleanupJournal.prepareEdit(preferences.edit().remove(slot.preferenceKey), cleanup),
            )
        },
        rollbackSlotRemoval = { slot ->
            commitPreferences(preferences.edit().putString(slot.preferenceKey, slot.encrypted))
        },
        removeAccountOwnedState = removeAccountOwnedState,
        clearCleanup = cleanupJournal::clear,
        recordCleanupFailure = recordCleanupFailure,
    )
    clearInvalidStore(suspectEncrypted)
}

internal suspend fun retireUnregisteredAndroidAccountCredentialSlots(
    slots: List<AndroidIndependentCredentialSlotReset>,
    preexistingCleanupAccountStorageKeys: Set<String> = emptySet(),
    retryPreexistingCleanup: suspend (AndroidIndependentCredentialSlotReset) -> Unit = {},
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    prepareAccountRemoval: suspend (NextcloudSession) -> Unit,
    commitSlotRemoval: suspend (AndroidIndependentCredentialSlotReset, AndroidPendingAccountRemovalCleanup) -> Unit,
    rollbackSlotRemoval: suspend (AndroidIndependentCredentialSlotReset) -> Unit,
    removeAccountOwnedState: suspend (NextcloudSession) -> Unit,
    clearCleanup: suspend (String) -> Unit,
    recordCleanupFailure: (Exception) -> Unit,
) {
    slots.forEach { slot ->
        val session = slot.session
        if (session.accountId.storageKey in preexistingCleanupAccountStorageKeys) {
            retryPreexistingCleanup(slot)
        }
        val pendingCleanup = pendingAndroidAccountRemovalCleanup(session)
        withAndroidAccountRemovalLease(NextcloudDocumentIds.accountKey(session), guard) {
            removeRecoveredAndroidAccountCredentialData(
                prepareAccountRemoval = { prepareAccountRemoval(session) },
                removeQueuedUploads = { removeAccountOwnedState(session) },
                clearRecoveredAccount = { commitSlotRemoval(slot, pendingCleanup) },
                rollbackRecoveredAccount = {
                    rollbackSlotRemoval(slot)
                    clearCleanup(session.accountId.storageKey)
                },
                completeCommittedCleanup = { clearCleanup(session.accountId.storageKey) },
                recordCommittedCleanupFailure = recordCleanupFailure,
            )
        }
    }
}
