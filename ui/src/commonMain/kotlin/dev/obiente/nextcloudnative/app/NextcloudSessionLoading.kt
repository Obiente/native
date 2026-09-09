package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

internal class NextcloudSessionLoadCoordinator(
    private val loadSession: () -> NextcloudSession?,
) {
    var state: NextcloudSessionLoadState? = null
        private set

    suspend fun load(dispatcher: CoroutineDispatcher = Dispatchers.Default): NextcloudSessionLoadState {
        val loaded = withContext(dispatcher) { loadNextcloudSessionSafely(loadSession) }
        state = loaded
        return loaded
    }
}

internal data class NextcloudSessionCompositionState(
    val loadState: NextcloudSessionLoadState?,
    val session: MutableState<NextcloudSession?>,
)

@Composable
internal fun rememberNextcloudSessionCompositionState(
    services: NextcloudPlatformServices,
    loadAttempt: Int,
): NextcloudSessionCompositionState {
    val coordinator = remember(services, loadAttempt) { NextcloudSessionLoadCoordinator(services::loadSession) }
    val loadState = remember(services, loadAttempt) {
        mutableStateOf<NextcloudSessionLoadState?>(null)
    }
    val session = remember(services, loadAttempt) { mutableStateOf<NextcloudSession?>(null) }
    LaunchedEffect(coordinator) {
        val loaded = coordinator.load()
        loadState.value = loaded
        session.value = (loaded as? NextcloudSessionLoadState.Loaded)?.session
    }
    return NextcloudSessionCompositionState(loadState.value, session)
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
