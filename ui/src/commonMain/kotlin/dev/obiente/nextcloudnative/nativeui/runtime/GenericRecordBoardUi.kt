package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudBoardDragHandle
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

@Composable
internal fun GenericBoardCard(
    resource: ResourceSpec,
    record: NativeRecord,
    actions: NativeBoardCardActionPlan,
    busy: Boolean,
    dragging: Boolean,
    onOpen: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onMove: (() -> Unit)?,
    onDragStart: ((Offset) -> Unit)?,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDirectAction: (NativeBoardDirectActionPlan) -> Unit,
) {
    val presentation = nativeRecordPresentation(resource, record)
    var actionMenuExpanded by remember(record.id) { mutableStateOf(false) }
    val menuActions = buildList {
        onEdit?.let { edit -> add(NextcloudCardAction("Edit", enabled = !busy, onClick = edit)) }
        onMove?.let { move -> add(NextcloudCardAction("Move", enabled = !busy, onClick = move)) }
        actions.directActions.forEach { plan ->
            add(
                NextcloudCardAction(
                    label = plan.label,
                    destructive = plan.kind == NativeBoardDirectActionKind.Delete,
                    enabled = !busy,
                    onClick = { onDirectAction(plan) },
                ),
            )
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer {
                alpha = if (dragging) 0.18f else 1f
            }
            .nextcloudCardInteractions(
                onOpen = onOpen,
                onShowActions = if (menuActions.isNotEmpty()) {
                    { actionMenuExpanded = true }
                } else {
                    null
                },
                openLabel = "Open ${presentation.title}",
                actionsLabel = "Show actions for ${presentation.title}",
            ),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                onDragStart?.let { startDrag ->
                    NextcloudBoardDragHandle(
                        itemLabel = presentation.title,
                        dragActive = dragging,
                        onDragStart = startDrag,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                }
                Text(
                    presentation.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                NextcloudCardOverflow(
                    itemLabel = presentation.title,
                    actions = menuActions,
                    expanded = actionMenuExpanded,
                    onExpandedChange = { actionMenuExpanded = it },
                )
            }
            presentation.subtitle?.let { subtitle ->
                Text(
                    subtitle,
                    modifier = Modifier.padding(top = NextcloudSpacing.XSmall),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NativeRecordFacts(resource, record, modifier = Modifier.padding(top = NextcloudSpacing.XSmall), maximumFacts = 3)
        }
    }
}

@Composable
internal fun NativeBoardCreateDialog(
    target: NativeBoardCreatePlan,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var title by remember(target.lane.key, target.action.id) { mutableStateOf("") }
    var description by remember(target.lane.key, target.action.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add card to ${target.lane.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title") },
                    singleLine = true,
                    enabled = !busy,
                )
                if (target.descriptionBodyFieldName != null) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Description") },
                        minLines = 3,
                        enabled = !busy,
                    )
                }
                Text(
                    "The new card will be created directly in this lane.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = !busy && title.isNotBlank(),
                onClick = { onCreate(title, description) },
            ) {
                Text(if (busy) "Creating..." else "Create")
            }
        },
    )
}

@Composable
internal fun NativeBoardDirectActionDialog(
    target: NativeBoardDirectActionTarget,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val destructive = target.plan.kind == NativeBoardDirectActionKind.Delete
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${target.plan.label} ${nativeRecordTitle(target.record)}?") },
        text = {
            Text(
                if (destructive) {
                    "This removes the card from the server. Continue only if you are sure."
                } else {
                    "This updates the card on the server."
                },
            )
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = !busy,
                colors = if (destructive) {
                    androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    )
                } else {
                    androidx.compose.material3.ButtonDefaults.buttonColors()
                },
                onClick = onConfirm,
            ) {
                Text(if (busy) "Working..." else target.plan.label)
            }
        },
    )
}

@Composable
internal fun NativeBoardEditDialog(
    target: NativeBoardEditTarget,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    var values by remember(target.record.id, target.plan.action.id) {
        mutableStateOf(target.plan.initialValues)
    }
    var errors by remember(target.record.id, target.plan.action.id) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${nativeRecordTitle(target.record)}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                target.plan.fields.forEach { editable ->
                    OutlinedTextField(
                        value = values[editable.field.id].orEmpty(),
                        onValueChange = { value ->
                            values = values + (editable.field.id to value)
                            errors = errors - editable.field.id
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(editable.field.label) },
                        minLines = if (editable.field.kind == FieldKind.longText) 4 else 1,
                        isError = editable.field.id in errors,
                        supportingText = errors[editable.field.id]?.let { message -> { Text(message) } },
                        enabled = !busy,
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    val validation = target.plan.fields.mapNotNull { editable ->
                        validateNativeCellEdit(editable.field, values[editable.field.id].orEmpty())
                            ?.let { editable.field.id to it }
                    }.toMap()
                    if (validation.isEmpty()) onSave(values) else errors = validation
                },
            ) {
                Text(if (busy) "Saving..." else "Save")
            }
        },
    )
}

@Composable
internal fun NativeBoardMoveDialog(
    target: NativeBoardMoveTargetSelection,
    busy: Boolean,
    onDismiss: () -> Unit,
    onMove: (NativeBoardMoveTarget) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${nativeRecordTitle(target.record)}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Text(
                    "Choose a destination lane. The board will refresh before the move is reported as confirmed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                target.plan.targets.forEach { destination ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        onClick = { onMove(destination) },
                    ) {
                        Text(destination.title)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

private fun nativeRecordTitle(record: NativeRecord): String =
    listOf("title", "name", "subject").firstNotNullOfOrNull { expected ->
        record.values.entries.firstOrNull { it.key.equals(expected, ignoreCase = true) }
            ?.value
            ?.takeIf(String::isNotBlank)
    } ?: "card"
