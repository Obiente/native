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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

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
    if (!access.canAdminister) {
        if (access.state.checking) {
            LoadingMessage("Checking administrator access...")
        } else {
            LaunchedEffect(access.state) { onBack() }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            title = "Server apps",
            subtitle = "Administrator app management",
            onBack = onBack,
        )
        when (val result = access.state.result) {
            null -> LoadingMessage("Loading administrator app catalog...")
            is NativeAppCatalogResult.Available -> NativeAppCatalogSurface(
                catalog = result.catalog,
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
            NativeAppCatalogResult.Forbidden -> ErrorMessage(
                "This account does not have permission to manage server apps.",
                onRetry = access.refresh,
            )
            NativeAppCatalogResult.Unavailable -> ErrorMessage(
                "Administrator app management is unavailable on this server.",
                onRetry = access.refresh,
            )
            is NativeAppCatalogResult.InvalidResponse -> ErrorMessage(
                result.reason,
                onRetry = access.refresh,
            )
        }
    }

    pendingLifecycleAction?.let { (app, action) ->
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
