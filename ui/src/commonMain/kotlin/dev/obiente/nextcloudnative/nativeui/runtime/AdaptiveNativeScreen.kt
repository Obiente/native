package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec

@Composable
fun AdaptiveNativeScreen(
    schema: NativeAppSchema,
    view: ViewSpec,
    state: NativeScreenState,
    onAction: (NativeActionRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resource = schema.resource(view.resourceId)
    val action = schema.action(view.sourceActionId)

    Surface(modifier = modifier.fillMaxSize()) {
        when (state) {
            NativeScreenState.Loading -> LoadingState()
            is NativeScreenState.Error -> ErrorState(state)
            is NativeScreenState.Ready -> when (view.component) {
                NativeComponent.dashboard,
                NativeComponent.fileBrowser,
                NativeComponent.collectionList,
                NativeComponent.timeline,
                NativeComponent.calendar,
                NativeComponent.board,
                NativeComponent.mailbox,
                NativeComponent.contactList,
                NativeComponent.taskList,
                NativeComponent.dataTable,
                NativeComponent.mediaLibrary,
                NativeComponent.recipeList,
                NativeComponent.conversationList,
                -> RecordList(resource, state.records)

                NativeComponent.mediaGrid -> MediaGrid(resource, state.records)
                NativeComponent.chatThread -> ChatThread(resource, state.records)
                NativeComponent.detail,
                NativeComponent.documentEditor,
                -> RecordDetail(resource, state.records.firstOrNull())
                NativeComponent.form -> NativeForm(resource, action, onAction)
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(state: NativeScreenState.Error) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(state.message, color = MaterialTheme.colorScheme.error)
        state.retry?.let { retry -> Button(onClick = retry) { Text(state.retryLabel) } }
    }
}

@Composable
private fun RecordList(resource: ResourceSpec?, records: List<NativeRecord>) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(records, key = NativeRecord::id) { record ->
            val title = preferredValue(resource, record, listOf("name", "title", "displayName", "description"))
                ?: record.id
            val supporting = secondaryValue(resource, record, title)
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = { supporting?.let { value -> Text(value) } },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun MediaGrid(resource: ResourceSpec?, records: List<NativeRecord>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(records, key = NativeRecord::id) { record ->
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        preferredValue(resource, record, listOf("name", "title")) ?: record.id,
                        fontWeight = FontWeight.SemiBold,
                    )
                    preferredValue(resource, record, listOf("takenAt", "date", "mimetype"))?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatThread(resource: ResourceSpec?, records: List<NativeRecord>) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(records, key = NativeRecord::id) { record ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    preferredValue(resource, record, listOf("actorDisplayName", "author", "user"))?.let {
                        Text(it, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        preferredValue(resource, record, listOf("message", "text", "content"))
                            ?: record.values.values.firstOrNull().orEmpty(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordDetail(resource: ResourceSpec?, record: NativeRecord?) {
    if (record == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing selected")
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        resource?.fields?.forEach { field ->
            record.values[field.id]?.let { value ->
                Column {
                    Text(field.label, style = MaterialTheme.typography.labelMedium)
                    Text(value)
                }
            }
        }
    }
}

@Composable
private fun NativeForm(
    resource: ResourceSpec?,
    action: ActionSpec?,
    onAction: (NativeActionRequest) -> Unit,
) {
    val values = remember { mutableStateMapOf<String, String>() }
    var confirmationRequested by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        resource?.fields
            ?.filterNot { field -> field.readOnly || field.kind == FieldKind.objectValue }
            ?.forEach { field ->
                OutlinedTextField(
                    value = values[field.id].orEmpty(),
                    onValueChange = { value -> values[field.id] = value },
                    label = { Text(field.label) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = if (field.kind == FieldKind.longText) 3 else 1,
                )
            }

        action?.let { resolvedAction ->
            if (confirmationRequested) {
                Text("Confirm ${resolvedAction.label.lowercase()} before changing server data.")
            }
            Button(
                onClick = {
                    if (resolvedAction.requiresConfirmation && !confirmationRequested) {
                        confirmationRequested = true
                    } else {
                        onAction(
                            NativeActionRequest.Submit(
                                action = resolvedAction,
                                values = values.toMap(),
                                confirmed = confirmationRequested,
                            ),
                        )
                    }
                },
            ) {
                Text(if (confirmationRequested) "Confirm" else resolvedAction.label)
            }
        }
    }
}

private fun preferredValue(
    resource: ResourceSpec?,
    record: NativeRecord,
    preferredIds: List<String>,
): String? {
    for (id in preferredIds) {
        record.values[id]?.takeIf(String::isNotBlank)?.let { return it }
    }
    return resource?.fields
        ?.firstNotNullOfOrNull { field -> record.values[field.id]?.takeIf(String::isNotBlank) }
}

private fun secondaryValue(
    resource: ResourceSpec?,
    record: NativeRecord,
    title: String,
): String? = resource?.fields
    ?.asSequence()
    ?.mapNotNull { field -> record.values[field.id] }
    ?.firstOrNull { value -> value.isNotBlank() && value != title }
