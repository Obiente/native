package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceField
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceOption
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun rememberAccountProfileController(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
): AccountProfileController {
    val transport = LocalAccountPasswordConfirmedTransport.current
    val currentServices by rememberUpdatedState(services)
    val scope = rememberCoroutineScope()
    val controller = remember(session.accountId, transport) {
        AccountProfileController(scope, session, { request -> currentServices.executeNextcloudApi(session, request) }, transport)
    }
    LaunchedEffect(controller) { controller.refresh() }
    return controller
}

/** Personal info: profile fields, visibility, and account facts, inside the Settings workspace. */
@Composable
internal fun PersonalInfoSettingsSection(
    controller: AccountProfileController,
    onOpenStatus: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state = controller.state
    val profile = state.profile
    val draftValues = remember(profile?.userId, state.revision) { mutableStateMapOf<AccountProfileField, String>() }
    val draftScopes = remember(profile?.userId, state.revision) { mutableStateMapOf<AccountProfileField, AccountPropertyScope>() }
    var confirming by remember { mutableStateOf(false) }
    val edits = profile?.let { accountProfileEdits(it, draftValues, draftScopes) }.orEmpty()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
        onOpenStatus?.let { openStatus ->
            SettingsActionCard(
                title = "Online status",
                description = "Set your availability, status message, and when it clears",
                icon = NextcloudIcons.Clock,
                onClick = openStatus,
            )
        }
        when {
            profile == null && state.loading -> Text("Loading your profile...", style = MaterialTheme.typography.bodyMedium)
            profile == null -> state.loadFailure?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = controller::refresh) { Text("Try again") }
            }
            else -> {
                AccountFactsCard(profile)
                if (!controller.canEdit) {
                    Text(
                        ConfirmedWriteRejection.TransportMissing.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                profile.values.forEach { value ->
                    ProfileFieldEditor(
                        value = value,
                        draft = draftValues[value.field] ?: value.value,
                        scope = draftScopes[value.field] ?: value.scope,
                        saved = value.field in state.savedFields,
                        enabled = controller.canEdit && !state.saving,
                        onDraftChanged = { draftValues[value.field] = it.take(value.field.maximumLength) },
                        onScopeChanged = { draftScopes[value.field] = it },
                    )
                }
                if (profile.additionalEmails.isNotEmpty()) {
                    ProfileFactsGroup(
                        "Additional email addresses",
                        profile.additionalEmails.map { email -> email.address to (email.scope?.label ?: "") },
                    )
                }
                state.saveFailure?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { confirming = true },
                        enabled = controller.canEdit && edits.isNotEmpty() && !state.saving,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("personal-info-save"),
                    ) { Text(if (state.saving) "Saving..." else "Save changes") }
                    TextButton(
                        onClick = { draftValues.clear(); draftScopes.clear() },
                        enabled = edits.isNotEmpty() && !state.saving,
                    ) { Text("Discard") }
                    if (state.loading) Text("Refreshing...", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (confirming && profile != null) {
        AccountPasswordConfirmationDialog(
            title = "Save ${edits.size} profile change(s)?",
            description = edits.joinToString("\n") { edit -> "- " + edit.describe() },
            confirmLabel = "Save",
            busy = state.saving,
            onConfirm = { secret -> controller.save(edits, secret) { confirming = false } },
            onDismiss = { confirming = false },
        )
    }
    DisposableEffect(Unit) { onDispose { confirming = false } }
}

@Composable
private fun AccountFactsCard(profile: AccountProfile) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(profile.value(AccountProfileField.DisplayName)?.value?.ifBlank { null } ?: profile.userId,
                style = MaterialTheme.typography.titleMedium)
            Text("Account ID: ${profile.userId}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            profile.quota?.let { quota -> QuotaSummary(quota) }
            listOfNotNull(
                profile.language?.let { "Language" to it },
                profile.locale?.let { "Locale" to it },
                profile.timezone?.let { "Time zone" to it },
                profile.profileEnabled?.let { "Public profile" to if (it) "Enabled" else "Disabled" },
            ).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (profile.groups.isNotEmpty()) {
                Text("Groups", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    profile.groups.forEach { group ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                            Text(group, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotaSummary(quota: AccountQuota) {
    val used = quota.usedBytes ?: return
    val total = quota.totalBytes?.takeIf { it > 0L }
    Text(
        if (total != null) "${formatByteSize(used)} of ${formatByteSize(total)} used" else "${formatByteSize(used)} used",
        style = MaterialTheme.typography.bodySmall,
    )
    quota.relativePercent?.let { percent ->
        LinearProgressIndicator(progress = { (percent / 100.0).toFloat() }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ProfileFieldEditor(
    value: AccountProfileValue,
    draft: String,
    scope: AccountPropertyScope?,
    saved: Boolean,
    enabled: Boolean,
    onDraftChanged: (String) -> Unit,
    onScopeChanged: (AccountPropertyScope) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChanged,
            modifier = Modifier.fillMaxWidth().testTag("personal-info-field-${value.field.key}"),
            label = { Text(value.field.label) },
            enabled = enabled && value.editable,
            singleLine = !value.field.multiline,
            minLines = if (value.field.multiline) 3 else 1,
            supportingText = when {
                saved -> { { Text("Saved") } }
                !value.editable -> { { Text("Managed by your server") } }
                else -> null
            },
        )
        scope?.let { current ->
            NextcloudChoiceField(
                label = "${value.field.label} visibility",
                options = AccountPropertyScope.entries.map { NextcloudChoiceOption(it.name, it.label, searchTerms = listOf(it.description)) },
                selectedId = current.name,
                onSelected = { id -> AccountPropertyScope.entries.firstOrNull { it.name == id }?.let(onScopeChanged) },
                enabled = enabled,
                modifier = Modifier.widthIn(max = 360.dp),
                fieldKey = "scope-${value.field.key}",
            )
        }
    }
}

@Composable
private fun ProfileFactsGroup(title: String, rows: List<Pair<String, String>>) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

internal fun AccountProfileEdit.describe(): String = when (this) {
    is AccountProfileEdit.Value -> if (value.isBlank()) "Clear ${field.label}" else "Change ${field.label}"
    is AccountProfileEdit.Scope -> "Make ${field.label} ${scope.label.lowercase()}"
    is AccountProfileEdit.ProfileEnabled -> if (enabled) "Enable public profile" else "Disable public profile"
}
