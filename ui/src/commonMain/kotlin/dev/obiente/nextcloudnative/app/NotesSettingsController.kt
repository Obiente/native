package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

data class NotesUiSettingsState(
    val loading: Boolean = true,
    val capabilities: NotesApiCapabilities? = null,
    /** Notes is not installed or does not advertise an API version. */
    val notesUnavailable: Boolean = false,
    val settings: NotesSettings? = null,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val notice: String? = null,
    /** A save could not be confirmed. Saving stays blocked until settings load again. */
    val outcomeUnknown: Boolean = false,
) {
    val canSave: Boolean
        get() = capabilities?.supportsSettings == true && settings != null && !busy && !loading && !outcomeUnknown
}

/**
 * Loads and saves Notes settings. Settings writes are idempotent, so an unknown result is
 * resolved by reading the settings again; the stored values always come from the server.
 */
internal class NotesSettingsController(
    private val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    private val onNotesFolderChanged: () -> Unit,
) {
    var state: NotesUiSettingsState by mutableStateOf(NotesUiSettingsState())
        private set

    suspend fun load() {
        state = state.copy(loading = true, errorMessage = null)
        try {
            val capabilityResponse = execute(notesCapabilitiesRequest())
            val capabilities = parseNotesApiCapabilities(capabilityResponse)
            if (capabilities == null || !capabilities.supportsSettings) {
                state = state.copy(
                    loading = false,
                    capabilities = capabilities,
                    notesUnavailable = capabilities == null,
                    settings = null,
                    outcomeUnknown = false,
                )
                return
            }
            val settings = parseNotesSettings(execute(notesSettingsRequest()))
            state = state.copy(
                loading = false,
                capabilities = capabilities,
                settings = settings,
                outcomeUnknown = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            state = state.copy(loading = false, errorMessage = failure.message ?: "Notes settings could not be loaded.")
        }
    }

    suspend fun save(change: NotesSettingsChange) {
        val current = state
        val capabilities = current.capabilities
        val previous = current.settings
        if (!current.canSave || capabilities == null || previous == null) return
        val request = try {
            updateNotesSettingsRequest(capabilities, change)
        } catch (invalid: IllegalArgumentException) {
            state = current.copy(errorMessage = invalid.message)
            return
        }
        state = current.copy(busy = true, errorMessage = null, notice = null)
        var status: Int? = null
        val writeFailure: Exception? = try {
            val response = execute(request)
            status = response.status
            parseNotesSettings(response)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }
        if (writeFailure != null && status != null && status in 400..499) {
            state = state.copy(busy = false, errorMessage = writeFailure.message ?: "Notes refused the settings.")
            return
        }
        val stored = try {
            parseNotesSettings(execute(notesSettingsRequest()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state = state.copy(
                busy = false,
                outcomeUnknown = true,
                errorMessage = "Notes may have saved these settings. Reload them before changing anything else.",
            )
            return
        }
        val folderChanged = stored.notesPath != previous.notesPath
        val unchanged = stored == previous
        state = state.copy(
            busy = false,
            settings = stored,
            errorMessage = when {
                unchanged && writeFailure != null -> writeFailure.message ?: "Notes did not save the settings."
                unchanged -> "Notes kept the previous settings."
                else -> null
            },
            notice = if (unchanged) {
                null
            } else {
                describeNotesSettingsAdjustment(change, stored) ?: "Notes settings saved"
            },
        )
        if (folderChanged) onNotesFolderChanged()
    }
}
