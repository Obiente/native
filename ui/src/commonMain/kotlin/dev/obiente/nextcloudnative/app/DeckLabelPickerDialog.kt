package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
fun DeckUiLabelPickerDialog(
    card: DeckCard,
    availableLabels: List<DeckLabel>,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (List<DeckLabel>) -> Unit,
    onManageLabels: (() -> Unit)? = null,
    onCheckStatus: (() -> Unit)? = null,
    onRepeat: (() -> Unit)? = null,
    saveEnabled: Boolean = true,
) {
    var selectedKeys by remember(card.boardId, card.stackId, card.id) {
        mutableStateOf(card.labels.map(DeckLabel::id).toSet())
    }
    DeckUiAdaptiveDialog(
        title = "Labels",
        supportingText = card.title,
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        confirmLabel = "Save labels",
        confirmEnabled = !busy && saveEnabled,
        busy = busy,
        onConfirm = {
            onSubmit(availableLabels.filter { it.id in selectedKeys })
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            onCheckStatus?.let { check -> item { TextButton(onClick = check, enabled = !busy) { Text("Check earlier changes") } } }
            onRepeat?.let { repeat -> item { TextButton(onClick = repeat, enabled = !busy) { Text("Repeat same change") } } }
            onManageLabels?.let { manageLabels ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = manageLabels, enabled = !busy) {
                            Text("Manage board labels")
                        }
                    }
                }
            }
            if (availableLabels.isEmpty()) {
                item {
                    DeckUiEmptyRelationState(
                        title = "No labels available",
                        message = "Create a board label, then assign it to this card.",
                    )
                }
            }
            items(availableLabels, key = DeckLabel::id) { label ->
                val selected = label.id in selectedKeys
                DeckUiCheckboxRow(
                    title = label.title,
                    selected = selected,
                    enabled = !busy,
                    leading = {
                        Box(
                            modifier = Modifier.size(18.dp).background(
                                color = label.color.toDeckUiColor(
                                    MaterialTheme.colorScheme.primary,
                                ),
                                shape = RoundedCornerShape(5.dp),
                            ),
                        )
                    },
                    onClick = {
                        selectedKeys = if (label.id in selectedKeys) selectedKeys - label.id else selectedKeys + label.id
                    },
                )
            }
            errorMessage?.let { message ->
                item { DeckUiInlineError(message) }
            }
        }
    }
}
