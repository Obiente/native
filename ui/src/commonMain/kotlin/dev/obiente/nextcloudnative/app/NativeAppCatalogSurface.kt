package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions

enum class NativeAppCatalogFilter {
    All,
    Enabled,
    Disabled,
    Updates,
}

fun filterNativeAppCatalog(
    catalog: NativeAppCatalog,
    query: String,
    filter: NativeAppCatalogFilter,
): List<NativeManagedApp> = catalog.apps.filter { app ->
    val matchesQuery = query.isBlank() ||
        app.name.contains(query, ignoreCase = true) ||
        app.id.contains(query, ignoreCase = true)
    val matchesFilter = when (filter) {
        NativeAppCatalogFilter.All -> true
        NativeAppCatalogFilter.Enabled -> app.installed && app.enabled
        NativeAppCatalogFilter.Disabled -> app.installed && !app.enabled
        NativeAppCatalogFilter.Updates -> app.updateAvailable
    }
    matchesQuery && matchesFilter
}

@Composable
fun NativeAppCatalogSurface(
    catalog: NativeAppCatalog,
    query: String,
    filter: NativeAppCatalogFilter,
    onQueryChanged: (String) -> Unit,
    onFilterChanged: (NativeAppCatalogFilter) -> Unit,
    onOpenInstalledApp: (NativeManagedApp) -> Unit,
    onLifecycleAction: (NativeManagedApp, NativeAppLifecycleAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val apps = filterNativeAppCatalog(catalog, query, filter)
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = NextcloudSpacing.XLarge,
                vertical = NextcloudSpacing.Medium,
            ),
            placeholder = { Text("Find an app") },
            singleLine = true,
            shape = RoundedCornerShape(NextcloudRadii.Card),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            NativeAppCatalogFilter.entries.forEach { candidate ->
                if (candidate != NativeAppCatalogFilter.Updates || catalog.includesUpdateAvailability) {
                    FilterChip(
                        selected = candidate == filter,
                        onClick = { onFilterChanged(candidate) },
                        label = { Text(candidate.name) },
                    )
                }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = NextcloudSpacing.XLarge,
                top = NextcloudSpacing.Medium,
                end = NextcloudSpacing.XLarge,
                bottom = NextcloudSpacing.XXLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            items(apps, key = NativeManagedApp::id) { app ->
                NativeManagedAppCard(
                    catalog = catalog,
                    app = app,
                    onOpen = { onOpenInstalledApp(app) },
                    onLifecycleAction = { action -> onLifecycleAction(app, action) },
                )
            }
        }
    }
}

@Composable
private fun NativeManagedAppCard(
    catalog: NativeAppCatalog,
    app: NativeManagedApp,
    onOpen: () -> Unit,
    onLifecycleAction: (NativeAppLifecycleAction) -> Unit,
) {
    val actions = availableNativeAppLifecycleActions(catalog, app)
    var menuExpanded by remember(app.id) { mutableStateOf(false) }
    val menuActions = actions.sortedBy(NativeAppLifecycleAction::ordinal).map { action ->
        NextcloudCardAction(
            label = action.uiLabel(),
            destructive = action == NativeAppLifecycleAction.Uninstall,
            onClick = { onLifecycleAction(action) },
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
            onOpen = onOpen.takeIf { app.installed && app.enabled },
            onShowActions = if (menuActions.isNotEmpty()) {
                { menuExpanded = true }
            } else {
                null
            },
            openLabel = "Open ${app.name}",
            actionsLabel = "Show actions for ${app.name}",
        ),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        app.summary ?: app.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    when {
                        !app.installed -> "Available"
                        app.enabled -> "Enabled"
                        else -> "Disabled"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (app.enabled) NextcloudTheme.colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NextcloudCardOverflow(
                    itemLabel = app.name,
                    actions = menuActions,
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                )
            }
            app.availableVersion?.let { version ->
                Text(
                    "Update available: $version",
                    modifier = Modifier.padding(top = NextcloudSpacing.Small),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (app.compatible == false || app.missingDependencies.isNotEmpty()) {
                Text(
                    "Not currently compatible. Review dependencies in server administration.",
                    modifier = Modifier.padding(top = NextcloudSpacing.Small),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (app.externalApp) {
                Text(
                    "External app lifecycle is managed through AppAPI.",
                    modifier = Modifier.padding(top = NextcloudSpacing.Small),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun NativeAppLifecycleAction.uiLabel(): String = when (this) {
    NativeAppLifecycleAction.InstallAndEnable -> "Install"
    NativeAppLifecycleAction.Enable -> "Enable"
    NativeAppLifecycleAction.Disable -> "Disable"
    NativeAppLifecycleAction.Update -> "Update"
    NativeAppLifecycleAction.Uninstall -> "Uninstall"
}
