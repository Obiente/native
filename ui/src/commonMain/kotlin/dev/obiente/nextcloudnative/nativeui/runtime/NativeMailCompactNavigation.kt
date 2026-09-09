package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

internal fun nativeMailNavigationDestinations(plan: NativeMailWorkspacePlan): List<NativeMailWorkspaceItem> =
    (plan.accounts + plan.folders).distinctBy { it.nativeMailWorkspaceRecordKey() }

internal fun nativeMailNavigationAccountLabel(
    plan: NativeMailWorkspacePlan,
    item: NativeMailWorkspaceItem,
): String? {
    val accountIds = item.record.mailWorkspaceAccountIds(item.presentation.kind)
    return plan.accounts.filter { account ->
        account.record.mailWorkspaceAccountIds(account.presentation.kind).intersect(accountIds).isNotEmpty()
    }.singleOrNull()?.presentation?.title
}

@Composable
internal fun NativeMailCompactNavigation(
    plan: NativeMailWorkspacePlan,
    onSelectRecord: ((NativeRecord) -> Unit)?,
) {
    val destinations = nativeMailNavigationDestinations(plan)
    if (destinations.isEmpty()) return
    var expanded by remember(destinations.map { it.nativeMailWorkspaceRecordKey() }) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                plan.selectedContainer?.presentation?.title ?: "Mail",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            plan.selectedContainer?.let { nativeMailNavigationAccountLabel(plan, it) }?.takeUnless {
                it == plan.selectedContainer.presentation.title
            }?.let { account ->
                Text(
                    account,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            TextButton(onClick = { expanded = true }, enabled = onSelectRecord != null) {
                Text("Mailboxes")
                Icon(NextcloudIcons.ExpandMore, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                destinations.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(item.presentation.title)
                                if (item.presentation.kind == NativeMailboxItemKind.Account) {
                                    Text("Account", style = MaterialTheme.typography.labelSmall)
                                } else {
                                    nativeMailNavigationAccountLabel(plan, item)?.let { account ->
                                        Text(account, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(
                                if (item.presentation.kind == NativeMailboxItemKind.Account) NextcloudIcons.app("mail") else NextcloudIcons.Folder,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelectRecord?.invoke(item.record)
                        },
                    )
                }
            }
        }
    }
}
