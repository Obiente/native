package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

/**
 * Everything a mailbox surface needs to offer composing: the contract-described send operation,
 * the executor that runs it, and the retained draft state. Null means the contract describes no
 * usable send operation, so no compose, reply, or forward control is shown.
 */
internal class NativeMailComposerHost(
    val capability: NativeMailSendCapability,
    val state: NativeMailComposerState,
    val executor: NativeActionExecutor,
    val onSent: () -> Unit,
) {
    /** Sends in the state owner's scope; leaving the dialog or the screen does not cancel it. */
    fun send(confirmedResend: Boolean) = state.launchSend(capability, executor, confirmedResend, onSent)

    fun checkStatus() = state.launchStatusCheck(capability, executor, onSent)

    fun startNew() {
        state.open(NativeMailComposeDraft(mode = NativeMailComposeMode.New, senderId = capability.senders.firstOrNull()?.key))
    }

    fun respond(mode: NativeMailComposeMode, resource: ResourceSpec, record: NativeRecord): Boolean {
        val original = nativeMailOriginal(resource, record) ?: return false
        val own = capability.senders.mapNotNullTo(mutableSetOf(), NativeMailSender::email)
        val sender = capability.senders.firstOrNull { sender ->
            sender.email != null && (original.to + original.cc).any { it.email.equals(sender.email, ignoreCase = true) }
        } ?: capability.senders.firstOrNull()
        state.open(nativeMailResponseDraft(mode, original, sender?.key, own))
        return true
    }
}

/**
 * The composer for [schema]'s mailbox. With a [retention] from the account owner, the draft, its
 * device copy, and any in-flight send outlive this composition. Without one (previews and hosts
 * without account ownership) the draft lives only as long as this composition.
 */
@Composable
internal fun rememberNativeMailComposerHost(
    schema: NativeAppSchema,
    context: NativeDatasetContext,
    executor: NativeActionExecutor,
    retention: NativeMailComposerRetention?,
    onSent: (ActionSpec) -> Unit,
): NativeMailComposerHost? {
    val fallbackScope = rememberCoroutineScope()
    val state = remember(retention, schema.app.id) {
        retention?.stateFor(schema.app.id) ?: NativeMailComposerState(fallbackScope)
    }
    val currentOnSent by rememberUpdatedState(onSent)
    val capability = remember(schema, context.relatedRecords, context.parentRecord, context.parentResourceId) {
        nativeMailSendCapability(schema, context)
    } ?: return null
    return remember(capability, state, executor) {
        NativeMailComposerHost(capability, state, executor) { currentOnSent(capability.action) }
    }
}

@Composable
internal fun NativeMailComposeButton(host: NativeMailComposerHost, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = host::startNew,
        modifier = modifier,
        icon = { Icon(NextcloudIcons.Edit, contentDescription = null) },
        text = { Text("Compose") },
    )
}

/** Reply, reply all, and forward for one opened message. */
@Composable
internal fun NativeMailResponseActions(
    host: NativeMailComposerHost,
    resource: ResourceSpec,
    record: NativeRecord,
) {
    val original = remember(resource, record) { nativeMailOriginal(resource, record) } ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        if (original.from.isNotEmpty() || original.replyTo.isNotEmpty()) {
            OutlinedButton(onClick = { host.respond(NativeMailComposeMode.Reply, resource, record) }) { Text("Reply") }
            if (original.to.size + original.cc.size > 1) {
                OutlinedButton(onClick = { host.respond(NativeMailComposeMode.ReplyAll, resource, record) }) {
                    Text("Reply all")
                }
            }
        }
        OutlinedButton(onClick = { host.respond(NativeMailComposeMode.Forward, resource, record) }) { Text("Forward") }
    }
}

