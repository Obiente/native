package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceField
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceOption
import dev.obiente.nextcloudnative.app.design.NextcloudDetailSheet
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** Header action that opens the conversation settings sheet. */
@Composable
internal fun TalkConversationSettingsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("talk-conversation-settings")) {
        Icon(NextcloudIcons.Settings, contentDescription = "Conversation settings")
    }
}

@Composable
internal fun TalkConversationSettingsSheet(
    token: String,
    title: String,
    execute: suspend (TalkTransportRequest) -> TalkTransportResponse,
    onDismiss: () -> Unit,
) {
    val currentExecute by rememberUpdatedState(execute)
    val scope = rememberCoroutineScope()
    val controller = remember(token) { TalkConversationSettingsController(scope, token) { currentExecute(it) } }
    LaunchedEffect(controller) { controller.refresh() }
    NextcloudDetailSheet(title = title, onDismiss = onDismiss, closeLabel = "Close conversation settings", bodyHeightFraction = 0.85f) {
        TalkConversationSettingsContent(controller)
    }
}

@Composable
internal fun TalkConversationSettingsContent(controller: TalkConversationSettingsController) {
    val state = controller.state
    val settings = state.settings
    var pendingRemoval by remember { mutableStateOf<TalkParticipant?>(null) }
    var pendingGuestAccess by remember { mutableStateOf<Boolean?>(null) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        state.message?.let { message ->
            Text(
                message,
                modifier = Modifier.semantics { liveRegion = if (state.messageIsError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
                color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (settings == null) {
            Text(state.loadFailure ?: "Loading conversation settings...", style = MaterialTheme.typography.bodyMedium)
            if (state.loadFailure != null) OutlinedButton(onClick = controller::refresh) { Text("Try again") }
            return@Column
        }
        val busy = state.applying
        TalkTextSetting(
            label = "Name",
            current = settings.name,
            enabled = settings.allows(TalkConversationChange.Rename("x")) && !busy,
            singleLine = true,
            onSave = { controller.apply(TalkConversationChange.Rename(it)) },
            valid = { it.isNotBlank() && it.length <= 255 },
        )
        if (settings.allows(TalkConversationChange.Describe("")) || settings.description.isNotBlank()) {
            TalkTextSetting(
                label = "Description",
                current = settings.description,
                enabled = settings.allows(TalkConversationChange.Describe("")) && !busy,
                singleLine = false,
                onSave = { controller.apply(TalkConversationChange.Describe(it)) },
                valid = { it.length <= 2_000 },
            )
        }
        NextcloudChoiceField(
            label = "Notifications",
            options = TalkNotificationLevel.entries.map { NextcloudChoiceOption(it.name, it.label) },
            selectedId = settings.notificationLevel?.name,
            onSelected = { id ->
                TalkNotificationLevel.entries.firstOrNull { it.name == id }?.let { controller.apply(TalkConversationChange.Notifications(it)) }
            },
            enabled = settings.allows(TalkConversationChange.Notifications(TalkNotificationLevel.Default)) && !busy,
            fieldKey = "talk-notifications-${settings.token}",
        )
        if (settings.canModerate) {
            HorizontalDivider()
            Text("Moderation", style = MaterialTheme.typography.titleSmall)
            TalkSwitchSetting("Read-only", "Only moderators can write while this is on", settings.readOnly,
                settings.allows(TalkConversationChange.ReadOnly(true)) && !busy) {
                controller.apply(TalkConversationChange.ReadOnly(it))
            }
            TalkSwitchSetting("Lobby", "Only moderators can join until the lobby is turned off", settings.lobbyEnabled,
                settings.allows(TalkConversationChange.Lobby(true)) && !busy) {
                controller.apply(TalkConversationChange.Lobby(it))
            }
            TalkSwitchSetting("Guest access", "Anyone with the link can join as a guest", settings.guestsAllowed,
                settings.canChangeGuestAccess && !busy) { pendingGuestAccess = it }
        }
        state.participants?.let { participants ->
            HorizontalDivider()
            Text("Participants (${participants.size})", style = MaterialTheme.typography.titleSmall)
            participants.take(MAX_SHOWN_PARTICIPANTS).forEach { participant ->
                TalkParticipantRow(
                    participant = participant,
                    canManage = settings.allows(TalkConversationChange.RemoveParticipant(participant.attendeeId, "")) && !participant.isOwner && !busy,
                    onToggleModerator = { controller.apply(TalkConversationChange.Moderator(participant.attendeeId, !participant.isModerator)) },
                    onRemove = { pendingRemoval = participant },
                )
            }
            if (participants.size > MAX_SHOWN_PARTICIPANTS) {
                Text("${participants.size - MAX_SHOWN_PARTICIPANTS} more participants are not shown.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    pendingRemoval?.let { participant ->
        TalkConfirmDialog(
            title = "Remove ${participant.displayName}?",
            text = "They leave the conversation and lose access to its messages until someone adds them again.",
            confirmLabel = "Remove",
            onConfirm = { controller.apply(TalkConversationChange.RemoveParticipant(participant.attendeeId, participant.displayName)) },
            onDismiss = { pendingRemoval = null },
        )
    }
    pendingGuestAccess?.let { allowed ->
        TalkConfirmDialog(
            title = if (allowed) "Allow guests?" else "Turn off guest access?",
            text = if (allowed) "Anyone who has the conversation link can join and read the conversation."
            else "Guests who joined with the link are removed from the conversation.",
            confirmLabel = if (allowed) "Allow guests" else "Turn off",
            destructive = !allowed,
            onConfirm = { controller.apply(TalkConversationChange.GuestAccess(allowed)) },
            onDismiss = { pendingGuestAccess = null },
        )
    }
}

@Composable
private fun TalkTextSetting(
    label: String,
    current: String,
    enabled: Boolean,
    singleLine: Boolean,
    onSave: (String) -> Unit,
    valid: (String) -> Boolean,
) {
    var draft by remember(current) { mutableStateOf(current) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(2_000) },
            label = { Text(label) },
            enabled = enabled,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            modifier = Modifier.fillMaxWidth(),
        )
        if (enabled && draft.trim() != current) {
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Button(onClick = { onSave(draft.trim()) }, enabled = valid(draft.trim())) { Text("Save ${label.lowercase()}") }
                TextButton(onClick = { draft = current }) { Text("Undo") }
            }
        }
    }
}

@Composable
private fun TalkSwitchSetting(title: String, description: String, checked: Boolean, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChanged),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun TalkParticipantRow(participant: TalkParticipant, canManage: Boolean, onToggleModerator: () -> Unit, onRemove: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(participant.displayName, style = MaterialTheme.typography.bodyMedium)
            val role = when {
                participant.isOwner -> "Owner"
                participant.isModerator -> "Moderator"
                participant.actorType == "guests" -> "Guest"
                else -> null
            }
            role?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
        if (canManage) {
            NextcloudCardOverflow(
                itemLabel = participant.displayName,
                expanded = menuOpen,
                onExpandedChange = { menuOpen = it },
                actions = buildList {
                    if (participant.actorType == "users") {
                        add(NextcloudCardAction(if (participant.isModerator) "Remove moderator role" else "Make moderator", onClick = onToggleModerator))
                    }
                    add(NextcloudCardAction("Remove from conversation", destructive = true, onClick = onRemove))
                },
            )
        }
    }
}

@Composable
private fun TalkConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(
                onClick = { onDismiss(); onConfirm() },
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors(),
            ) { Text(confirmLabel) }
        },
    )
}

private const val MAX_SHOWN_PARTICIPANTS = 200
