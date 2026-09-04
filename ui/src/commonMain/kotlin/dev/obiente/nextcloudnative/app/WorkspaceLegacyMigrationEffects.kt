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
        val result = coordinator.load()
        appIds.value = result.appIds
        authoritative.value = if (result.legacyMigrationRequired) {
            withContext(Dispatchers.Default) { repository.saveIfAbsent(accountScopeDigest, result.appIds) }
        } else {
            result.storageAuthoritative
        }
        loaded.value = result
    }
    return AppWorkspacePinsCompositionState(appIds, authoritative, loaded.value != null)
}

@Composable
internal fun rememberMigratedHomeWorkspaceLayout(
    repository: HomeWorkspaceLayoutRepository,
    scope: HomeWorkspaceScope,
    legacyAccountScopeDigest: String?,
): MutableState<HomeWorkspaceLayout> {
    val loaded = remember(scope, legacyAccountScopeDigest) {
        repository.loadWithMigration(scope, legacyAccountScopeDigest)
    }
    val layout = remember(scope, legacyAccountScopeDigest) { mutableStateOf(loaded.layout) }
    LaunchedEffect(scope, loaded.legacyMigrationRequired) {
        if (loaded.legacyMigrationRequired) {
            withContext(Dispatchers.Default) { repository.saveIfAbsent(loaded.layout) }
        }
    }
    return layout
}
