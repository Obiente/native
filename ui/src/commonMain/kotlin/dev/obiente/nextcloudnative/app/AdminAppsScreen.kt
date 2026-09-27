package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

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

@Composable
internal fun AdminAppsScreen(
    access: AdministrationAccessController,
    onContinueInBrowser: () -> Unit,
    serverInfo: NextcloudServerInfo?,
    onOpenApp: (NextcloudAppEntry) -> Unit,
    onBack: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var catalogFilter by remember { mutableStateOf(NativeAppCatalogFilter.All) }
    var pendingLifecycleAction by remember(access.state.result) {
        mutableStateOf<Pair<NativeManagedApp, NativeAppLifecycleAction>?>(null)
    }
    val content = adminAppsContent(access.state, access.canAdminister)

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            title = "Server apps",
            subtitle = "Administrator app management",
            onBack = onBack,
        )
        when (content) {
            AdminAppsContent.Checking -> LoadingMessage("Checking administrator access...")
            is AdminAppsContent.Catalog -> NativeAppCatalogSurface(
                catalog = content.catalog,
                query = search,
                filter = catalogFilter,
                onQueryChanged = { search = it },
                onFilterChanged = { catalogFilter = it },
                onOpenInstalledApp = { managed ->
                    serverInfo?.apps?.firstOrNull { app -> app.id == managed.id }?.let(onOpenApp)
                },
                onLifecycleAction = { app, action ->
                    if (access.canAdminister) pendingLifecycleAction = app to action
                },
            )
            is AdminAppsContent.Failure -> ErrorMessage(
                content.message,
                onRetry = access.refresh,
            )
        }
    }

    pendingLifecycleAction?.takeIf { content is AdminAppsContent.Catalog }?.let { (app, action) ->
        AlertDialog(
            onDismissRequest = { pendingLifecycleAction = null },
            title = { Text("${action.uiLabel()} ${app.name}?") },
            text = {
                Text(
                    when (action) {
                        NativeAppLifecycleAction.InstallAndEnable ->
                            "This downloads server-side code and enables the app for users."
                        NativeAppLifecycleAction.Enable ->
                            "This activates the app and may add navigation, jobs, and integrations for users."
                        NativeAppLifecycleAction.Disable ->
                            "This makes the app and its integrations unavailable until an administrator enables it again."
                        NativeAppLifecycleAction.Update ->
                            "The server may enter maintenance mode while the app package is updated. Do not interrupt it."
                        NativeAppLifecycleAction.Uninstall ->
                            "This removes the app package. App data retention depends on the app and is not guaranteed."
                    } + "\n\nNextcloud requires administrator password confirmation. Continue in the authenticated server administration page.",
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingLifecycleAction = null }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingLifecycleAction = null
                        if (access.canAdminister) {
                            onContinueInBrowser()
                            access.refresh()
                        }
                    },
                    colors = if (action == NativeAppLifecycleAction.Uninstall) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text("Continue in browser")
                }
            },
        )
    }
}
