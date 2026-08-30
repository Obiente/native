package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationFormAction
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec

@Composable
internal fun DynamicCollectionHeaderActions(
    schema: NativeAppSchema,
    appName: String,
    primaryAction: Pair<DynamicNavigationFormAction, ViewSpec>?,
    overflowActions: List<Pair<DynamicNavigationFormAction, ViewSpec>>,
    secondaryDestinations: List<Pair<DynamicNavigationDestination, ViewSpec>>,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onActionSelected: (DynamicNavigationFormAction, ViewSpec) -> Unit,
    onDestinationSelected: (DynamicNavigationDestination, ViewSpec) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        primaryAction?.let { (action, view) ->
            val label = schema.action(action.actionId)?.let { spec ->
                dynamicHeaderActionLabel(spec, view.dynamicActionLabel())
            } ?: view.dynamicActionLabel()
            IconButton(onClick = { onActionSelected(action, view) }) {
                Icon(NextcloudIcons.Add, contentDescription = label)
            }
        }
        if (overflowActions.isNotEmpty() || secondaryDestinations.isNotEmpty()) {
            Box {
                IconButton(onClick = { onMenuExpandedChange(true) }) {
                    Icon(NextcloudIcons.More, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) },
                ) {
                    overflowActions.forEach { (action, view) ->
                        val label = schema.action(action.actionId)?.let { spec ->
                            dynamicHeaderActionLabel(spec, view.dynamicActionLabel())
                        } ?: view.dynamicActionLabel()
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { onActionSelected(action, view) },
                        )
                    }
                    if (overflowActions.isNotEmpty() && secondaryDestinations.isNotEmpty()) {
                        HorizontalDivider()
                    }
                    secondaryDestinations.forEach { (destination, view) ->
                        val baseLabel = destination.label.dynamicUiLabel(appName)
                        val duplicate = secondaryDestinations.count { (candidate, _) ->
                            candidate.label.dynamicUiLabel(appName)
                                .equals(baseLabel, ignoreCase = true)
                        } > 1
                        val label = dynamicSecondaryDestinationLabel(
                            destinationLabel = baseLabel,
                            resourceLabel = schema.resource(view.resourceId)?.name ?: view.resourceId,
                            duplicate = duplicate,
                        )
                        DropdownMenuItem(
                            text = { Text(label) },
                            modifier = Modifier.semantics {
                                contentDescription = "Open $label"
                            },
                            onClick = { onDestinationSelected(destination, view) },
                        )
                    }
                }
            }
        }
    }
}

internal fun String.dynamicUiLabel(appName: String): String {
    val cleaned = removePrefix("API ").removePrefix("Api ").removePrefix("api ").trim()
    return when {
        cleaned.equals("general", ignoreCase = true) -> appName
        cleaned.equals("prefs", ignoreCase = true) -> "Preferences"
        else -> cleaned
    }
}

internal fun ViewSpec.dynamicActionLabel(): String = title
    .replace(Regex("^\\[api\\s+v?[0-9.]+]\\s*", RegexOption.IGNORE_CASE), "")
    .trim()
    .replaceFirstChar { character -> character.titlecase() }
