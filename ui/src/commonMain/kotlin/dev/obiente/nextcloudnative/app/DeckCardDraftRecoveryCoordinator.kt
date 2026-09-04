package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

internal class DeckCardDraftRecoveryCoordinator(
    private val services: DeckCardDraftPlatformServices,
    private val session: NextcloudSession,
) {
    var restoredDraft by mutableStateOf<PersistedDeckCardDraft?>(null)
        private set
    var loadedKey by mutableStateOf<DeckCardDraftKey?>(null)
        private set
    var recoveredNotice by mutableStateOf(false)
        private set
    var unreadableKey by mutableStateOf<DeckCardDraftKey?>(null)
        private set
    var resetRequired by mutableStateOf(false)
        private set
    var persistenceJob: Job? = null

    private var submittedQuarantineFailures = emptySet<DeckCardDraftKey>()

    suspend fun load(key: DeckCardDraftKey, original: DeckUiCardDraft): String? {
        loadedKey = null
        recoveredNotice = false
        unreadableKey = null
        restoredDraft = null
        if (key in submittedQuarantineFailures) {
            unreadableKey = key
            loadedKey = key
            return SUBMITTED_QUARANTINE_FAILURE
        }
        var recoveryError: String? = null
        val error = try {
            persistenceJob?.join()
            services.loadDeckCardDraft(session, key)?.let { persisted ->
                val reconciled = persisted.copy(
                    draft = persisted.draft.reconcileUntouchedDueDate(original),
                )
                if (reconciled != persisted) {
                    try {
                        services.saveDeckCardDraft(session, reconciled)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        recoveryError = DRAFT_SAVE_FAILURE
                    }
                }
                restoredDraft = reconciled
                recoveredNotice = true
            }
            recoveryError
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            unreadableKey = key
            UNREADABLE_DRAFT_FAILURE
        }
        loadedKey = key
        return error
    }

    fun clearForClosedEditor() {
        restoredDraft = null
        recoveredNotice = false
    }

    fun canClearOnClose(key: DeckCardDraftKey): Boolean = unreadableKey != key

    fun blocksPersistence(key: DeckCardDraftKey): Boolean = unreadableKey == key

    suspend fun persist(
        key: DeckCardDraftKey,
        draft: DeckUiCardDraft,
        original: DeckUiCardDraft,
        clearWhenUnchanged: Boolean = true,
    ): String? {
        if (!draft.hasMeaningfulChangesFrom(original)) {
            try {
                if (clearWhenUnchanged) services.clearDeckCardDraft(session, key)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                return DRAFT_SAVE_FAILURE
            }
            restoredDraft = null
            recoveredNotice = false
            return null
        }
        return try {
            val persisted = PersistedDeckCardDraft(key, draft)
            services.saveDeckCardDraft(session, persisted)
            restoredDraft = persisted
            resetRequired = false
            null
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (failure is DeckCardDraftCapacityException) resetRequired = true
            DRAFT_SAVE_FAILURE
        }
    }

    suspend fun discardAndPersistReplacement(
        key: DeckCardDraftKey,
        draft: DeckUiCardDraft,
        original: DeckUiCardDraft,
    ): String? {
        val preserveReplacement = unreadableKey == key || key in submittedQuarantineFailures
        try {
            services.clearDeckCardDraft(session, key, discardUnreadable = true)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return DRAFT_DISCARD_FAILURE
        }
        restoredDraft = null
        recoveredNotice = false
        unreadableKey = null
        submittedQuarantineFailures -= key
        return if (preserveReplacement) {
            persist(key, draft, original, clearWhenUnchanged = false)
        } else {
            null
        }
    }

    suspend fun resetAndPersistReplacement(
        key: DeckCardDraftKey,
        draft: DeckUiCardDraft,
        original: DeckUiCardDraft,
    ): String? {
        try {
            services.discardAllDeckCardDrafts()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return DRAFT_RESET_FAILURE
        }
        restoredDraft = null
        recoveredNotice = false
        unreadableKey = null
        submittedQuarantineFailures = emptySet()
        resetRequired = false
        return persist(key, draft, original, clearWhenUnchanged = false)
    }

    suspend fun quarantineSubmitted(key: DeckCardDraftKey): String? {
        val error = try {
            services.quarantineSubmittedDeckCardDraft(session, key)
            submittedQuarantineFailures -= key
            null
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            submittedQuarantineFailures += key
            SUBMITTED_QUARANTINE_FAILURE
        }
        restoredDraft = null
        recoveredNotice = false
        unreadableKey = key.takeIf { it in submittedQuarantineFailures }
        return error
    }

    fun markRestored(persisted: PersistedDeckCardDraft) {
        restoredDraft = persisted
    }

    companion object {
        const val UNREADABLE_DRAFT_FAILURE = "The saved card draft could not be restored safely."
        const val DRAFT_SAVE_FAILURE = "The unsaved card draft could not be stored safely."
        const val DRAFT_DISCARD_FAILURE = "The recovered card draft could not be discarded."
        const val DRAFT_RESET_FAILURE = "Saved card draft recovery could not be reset."
        const val SUBMITTED_QUARANTINE_FAILURE =
            "The card was saved, but its recovery copy could not be quarantined. Discard it before editing again."
    }
}
