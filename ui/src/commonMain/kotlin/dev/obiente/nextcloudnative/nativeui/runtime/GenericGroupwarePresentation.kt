package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

@Composable
internal fun GenericGroupwareCollection(
    rows: List<Pair<NativeRecord, NativeGroupwarePresentation>>,
    onSelectRecord: ((NativeRecord) -> Unit)?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Medium,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.XXLarge,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        items(rows, key = { (record, _) -> record.id }) { (record, presentation) ->
            val interaction = onSelectRecord
                ?.let { callback -> Modifier.clickable { callback(record) } }
                ?: Modifier
            Card(
                modifier = interaction.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = NextcloudTheme.colors.appIconContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        if (presentation.kind == NativeGroupwareItemKind.Contact) {
                            Text(
                                presentation.title.nativeContactInitials(),
                                modifier = Modifier.padding(NextcloudSpacing.Medium),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = NextcloudTheme.colors.appIcon,
                            )
                        } else {
                            Icon(
                                NextcloudIcons.Calendar,
                                contentDescription = null,
                                modifier = Modifier.padding(NextcloudSpacing.Medium).size(24.dp),
                                tint = NextcloudTheme.colors.appIcon,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                    ) {
                        Text(
                            presentation.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        presentation.subtitle?.let { subtitle ->
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (onSelectRecord != null) {
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open ${presentation.title}",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun String.nativeContactInitials(): String = trim()
    .split(' ')
    .filter(String::isNotBlank)
    .take(2)
    .mapNotNull(String::firstOrNull)
    .joinToString("")
    .uppercase()
    .takeIf(String::isNotBlank)
    ?: "?"

@Composable
internal fun GenericGroupwareDetail(
    presentation: NativeGroupwarePresentation,
    onOpenLink: ((String) -> Unit)?,
) {
    val emailUri = remember(presentation.primaryEmail) {
        nativeContactEmailUri(presentation.primaryEmail)
    }
    val phoneUri = remember(presentation.primaryPhone) {
        nativeContactPhoneUri(presentation.primaryPhone)
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = NextcloudTheme.colors.appIconContainer,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                if (presentation.kind == NativeGroupwareItemKind.Contact) {
                    Text(
                        presentation.title.nativeContactInitials(),
                        modifier = Modifier.padding(NextcloudSpacing.Large),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = NextcloudTheme.colors.appIcon,
                    )
                } else {
                    Icon(
                        NextcloudIcons.Calendar,
                        contentDescription = null,
                        modifier = Modifier.padding(NextcloudSpacing.Large).size(32.dp),
                        tint = NextcloudTheme.colors.appIcon,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    presentation.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                listOfNotNull(
                    presentation.organization,
                    presentation.status?.takeIf { presentation.kind == NativeGroupwareItemKind.Event },
                ).distinct().joinToString(" · ").takeIf(String::isNotBlank)?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (
            presentation.kind == NativeGroupwareItemKind.Contact &&
            onOpenLink != null &&
            (emailUri != null || phoneUri != null)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                emailUri?.let { uri ->
                    Button(onClick = { onOpenLink(uri) }) {
                        Icon(NextcloudIcons.app("mail"), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Email", modifier = Modifier.padding(start = NextcloudSpacing.Small))
                    }
                }
                phoneUri?.let { uri ->
                    OutlinedButton(onClick = { onOpenLink(uri) }) {
                        Text("Call")
                    }
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column {
                val rows = when (presentation.kind) {
                    NativeGroupwareItemKind.Contact -> listOfNotNull(
                        presentation.primaryEmail?.let { "Email" to it },
                        presentation.primaryPhone?.let { "Phone" to it },
                        presentation.organization?.let { "Organization" to it },
                        presentation.address?.let { "Address" to it },
                        presentation.birthday?.let { "Birthday" to it.compactSemanticDateTime() },
                    )
                    NativeGroupwareItemKind.Event -> listOfNotNull(
                        presentation.start?.let {
                            (if (presentation.allDay) "Date" else "Starts") to it.compactSemanticDateTime()
                        },
                        presentation.end?.let { "Ends" to it.compactSemanticDateTime() },
                        presentation.location?.let { "Location" to it },
                        presentation.organizer?.let { "Organizer" to it },
                        presentation.attendeeCount?.let { "Attendees" to it.toString() },
                        presentation.status?.let { "Status" to it },
                        presentation.recurrenceRule?.let { "Repeats" to it },
                    )
                    NativeGroupwareItemKind.Task -> emptyList()
                }
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Text(value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        presentation.description?.takeIf(String::isNotBlank)?.let { description ->
            Text(
                if (presentation.kind == NativeGroupwareItemKind.Contact) "Notes" else "Description",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                SelectionContainer {
                    Text(
                        description,
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
