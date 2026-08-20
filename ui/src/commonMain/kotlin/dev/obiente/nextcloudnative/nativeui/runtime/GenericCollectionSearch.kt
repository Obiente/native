package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

@Composable
internal fun GenericCollectionSearchField(
    resourceName: String,
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier,
        singleLine = true,
        leadingIcon = {
            Icon(
                NextcloudIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        placeholder = {
            Text(
                "Search ${resourceName.ifBlank { "items" }.lowercase()}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

internal fun nativeRecordMatchesCollectionQuery(
    resource: ResourceSpec,
    record: NativeRecord,
    query: String,
): Boolean {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (terms.isEmpty()) return true
    val presentation = nativeRecordPresentation(resource, record)
    val searchableText = buildList {
        add(presentation.title)
        presentation.subtitle?.let(::add)
        addAll(record.displayValues.values)
        resource.fields
            .filterNot { field -> field.id.isTechnicalCollectionSearchField() }
            .mapNotNullTo(this) { field -> record.values[field.id] }
    }.joinToString(" ").lowercase()
    return terms.all { term -> term.lowercase() in searchableText }
}

private fun String.isTechnicalCollectionSearchField(): Boolean {
    val words = replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotBlank)
    return words.any { word ->
        word in setOf("id", "uuid", "etag", "order", "position", "sort", "token")
    }
}

@Composable
internal fun GenericRendererNoSearchResults(
    query: String,
    onClear: () -> Unit,
) {
    GenericCenteredState {
        GenericStateIcon(NextcloudIcons.Search)
        Text("No matching items", style = MaterialTheme.typography.titleLarge)
        Text(
            "Nothing matches \"$query\".",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onClear) { Text("Clear search") }
    }
}

@Composable
internal fun GenericRendererSearchPagingState(
    query: String,
    loading: Boolean,
    error: String?,
    onRetry: (() -> Unit)?,
    onClear: () -> Unit,
) {
    GenericCenteredState {
        GenericStateIcon(NextcloudIcons.Search)
        Text(
            if (loading) "Searching more items" else "Could not finish searching",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            error ?: "Looking through the rest of the collection for \"$query\".",
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                onRetry?.let { retry -> TextButton(onClick = retry) { Text("Try again") } }
                TextButton(onClick = onClear) { Text("Clear search") }
            }
        }
    }
}
