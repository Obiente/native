package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

data class DeckUiSettingsState(
    val board: DeckBoard,
    val userSettings: DeckUserSettings? = null,
    val loadingUserSettings: Boolean = true,
    val userSettingsError: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val errorMessage: String? = null,
    val outcomeUnknown: Boolean = false,
) {
    val canChangeBoardSettings: Boolean
        get() = board.canChangeDeckSettings() && board.settings != null && !busy && !outcomeUnknown

    val canChangeUserSettings: Boolean
        get() = userSettings?.showBoardsInCalendar != null && !busy && !outcomeUnknown
}

private data class DeckSettingsSnapshot(
    val board: DeckBoard,
    val user: DeckUserSettings?,
)

/** State holder for Deck board and account settings. Each change is re-read and verified. */
internal class DeckBoardSettingsController(private val context: DeckBoardToolContext) {
    var state: DeckUiSettingsState? by mutableStateOf(null)
        private set

    fun open(board: DeckBoard) {
        state = DeckUiSettingsState(board = board)
    }

    fun close() {
        if (state?.busy != true) state = null
    }

    suspend fun load() {
        val current = state ?: return
        state = current.copy(loadingUserSettings = true, userSettingsError = null)
        try {
            val board = context.fetchBoard(current.board.id)
            context.onBoardRefreshed(board)
            val user = fetchUserSettings()
            state = state?.copy(
                board = board,
                userSettings = user,
                loadingUserSettings = false,
                outcomeUnknown = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            state = state?.copy(
                loadingUserSettings = false,
                userSettingsError = failure.message ?: "Deck settings could not be loaded.",
            )
        }
    }

    suspend fun change(change: DeckSettingChange) {
        val current = state ?: return
        val allowed = when (change) {
            is DeckSettingChange.GlobalCalendar -> current.canChangeUserSettings
            else -> current.canChangeBoardSettings
        }
        if (!allowed) return
        val request = try {
            deckSettingWriteRequest(current.board, change)
        } catch (invalid: IllegalArgumentException) {
            state = current.copy(errorMessage = invalid.message)
            return
        } catch (invalid: IllegalStateException) {
            state = current.copy(errorMessage = invalid.message)
            return
        }
        state = current.copy(busy = true, errorMessage = null, notice = null)
        val outcome = runVerifiedDeckWrite(
            execute = context.execute,
            request = request,
            acceptResponse = ::parseDeckSettingWrite,
            reread = {
                when (change) {
                    is DeckSettingChange.GlobalCalendar ->
                        DeckSettingsSnapshot(current.board, fetchUserSettings())
                    else -> DeckSettingsSnapshot(context.fetchBoard(current.board.id), current.userSettings)
                }
            },
            verify = { snapshot ->
                if (verifyDeckSettingChange(change, snapshot.board, snapshot.user)) {
                    DeckWriteVerification.Confirmed
                } else {
                    DeckWriteVerification.NotApplied("Deck did not save this setting.")
                }
            },
            actionLabel = "save setting",
        )
        val latest = state ?: return
        state = when (outcome) {
            is DeckVerifiedWriteOutcome.Confirmed -> {
                if (change !is DeckSettingChange.GlobalCalendar) context.onBoardRefreshed(outcome.snapshot.board)
                latest.copy(
                    board = outcome.snapshot.board,
                    userSettings = outcome.snapshot.user,
                    busy = false,
                    notice = "Setting saved",
                )
            }
            is DeckVerifiedWriteOutcome.Rejected -> latest.copy(
                board = outcome.snapshot?.board ?: latest.board,
                userSettings = outcome.snapshot?.user ?: latest.userSettings,
                busy = false,
                errorMessage = outcome.message,
            )
            is DeckVerifiedWriteOutcome.Unknown -> latest.copy(
                busy = false,
                outcomeUnknown = true,
                errorMessage = outcome.message,
            )
        }
    }

    private suspend fun fetchUserSettings(): DeckUserSettings {
        val response = context.execute(deckUserSettingsRequest())
        return parseDeckResponseOffUi { parseDeckUserSettings(response) }
    }
}