/** Shows the composer while a draft is open and the last send notice otherwise. */
@Composable
internal fun NativeMailComposerOverlay(host: NativeMailComposerHost) {
    val state = host.state
    val draft = state.draft
    if (draft != null) {
        NativeMailComposerDialog(host, draft)
        return
    }
    val notice = state.notice ?: return
    Box(Modifier.fillMaxSize().padding(NextcloudSpacing.Large), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(Modifier.padding(start = NextcloudSpacing.Large), verticalAlignment = Alignment.CenterVertically) {
                Text(notice, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = state::dismissNotice) {
                    Text("Dismiss", color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
        }
    }
}

@Composable
private fun NativeMailComposerDialog(host: NativeMailComposerHost, draft: NativeMailComposeDraft) {
    val state = host.state
    val capability = host.capability
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmResend by remember { mutableStateOf(false) }
    var showCopies by remember(draft.mode) { mutableStateOf(draft.cc.isNotBlank() || draft.bcc.isNotBlank()) }
    val problems = capability.problems(draft)
    val editable = !state.busy
    fun requestClose() {
        if (state.busy) return
        if (draft.hasContent || state.phase is NativeMailSendPhase.Unknown) confirmDiscard = true else state.discard()
    }
    Dialog(
        onDismissRequest = ::requestClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            val compact = maxWidth < 680.dp
            Surface(
                modifier = if (compact) Modifier.fillMaxSize() else Modifier.widthIn(max = 760.dp).heightIn(max = 760.dp).padding(NextcloudSpacing.Large),
                shape = if (compact) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.large,
                tonalElevation = 3.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Small, vertical = NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = ::requestClose, enabled = editable) {
                            Icon(NextcloudIcons.Close, contentDescription = "Close composer")
                        }
                        Text(
                            draft.mode.title(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.busy) {
                            CircularProgressIndicator(Modifier.size(24.dp).padding(end = NextcloudSpacing.Small), strokeWidth = 2.dp)
                        }
                        Button(
                            onClick = { if (state.resendNeedsConfirmation) confirmResend = true else host.send(confirmedResend = false) },
                            enabled = editable && state.sendAvailable && problems.isEmpty(),
                        ) {
                            Icon(NextcloudIcons.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Send", modifier = Modifier.padding(start = NextcloudSpacing.Small))
                        }
                    }
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        NativeMailSendPhaseBanner(state.phase, onCheckStatus = host::checkStatus.takeIf { state.statusCheckAvailable })
                        state.storageProblem?.let { problem ->
                            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (capability.senders.size > 1 || capability.senderFor(draft) == null) {
                            NativeMailSenderPicker(capability, draft, enabled = editable) { id ->
                                state.update { it.copy(senderId = id) }
                            }
                        }
                        OutlinedTextField(
                            value = draft.to,
                            onValueChange = { value -> state.update { it.copy(to = value) } },
                            label = { Text("To") },
                            enabled = editable,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (capability.supportsCc || capability.supportsBcc) {
                            if (showCopies) {
                                if (capability.supportsCc) {
                                    OutlinedTextField(
                                        value = draft.cc,
                                        onValueChange = { value -> state.update { it.copy(cc = value) } },
                                        label = { Text("Cc") },
                                        enabled = editable,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                if (capability.supportsBcc) {
                                    OutlinedTextField(
                                        value = draft.bcc,
                                        onValueChange = { value -> state.update { it.copy(bcc = value) } },
                                        label = { Text("Bcc") },
                                        enabled = editable,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                TextButton(onClick = { showCopies = true }) { Text("Add Cc or Bcc") }
                            }
                        }
                        if (capability.subjectField != null) {
                            OutlinedTextField(
                                value = draft.subject,
                                onValueChange = { value -> state.update { it.copy(subject = value) } },
                                label = { Text("Subject") },
                                singleLine = true,
                                enabled = editable,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        OutlinedTextField(
                            value = draft.body,
                            onValueChange = { value -> state.update { it.copy(body = value) } },
                            label = { Text("Message") },
                            minLines = 8,
                            enabled = editable,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        draft.quote?.let { quote ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = draft.includeQuote,
                                    onCheckedChange = { checked -> state.update { it.copy(includeQuote = checked) } },
                                    enabled = editable,
                                )
                                Text(if (quote.forward) "Include the forwarded message" else "Include the original message")
                            }
                            if (draft.includeQuote) {
                                Text(
                                    quote.header + "\n\n" + quote.text.take(QUOTE_PREVIEW_CHARACTERS),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 12,
                                )
                            }
                        }
                        problems.firstOrNull()?.takeIf { draft.hasContent }?.let { problem ->
                            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this message?") },
            text = {
                Text(
                    if (state.phase is NativeMailSendPhase.Unknown) {
                        "The last attempt may already have been sent. Discarding removes this draft from this device."
                    } else {
                        "This removes the draft from this device."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; state.discard() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (confirmResend) {
        AlertDialog(
            onDismissRequest = { confirmResend = false },
            title = { Text("Send again?") },
            text = { Text("The first attempt may already have been delivered. Sending again can deliver it twice.") },
            confirmButton = { TextButton(onClick = { confirmResend = false; host.send(confirmedResend = true) }) { Text("Send again") } },
            dismissButton = { TextButton(onClick = { confirmResend = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NativeMailSendPhaseBanner(phase: NativeMailSendPhase, onCheckStatus: (() -> Unit)?) {
    val message = when (phase) {
        is NativeMailSendPhase.Rejected -> phase.message
        is NativeMailSendPhase.Unknown -> listOfNotNull(phase.recovery.description(), phase.record?.evidence()).joinToString(" ")
        NativeMailSendPhase.Sending -> "Sending..."
        NativeMailSendPhase.Editing -> null
    } ?: return
    Surface(
        color = if (phase is NativeMailSendPhase.Sending) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(NextcloudSpacing.Medium), verticalAlignment = Alignment.CenterVertically) {
            if (phase !is NativeMailSendPhase.Sending) {
                Icon(NextcloudIcons.Error, contentDescription = null, modifier = Modifier.padding(end = NextcloudSpacing.Small))
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            onCheckStatus?.let { check -> TextButton(onClick = check) { Text("Check status") } }
        }
    }
}

@Composable
private fun NativeMailSenderPicker(
    capability: NativeMailSendCapability,
    draft: NativeMailComposeDraft,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = capability.senderFor(draft)
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("From: ${selected?.label ?: "Choose an account"}", modifier = Modifier.weight(1f))
            Icon(NextcloudIcons.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            capability.senders.forEach { sender ->
                DropdownMenuItem(
                    text = { Text(sender.label) },
                    onClick = {
                        expanded = false
                        onSelect(sender.key)
                    },
                )
            }
        }
    }
}

private fun NativeMailComposeMode.title(): String = when (this) {
    NativeMailComposeMode.New -> "New message"
    NativeMailComposeMode.Reply -> "Reply"
    NativeMailComposeMode.ReplyAll -> "Reply all"
    NativeMailComposeMode.Forward -> "Forward"
}

private const val QUOTE_PREVIEW_CHARACTERS = 2_000
