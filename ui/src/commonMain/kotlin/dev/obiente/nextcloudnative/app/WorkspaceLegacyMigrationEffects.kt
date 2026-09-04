package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AppWorkspacePinsCompositionState(
    val appIds: MutableState<List<String>>,
    val storageAuthoritative: MutableState<Boolean>,
    val loadComplete: Boolean,
)

@Composable
internal fun rememberAppWorkspacePinsCompositionState(
    repository: AppWorkspacePinsRepository,
    accountScopeDigest: String,
    legacyAccountScopeDigest: String?,
): AppWorkspacePinsCompositionState {
    val coordinator = remember(repository, accountScopeDigest, legacyAccountScopeDigest) {
        AppWorkspacePinsLoadCoordinator {
            repository.loadWithProvenance(accountScopeDigest, legacyAccountScopeDigest)
        }
    }
    val appIds = remember(accountScopeDigest) { mutableStateOf(defaultAppWorkspacePinnedIds()) }
    val authoritative = remember(accountScopeDigest) { mutableStateOf(false) }
    val loaded = remember(accountScopeDigest) { mutableStateOf<AppWorkspacePinsLoad?>(null) }
    LaunchedEffect(coordinator) {
        val initial = coordinator.load()
        val result = if (initial.legacyMigrationRequired) {
            withContext(Dispatchers.Default) {
                repository.resolveLegacyMigration(accountScopeDigest, initial)
            }
        } else {
            initial
        }
        appIds.value = result.appIds
        authoritative.value = result.storageAuthoritative
        loaded.value = result
    }
    return AppWorkspacePinsCompositionState(appIds, authoritative, loaded.value != null)
}

internal data class HomeWorkspaceLayoutCompositionState(
    val layout: MutableState<HomeWorkspaceLayout>,
    val storageAuthoritative: MutableState<Boolean>,
)

@Composable
internal fun rememberMigratedHomeWorkspaceLayoutState(
    repository: HomeWorkspaceLayoutRepository,
    scope: HomeWorkspaceScope,
    legacyAccountScopeDigest: String?,
): HomeWorkspaceLayoutCompositionState {
    val loaded = remember(scope, legacyAccountScopeDigest) {
        repository.loadWithMigration(scope, legacyAccountScopeDigest)
    }
    val layout = remember(scope, legacyAccountScopeDigest) { mutableStateOf(loaded.layout) }
    val authoritative = remember(scope, legacyAccountScopeDigest) {
        mutableStateOf(loaded.storageAuthoritative)
    }
    LaunchedEffect(scope, loaded.legacyMigrationRequired) {
        if (loaded.legacyMigrationRequired) {
            val resolved = withContext(Dispatchers.Default) {
                repository.resolveLegacyMigration(loaded)
            }
            layout.value = resolved.layout
            authoritative.value = resolved.storageAuthoritative
        }
    }
    return HomeWorkspaceLayoutCompositionState(layout, authoritative)
}
