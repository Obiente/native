package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun FileShareRecipientPicker(
    session: NextcloudSession,
    services: NextcloudPlatformServices,
    target: FileShareTarget,
    selectedRecipient: String,
    enabled: Boolean,
    onSelected: (FileShareRecipient?) -> Unit,
) {
    require(target != FileShareTarget.PublicLink)
    var query by remember(target) { mutableStateOf("") }
    var results by remember(target) { mutableStateOf<List<FileShareRecipient>>(emptyList()) }
    var loading by remember(target) { mutableStateOf(false) }
    var searchError by remember(target) { mutableStateOf<String?>(null) }

    LaunchedEffect(query, target, session, selectedRecipient) {
        val normalized = query.trim()
        if (selectedRecipient.isNotBlank()) {
            results = emptyList()
            loading = false
            searchError = null
            return@LaunchedEffect
        }
        if (normalized.length < MIN_FILE_SHARE_RECIPIENT_QUERY_LENGTH) {
            results = emptyList()
            loading = false
            searchError = null
            return@LaunchedEffect
        }
        delay(300)
        loading = true
        searchError = null
        try {
            results = services.searchFileShareRecipients(session, normalized, target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            results = emptyList()
            searchError = failure.message ?: "Could not search Nextcloud recipients."
        }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        OutlinedTextField(
            value = query,
            enabled = enabled,
            onValueChange = {
                query = it
                results = emptyList()
                searchError = null
                onSelected(null)
            },
            label = { Text(if (target == FileShareTarget.Group) "Search groups" else "Search people") },
            placeholder = { Text("Enter at least two characters") },
            trailingIcon = {
                if (loading) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            },
            supportingText = {
                when {
                    selectedRecipient.isNotBlank() -> Text("Selected: $selectedRecipient")
                    query.trim().length < MIN_FILE_SHARE_RECIPIENT_QUERY_LENGTH ->
                        Text("Search your Nextcloud server and select a result.")
                    !loading && searchError == null && results.isEmpty() -> Text("No matching recipients")
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        results.take(8).forEach { recipient ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(NextcloudRadii.Medium),
                    )
                    .clickable(enabled = enabled) {
                        query = recipient.displayName
                        results = emptyList()
                        searchError = null
                        onSelected(recipient)
                    }
                    .padding(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        recipient.displayName,
                        fontWeight = if (recipient.exact) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (recipient.id != recipient.displayName) {
                        Text(
                            recipient.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    if (target == FileShareTarget.Group) "Group" else "User",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        searchError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
