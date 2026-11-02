package dev.obiente.nextcloudnative.app

/**
 * Resource identity for one unsaved card editor of one board app. Account identity is added by the
 * platform. [appId] keeps two board apps on one account from sharing drafts for equal ids.
 */
data class DeckCardDraftKey(
    val boardId: Long,
    val stackId: Long,
    val cardId: Long?,
    val appId: String = LEGACY_CARD_DRAFT_APP_ID,
) {
    init {
        require(boardId > 0L) { "A draft needs a board." }
        require(stackId > 0L) { "A draft needs a stack." }
        require(cardId == null || cardId > 0L) { "A draft card id is invalid." }
        require(appId.length in 1..64 && appId.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "A draft app id is invalid."
        }
    }

    /**
     * The storage identity of this draft. Drafts were stored before boards were scoped by app, and
     * only the Deck app could create them, so a Deck draft keeps that original identity and is still
     * found after an upgrade. Every other board app adds its id.
     */
    val storageScope: String
        get() = listOfNotNull(
            appId.takeUnless { it == LEGACY_CARD_DRAFT_APP_ID }?.let { "app=$it" },
            boardId.toString(),
            stackId.toString(),
            cardId?.toString() ?: "new",
        ).joinToString(separator = ":")
}

/** The only app whose card drafts predate app scoping. */
const val LEGACY_CARD_DRAFT_APP_ID = "deck"

data class PersistedDeckCardDraft(
    val key: DeckCardDraftKey,
    val draft: DeckUiCardDraft,
) {
    init {
        require(draft.title.length <= DECK_UI_CARD_TITLE_LIMIT) {
            "The persisted card title is too long."
        }
        require(draft.descriptionMarkdown.length <= DECK_UI_CARD_DESCRIPTION_LIMIT) {
            "The persisted card description is too long."
        }
        require(draft.dueDate.length <= 10 && draft.dueTime.length <= 5) {
            "The persisted due date is invalid."
        }
        require(draft.dueAtBeforeEditing == null || draft.dueAtBeforeEditing.length <= 64) {
            "The persisted original due date is too long."
        }
    }
}

interface DeckCardDraftPlatformServices {
    /** Performs best-effort platform migration after authentication, off the composition path. */
    suspend fun prepareDeckCardDraftRecovery(session: NextcloudSession) = Unit

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
        error("This platform does not provide card draft recovery reset.")
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
        require(maximumEntries > 0) { "Card draft retention must keep at least one entry." }
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
    "Unreadable card drafts fill the recovery limit.",
)

class DeckCardDraftResetRequiredException(
    message: String,
) : IllegalStateException(message)

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
