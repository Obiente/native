package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

internal open class NextcloudSessionStorageUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal sealed interface NextcloudSessionLoadState {
    data class Loaded(val session: NextcloudSession?) : NextcloudSessionLoadState

    data object SecureStorageUnavailable : NextcloudSessionLoadState
}

internal fun loadNextcloudSessionSafely(
    loadSession: () -> NextcloudSession?,
): NextcloudSessionLoadState = try {
    NextcloudSessionLoadState.Loaded(loadSession())
} catch (failure: CancellationException) {
    throw failure
} catch (_: NextcloudSessionStorageUnavailableException) {
    NextcloudSessionLoadState.SecureStorageUnavailable
}
