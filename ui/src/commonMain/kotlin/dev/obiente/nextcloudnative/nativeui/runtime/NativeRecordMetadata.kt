package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

@Composable
internal fun NativeRecordMetadata(
    recordId: String,
    fields: List<NativeDetailFieldPresentation>,
    collapsible: Boolean,
    onOpenLink: ((String) -> Unit)?,
) {
    if (fields.isEmpty()) return
    var expanded by remember(recordId, collapsible) { mutableStateOf(!collapsible) }
    if (collapsible) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide details" else "Details")
        }
    } else {
        Text("Details", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column {
                fields.forEachIndexed { index, field ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                field.formatted.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(field.formatted.displayValue, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (field.formatted.safeLink != null && onOpenLink != null) {
                            TextButton(onClick = { onOpenLink(field.formatted.safeLink) }) {
                                Icon(NextcloudIcons.FormatLink, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Open", modifier = Modifier.padding(start = NextcloudSpacing.XSmall))
                            }
                        }
                    }
                }
            }
        }
    }
}
