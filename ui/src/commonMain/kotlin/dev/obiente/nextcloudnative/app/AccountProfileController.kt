package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Immutable
internal data class AccountProfileUiState(
    val profile: AccountProfile? = null,
    val loading: Boolean = true,
    val loadFailure: String? = null,
)

/**
 * Owns the signed-in account's profile for one session. Profile edits need a password
 * confirmation that the app-password sign-in cannot provide, so this holder only reads.
 */
internal class AccountProfileController(
    private val scope: CoroutineScope,
    private val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
) {
    var state by mutableStateOf(AccountProfileUiState())
        private set

    private var loadJob: Job? = null

    fun refresh() {
        loadJob?.cancel()
        // A refresh keeps the last profile visible and replaces it only with a newer read.
        state = state.copy(loading = true, loadFailure = null)
        loadJob = scope.launch {
            state = when (val result = readProfile()) {
                is AccountProfileLoadResult.Loaded -> state.copy(profile = result.profile, loading = false)
                else -> state.copy(loading = false, loadFailure = result.loadFailureMessage())
            }
        }
    }

    private suspend fun readProfile(): AccountProfileLoadResult = try {
        parseAccountProfile(execute(AccountProfileRequests.currentUser()))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Transport fault boundary: no exception text reaches the UI.
        AccountProfileLoadResult.Unavailable
    }
}

internal fun AccountProfileLoadResult.loadFailureMessage(): String = when (this) {
    is AccountProfileLoadResult.Loaded -> ""
    AccountProfileLoadResult.Unauthorized -> "Sign in to this account again to load your profile."
    AccountProfileLoadResult.Unavailable -> "Your profile could not be loaded. Check your connection and try again."
    AccountProfileLoadResult.Malformed -> "The server sent a profile this app cannot read."
}
