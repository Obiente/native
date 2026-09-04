package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberMigratedAppPinsAuthority(
    repository: AppWorkspacePinsRepository,
    accountScopeDigest: String,
    loaded: AppWorkspacePinsLoad,
): MutableState<Boolean> {
    val authoritative = remember(accountScopeDigest) { mutableStateOf(loaded.storageAuthoritative) }
    LaunchedEffect(accountScopeDigest, loaded.legacyMigrationRequired) {
        if (loaded.legacyMigrationRequired) {
            authoritative.value = withContext(Dispatchers.Default) {
                repository.save(accountScopeDigest, loaded.appIds)
            }
        }
    }
    return authoritative
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
