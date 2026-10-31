package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedControl
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedOption
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
 * Native server administration: apps, accounts, groups, and server information.
 * Nothing renders until administrator access is verified, and every change is password-confirmed.
 */
@Composable
internal fun AdminAppsScreen(
    access: AdministrationAccessController,
    session: NextcloudSession,
    signedInUserId: String,
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    serverInfo: NextcloudServerInfo?,
    onOpenApp: (NextcloudAppEntry) -> Unit,
    onBack: () -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf((AdministrationTabRequests.consume() ?: AdministrationTab.Apps).name) }
    val tab = AdministrationTab.entries.firstOrNull { it.name == tabName } ?: AdministrationTab.Apps
    val content = adminAppsContent(access.state, access.canAdminister)
    val currentExecute by rememberUpdatedState(execute)
    val transport = LocalAccountPasswordConfirmedTransport.current
    val scope = rememberCoroutineScope()
    val users = remember(session.accountId, signedInUserId, transport) {
        AdminUsersController(scope, session, signedInUserId, { currentExecute(it) }, transport)
    }

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
                        AdministrationTab.Apps -> AdminAppsPane(
                            access = access,
                            catalog = content.catalog,
                            session = session,
                            execute = { currentExecute(it) },
                            transport = transport,
                            serverInfo = serverInfo,
                            onOpenApp = onOpenApp,
                        )
                        AdministrationTab.Accounts -> AdminUsersPane(users, signedInUserId)
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
    access: AdministrationAccessController,
    catalog: NativeAppCatalog,
    session: NextcloudSession,
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    transport: AccountPasswordConfirmedTransport?,
    serverInfo: NextcloudServerInfo?,
    onOpenApp: (NextcloudAppEntry) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var catalogFilter by remember { mutableStateOf(NativeAppCatalogFilter.All) }
    var pending by remember(catalog) { mutableStateOf<Pair<NativeManagedApp, NativeAppLifecycleAction>?>(null) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        message?.let { (text, failed) ->
            Text(
                text,
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Large).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        NativeAppCatalogSurface(
            catalog = catalog,
            query = search,
            filter = catalogFilter,
            onQueryChanged = { search = it },
            onFilterChanged = { catalogFilter = it },
            onOpenInstalledApp = { managed -> serverInfo?.apps?.firstOrNull { app -> app.id == managed.id }?.let(onOpenApp) },
            onLifecycleAction = { app, action -> if (access.canAdminister && !running) pending = app to action },
        )
    }
    pending?.let { (app, action) ->
        AccountPasswordConfirmationDialog(
            title = "${action.uiLabel()} ${app.name}?",
            description = action.lifecycleDescription(),
            confirmLabel = action.uiLabel(),
            destructive = action == NativeAppLifecycleAction.Uninstall || action == NativeAppLifecycleAction.Disable,
            busy = running,
            transportAvailable = transport != null,
            onDismiss = { pending = null },
            onConfirm = { secret ->
                val activeTransport = transport
                if (activeTransport == null || !access.canAdminister) {
                    secret.clear()
                    pending = null
                } else {
                    running = true
                    scope.launch {
                        val outcome = try {
                            runNativeAppLifecycle(activeTransport, session, catalog, app, action, secret) {
                                loadNativeAppCatalog(execute)
                            }
                        } finally {
                            secret.clear()
                            running = false
                            pending = null
                        }
                        message = (outcome.failureMessage() ?: "${app.name}: ${action.uiLabel().lowercase()} completed.") to
                            (outcome != ConfirmedWriteOutcome.Applied)
                        // The verified catalog is also the permission evidence, so it is re-read after every change.
                        access.refresh()
                    }
                }
            },
        )
    }
}
