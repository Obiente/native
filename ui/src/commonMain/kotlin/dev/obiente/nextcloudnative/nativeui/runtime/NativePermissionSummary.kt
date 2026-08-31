package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

internal data class NativePermissionSummary(
    val allowed: List<String>,
    val denied: List<String>,
    val fieldIds: Set<String>,
)

internal fun nativePermissionSummary(resource: ResourceSpec, record: NativeRecord): NativePermissionSummary? {
    val known = mapOf(
        "permissionread" to "Read",
        "permissioncreate" to "Create",
        "permissionupdate" to "Edit",
        "permissiondelete" to "Delete",
        "permissionmanage" to "Manage",
    )
    val values = resource.fields.mapNotNull { field ->
        if (field.kind != FieldKind.boolean) return@mapNotNull null
        val label = known[field.id.lowercase().filter(Char::isLetterOrDigit)] ?: return@mapNotNull null
        val allowed = when (record.values[field.id]?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> return@mapNotNull null
        }
        Triple(field.id, label, allowed)
    }
    if (values.size < 2) return null
    return NativePermissionSummary(
        allowed = values.filter { it.third }.map { it.second },
        denied = values.filterNot { it.third }.map { it.second },
        fieldIds = values.mapTo(linkedSetOf()) { it.first },
    )
}

@Composable
internal fun NativePermissionSummary(summary: NativePermissionSummary) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text("Access", style = MaterialTheme.typography.titleMedium)
            if (summary.allowed.isNotEmpty()) {
                Text("Allowed: ${summary.allowed.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
            }
            if (summary.denied.isNotEmpty()) {
                Text(
                    "Not allowed: ${summary.denied.joinToString(", ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
