package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The platform host reports whether its application window is visible. */
val LocalAppWindowVisibility = staticCompositionLocalOf<StateFlow<Boolean>> { MutableStateFlow(false) }

internal fun administrationAccessPollingActive(screen: Screen, destination: NextcloudDestination): Boolean =
    screen == Screen.AdminApps || (screen == Screen.Root && destination == NextcloudDestination.Settings)

internal class AdministrationAccessController(
    val state: AdministrationAccessState,
    private val permissionCheck: () -> Boolean,
    val refresh: () -> Unit,
) {
    val canAdminister: Boolean get() = permissionCheck()
}

@Composable
internal fun rememberAdministrationAccess(
    session: NextcloudSession,
    refreshRequest: Long,
    active: Boolean,
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
): AdministrationAccessController = key(session) {
    val repository = remember { AdministrationAccessRepository() }
    val currentExecute by rememberUpdatedState(execute)
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    DisposableEffect(repository) { onDispose { repository.retire() } }
    val visibility = LocalAppWindowVisibility.current
    LaunchedEffect(repository, refreshRequest, active, visibility) {
        if (active) visibility.collectLatest { visible ->
            while (visible && isActive) {
                repository.refresh { request -> currentExecute(request) }
                val age = repository.state.value.checkedAt?.elapsedNow() ?: AdministrationAccessTtl
                delay((AdministrationAccessTtl - age).coerceAtLeast(kotlin.time.Duration.ZERO))
            }
        }
    }
    AdministrationAccessController(state, permissionCheck = { repository.state.value.canAdminister }) {
        scope.launch { repository.refresh(force = true) { currentExecute(it) } }
    }
}
