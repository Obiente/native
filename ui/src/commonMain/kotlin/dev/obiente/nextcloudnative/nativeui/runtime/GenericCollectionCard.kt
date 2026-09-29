package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

@Composable
internal fun GenericCollectionCard(
    resource: ResourceSpec,
    record: NativeRecord,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    secondaryActions: List<NextcloudCardAction> = emptyList(),
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    busy: Boolean = false,
    primaryContent: (@Composable () -> Unit)? = null,
) {
    val presentation = nativeRecordPresentation(resource, record)
    val dense = LocalNextcloudWorkspaceCapabilities.current.usesDenseControls
    var actionsExpanded by rememberSaveable(record.id) { mutableStateOf(false) }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = if (dense) NextcloudSpacing.Medium else NextcloudSpacing.Large,
                vertical = if (dense) NextcloudSpacing.Small else NextcloudSpacing.Large,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                if (dense) NextcloudSpacing.Small else NextcloudSpacing.Medium,
            ),
        ) {
            leadingContent?.invoke()
            GenericResourceIcon(resource, presentation.iconKey, presentation.colorArgb)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (primaryContent != null) primaryContent() else {
                    Text(
                        presentation.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    presentation.subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    NativeRecordFacts(resource, record)
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (secondaryActions.isNotEmpty()) {
                NextcloudCardOverflow(
                    itemLabel = presentation.title,
                    actions = secondaryActions,
                    expanded = actionsExpanded,
                    onExpandedChange = { actionsExpanded = it },
                )
            } else if (onSelectRecord != null) {
                Icon(
                    NextcloudIcons.ChevronRight,
                    contentDescription = "Open ${presentation.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                if (onSelectRecord != null) {
                    contentDescription = "Open ${presentation.title}"
                }
            }
            .nextcloudCardInteractions(
                onOpen = onSelectRecord?.let { select -> { select(record) } },
                onShowActions = if (secondaryActions.isNotEmpty()) {
                    { actionsExpanded = true }
                } else {
                    null
                },
                openLabel = "Open ${presentation.title}",
                actionsLabel = "Show actions for ${presentation.title}",
            ),
        color = if (dense) MaterialTheme.colorScheme.background else NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(if (dense) 0.dp else NextcloudRadii.Card),
        content = content,
    )
}


/** Optional semantic card bodies; interaction and action ownership stays with the collection. */
internal fun nativeCollectionCardPrimaryContent(
    schema: NativeAppSchema,
    resource: ResourceSpec,
): (@Composable (NativeRecord) -> Unit)? {
    if (schema.app.id == "tables" && resource.id == "tables") {
        return { record ->
            NativeTableInventoryContent(requireNotNull(nativeTableInventoryPresentation(schema, resource, record)))
        }
    }
    val pantryKind = nativePantryCollectionKind(schema, resource) ?: return null
    return { record -> NativePantryRecordContent(pantryKind, record) }
}