package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

internal open class NextcloudSessionStorageUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class NextcloudSessionLegacyMigrationUnavailableException(
    cause: Throwable,
) : NextcloudSessionStorageUnavailableException(
    "The legacy secure-storage provider required for session migration is unavailable.",
    cause,
)

internal sealed interface NextcloudSessionLoadState {
    data class Loaded(val session: NextcloudSession?) : NextcloudSessionLoadState

    data object SecureStorageUnavailable : NextcloudSessionLoadState

    data object LegacyMigrationUnavailable : NextcloudSessionLoadState
}

internal fun loadNextcloudSessionSafely(
    loadSession: () -> NextcloudSession?,
): NextcloudSessionLoadState = try {
    NextcloudSessionLoadState.Loaded(loadSession())
} catch (failure: CancellationException) {
    throw failure
} catch (_: NextcloudSessionLegacyMigrationUnavailableException) {
    NextcloudSessionLoadState.LegacyMigrationUnavailable
} catch (_: NextcloudSessionStorageUnavailableException) {
    NextcloudSessionLoadState.SecureStorageUnavailable
}
