package dev.obiente.nextcloudnative.app

/** Resource identity for one unsaved Deck card editor. Account identity is added by the platform. */
data class DeckCardDraftKey(
    val boardId: Long,
    val stackId: Long,
    val cardId: Long?,
) {
    init {
        require(boardId > 0L) { "A Deck draft needs a board." }
        require(stackId > 0L) { "A Deck draft needs a stack." }
        require(cardId == null || cardId > 0L) { "A Deck draft card id is invalid." }
    }
}

data class PersistedDeckCardDraft(
    val key: DeckCardDraftKey,
    val draft: DeckUiCardDraft,
) {
    init {
        require(draft.title.length <= DECK_UI_CARD_TITLE_LIMIT) {
            "The persisted Deck card title is too long."
        }
        require(draft.descriptionMarkdown.length <= DECK_UI_CARD_DESCRIPTION_LIMIT) {
            "The persisted Deck card description is too long."
        }
        require(draft.dueDate.length <= 10 && draft.dueTime.length <= 5) {
            "The persisted Deck due date is invalid."
        }
        require(draft.dueAtBeforeEditing == null || draft.dueAtBeforeEditing.length <= 64) {
            "The persisted Deck original due date is too long."
        }
    }
}

interface DeckCardDraftPlatformServices {
    /** Loads one bounded, account-scoped unsaved Deck editor draft from app-private storage. */
    suspend fun loadDeckCardDraft(
        session: NextcloudSession,
        key: DeckCardDraftKey,
    ): PersistedDeckCardDraft? = null

    /** Persists one bounded editor draft without storing account credentials in its key. */
    suspend fun saveDeckCardDraft(session: NextcloudSession, draft: PersistedDeckCardDraft) = Unit

    /** Clears a draft after explicit discard or confirmed successful server mutation. */
    suspend fun clearDeckCardDraft(
        session: NextcloudSession,
        key: DeckCardDraftKey,
        discardUnreadable: Boolean = false,
    ) = Unit

    /** Quarantines a recovery copy after the corresponding server mutation is confirmed. */
    suspend fun quarantineSubmittedDeckCardDraft(
        session: NextcloudSession,
        key: DeckCardDraftKey,
    ) = clearDeckCardDraft(session, key, discardUnreadable = true)

    /** Explicitly discards every local Deck recovery record, including unreadable records. */
    suspend fun discardAllDeckCardDrafts(): Unit =
        error("This platform does not provide Deck draft recovery reset.")
}

object DeckCardDraftRetention {
    const val MAX_ENTRIES = 24

    data class Entry(
        val storageKey: String,
        val updatedAtEpochMillis: Long,
    )

    fun keysToPrune(
        entries: List<Entry>,
        maximumEntries: Int = MAX_ENTRIES,
    ): Set<String> {
        require(maximumEntries > 0) { "Deck draft retention must keep at least one entry." }
        return entries
            .sortedWith(
                compareByDescending<Entry> { it.updatedAtEpochMillis }
                    .thenByDescending { it.storageKey },
            )
            .drop(maximumEntries)
            .mapTo(linkedSetOf(), Entry::storageKey)
    }
}

class DeckCardDraftCapacityException : IllegalStateException(
    "Unreadable Deck drafts fill the recovery limit.",
)

internal fun DeckUiCardDraft.hasMeaningfulChangesFrom(original: DeckUiCardDraft): Boolean =
    title != original.title ||
        descriptionMarkdown != original.descriptionMarkdown ||
        dueDate != original.dueDate ||
        dueTime != original.dueTime ||
        dueFieldsEdited != original.dueFieldsEdited

internal fun DeckUiCardDraft.reconcileUntouchedDueDate(
    authoritative: DeckUiCardDraft,
): DeckUiCardDraft =
    if (dueFieldsEdited) {
        this
    } else {
        copy(
            dueDate = authoritative.dueDate,
            dueTime = authoritative.dueTime,
            dueAtBeforeEditing = authoritative.dueAtBeforeEditing,
        )
    }
