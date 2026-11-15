package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedControl
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedOption
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.flow.MutableStateFlow

internal sealed interface AdminAppsContent {
    data object Checking : AdminAppsContent
    data class Catalog(val catalog: NativeAppCatalog) : AdminAppsContent
    enum class Failure(val message: String) : AdminAppsContent {
        Forbidden("This account does not have permission to manage server apps. You can return to Settings."),
        Unavailable("Administrator access could not be verified. Try again, or return to Settings."),
        InvalidResponse("The server returned an unexpected response while checking administrator access. Try again, or return to Settings."),
        Expired("Administrator access needs to be checked again before server apps can be shown."),
    }
}

internal fun adminAppsContent(state: AdministrationAccessState, canAdminister: Boolean): AdminAppsContent {
    if (state.checking) return AdminAppsContent.Checking
    return when (val result = state.result) {
        null -> AdminAppsContent.Checking
        is NativeAppCatalogResult.Available -> if (canAdminister && state.canAdminister) {
            AdminAppsContent.Catalog(result.catalog)
        } else {
            AdminAppsContent.Failure.Expired
        }
        NativeAppCatalogResult.Forbidden -> AdminAppsContent.Failure.Forbidden
        NativeAppCatalogResult.Unavailable -> AdminAppsContent.Failure.Unavailable
        is NativeAppCatalogResult.InvalidResponse -> AdminAppsContent.Failure.InvalidResponse
    }
}

internal enum class AdministrationTab(val label: String) {
    Apps("Apps"),
    Accounts("Accounts"),
    Groups("Groups"),
    Server("Server"),
}

/** Settings asks for a starting tab before opening administration; the screen consumes it once. */
internal object AdministrationTabRequests {
    private val requested = MutableStateFlow<AdministrationTab?>(null)

    fun request(tab: AdministrationTab) {
        requested.value = tab
    }

    fun consume(): AdministrationTab? = requested.value.also { requested.value = null }
}

/**
 * Native server administration: apps, accounts, groups, and server information, read-only.
 * Nothing renders until administrator access is verified. Lifecycle and provisioning changes need a
 * password confirmation that the app-password sign-in cannot provide, so no change is offered.
 */
@Composable
internal fun AdminAppsScreen(
    access: AdministrationAccessController,
    session: NextcloudSession,
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    serverInfo: NextcloudServerInfo?,
    onOpenApp: (NextcloudAppEntry) -> Unit,
    onBack: () -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf((AdministrationTabRequests.consume() ?: AdministrationTab.Apps).name) }
    val tab = AdministrationTab.entries.firstOrNull { it.name == tabName } ?: AdministrationTab.Apps
    val content = adminAppsContent(access.state, access.canAdminister)
    val currentExecute by rememberUpdatedState(execute)
    val scope = rememberCoroutineScope()
    val users = remember(session.accountId) { AdminUsersController(scope) { currentExecute(it) } }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(title = "Administration", subtitle = tab.label, onBack = onBack)
        when (content) {
            AdminAppsContent.Checking -> LoadingMessage("Checking administrator access...")
            is AdminAppsContent.Failure -> ErrorMessage(content.message, onRetry = access.refresh)
            is AdminAppsContent.Catalog -> {
                NextcloudSegmentedControl(
                    options = AdministrationTab.entries.map { NextcloudSegmentedOption(it.name, it.label) },
                    selectedId = tab.name,
                    onSelected = { tabName = it },
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
                    accessibilityLabel = "Administration sections",
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        AdministrationTab.Apps -> AdminAppsPane(content.catalog, serverInfo, onOpenApp)
                        AdministrationTab.Accounts -> AdminUsersPane(users)
                        AdministrationTab.Groups -> AdminGroupsPane(users)
                        AdministrationTab.Server -> AdminServerInfoPane(execute = { currentExecute(it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminAppsPane(
    catalog: NativeAppCatalog,
    serverInfo: NextcloudServerInfo?,
    onOpenApp: (NextcloudAppEntry) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var catalogFilter by remember { mutableStateOf(NativeAppCatalogFilter.All) }
    Column(Modifier.fillMaxSize()) {
        PasswordConfirmationHandoffNotice(Modifier.padding(horizontal = NextcloudSpacing.Large))
        NativeAppCatalogSurface(
            catalog = catalog,
            query = search,
            filter = catalogFilter,
            onQueryChanged = { search = it },
            onFilterChanged = { catalogFilter = it },
            onOpenInstalledApp = { managed -> serverInfo?.apps?.firstOrNull { app -> app.id == managed.id }?.let(onOpenApp) },
            onLifecycleAction = null,
        )
    }
}
