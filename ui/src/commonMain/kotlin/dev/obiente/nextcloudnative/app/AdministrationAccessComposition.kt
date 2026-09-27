package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    LaunchedEffect(repository, refreshRequest, active) {
        while (active && isActive) {
            repository.refresh { request -> currentExecute(request) }
            val age = repository.state.value.checkedAt?.elapsedNow() ?: AdministrationAccessTtl
            delay((AdministrationAccessTtl - age).coerceAtLeast(kotlin.time.Duration.ZERO))
        }
    }
    AdministrationAccessController(state, permissionCheck = { repository.state.value.canAdminister }) {
        scope.launch { repository.refresh(force = true) { currentExecute(it) } }
    }
}
