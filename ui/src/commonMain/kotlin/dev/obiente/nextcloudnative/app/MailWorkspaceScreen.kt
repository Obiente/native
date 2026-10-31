package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestination
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestinationSection
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationHost
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationModel
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionWorkspaceScaffold
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceBreakpoints
import dev.obiente.nextcloudnative.app.design.nextcloudFadeInOnLoad
import dev.obiente.nextcloudnative.app.design.resolveNextcloudCollectionNavigationMode
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Account-lifetime Mail state: the workspace, the Outbox, settings, and the open composer.
 *
 * It is remembered by the account shell rather than the Mail route, so switching apps keeps the
 * folder, the open message, and an undo countdown. Unsent drafts additionally survive process
 * death in the scoped draft store and are offered again by [recoveredDrafts].
 */
internal class MailWorkspaceSession(
    private val scope: CoroutineScope,
    private val transport: MailTransport,
    private val store: MailComposeDraftStore?,
    private val chooseLocalFile: suspend () -> LocalUploadSelectionResult,
    private val releaseLocalFile: (LocalUploadFile) -> Unit,
    discoverInstalledVersion: suspend () -> String?,
) {
    val workspace = MailWorkspaceController(scope, transport, discoverInstalledVersion)
    val outbox = MailOutboxController(scope, transport)
    val settings = MailAccountSettingsController(scope, transport) { accounts ->
        accounts.forEach(workspace::replaceAccount)
    }

    var composer by mutableStateOf<MailComposerController?>(null)
        private set
    var recoveredDrafts by mutableStateOf<List<MailComposeDraft>>(emptyList())
        private set
    var recoveryNotice by mutableStateOf<String?>(null)
        private set

    private var started = false

    fun start() {
        workspace.start()
        if (started) return
        started = true
        val draftStore = store ?: return
        scope.launch {
            try {
                recoveredDrafts = draftStore.list()
            } catch (failure: IllegalStateException) {
                recoveryNotice = failure.message ?: "Saved Mail drafts could not be read."
            }
        }
    }

    fun compose() {
        val account = workspace.state.selectedAccount ?: return
        open(newMailCompose(account))
    }

    fun respond(mode: MailComposeMode, original: MailMessageDetail) {
        val account = workspace.state.accounts?.firstOrNull { it.id == original.accountId } ?: return
        open(if (mode == MailComposeMode.EditDraft) mailEditDraftCompose(original, account) else mailResponseCompose(mode, original, account))
    }

    fun resume(draft: MailComposeDraft) {
        recoveredDrafts = recoveredDrafts.filterNot { it.slotId == draft.slotId }
        open(draft)
    }

    fun discardRecovered(draft: MailComposeDraft) {
        recoveredDrafts = recoveredDrafts.filterNot { it.slotId == draft.slotId }
        scope.launch { runCatchingPreservingCancellation { store?.remove(draft.slotId) } }
    }

    /** Closes the composer. Its draft stays in the store unless it was sent or discarded. */
    fun closeComposer() {
        val current = composer ?: return
        scope.launch {
            current.persistNow()
            // A message the Outbox already holds keeps sending; it is not an unsent draft.
            if (current.state.draft.hasContent && !current.state.finished && current.state.draft.outboxMessageId == null) {
                recoveredDrafts = (recoveredDrafts + current.state.draft).distinctBy(MailComposeDraft::slotId)
            }
        }
        if (current.state.finished) workspace.refresh()
        composer = null
    }

    private fun open(draft: MailComposeDraft) {
        if (!workspace.state.writesAllowed) return
        composer?.let { previous ->
            scope.launch { previous.persistNow() }
            if (previous.state.draft.hasContent && !previous.state.finished) {
                recoveredDrafts = (recoveredDrafts + previous.state.draft).distinctBy(MailComposeDraft::slotId)
            }
        }
        composer = MailComposerController(
            scope = scope,
            transport = transport,
            store = store,
            account = { id -> workspace.state.accounts?.firstOrNull { it.id == id } },
            chooseLocalFile = chooseLocalFile,
            releaseLocalFile = releaseLocalFile,
            nowEpochSeconds = { Clock.System.now().epochSeconds },
            initial = draft,
        )
    }
}

@Composable
internal fun rememberMailWorkspaceSession(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
): MailWorkspaceSession {
    val currentServices by rememberUpdatedState(services)
    val currentSession by rememberUpdatedState(session)
    val parent = rememberCoroutineScope()
    val scope = remember(session.accountId) {
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    return remember(scope) {
        MailWorkspaceSession(
            scope = scope,
            transport = MailTransport(
                execute = { request -> currentServices.executeNextcloudApi(currentSession, request) },
                uploadFile = { request -> currentServices.executeNextcloudMultipartUpload(currentSession, request) },
            ),
            store = mailComposeDraftStore(services, session),
            chooseLocalFile = { currentServices.chooseLocalUploadFile(listOf("*/*"), DEFAULT_LOCAL_UPLOAD_LIMIT_BYTES) },
            releaseLocalFile = { file -> currentServices.releaseLocalUploadFile(file) },
            discoverInstalledVersion = { discoverInstalledAppVersion(currentServices, currentSession, MAIL_APP_ID) },
        )
    }
}

/**
 * Native Mail: folders in the collection navigator, the message list, and a reader or composer.
 *
 * Wide windows keep the list beside the reader or composer. Compact windows show one at a time,
 * and system back closes the composer, then the reader.
 */
@Composable
internal fun MailWorkspaceScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    mail: MailWorkspaceSession,
    onBack: () -> Unit,
) {
    LaunchedEffect(mail) { mail.start() }
    val controller = mail.workspace
    val state = controller.state
    val composer = mail.composer
    val listState = key(state.selectedMailboxId, state.searchQuery) { rememberLazyListState() }
    var folderDialog by remember { mutableStateOf<MailFolderDialog?>(null) }
    PlatformBackHandler(enabled = composer != null, onBack = mail::closeComposer)
    PlatformBackHandler(enabled = composer == null && state.opened != null, onBack = controller::closeMessage)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val destinations = remember(state.mailboxes, state.selectedAccountId, state.accounts) { mailDestinations(state) }
        val selectedId = when (state.view) {
            MailWorkspaceView.Mailbox -> state.selectedMailboxId?.let { "mailbox:$it" }
            MailWorkspaceView.Outbox -> MAIL_OUTBOX_DESTINATION
            MailWorkspaceView.Settings -> MAIL_SETTINGS_DESTINATION
        }?.takeIf { id -> destinations.any { it.id == id } }
        val model = remember(destinations, selectedId) { NextcloudCollectionNavigationModel.create(destinations, selectedId) }
        val host = if (LocalNextcloudWorkspaceCapabilities.current.isDesktop) {
            NextcloudCollectionNavigationHost.Desktop
        } else {
            NextcloudCollectionNavigationHost.AdaptiveAndroid
        }
        val mode = resolveNextcloudCollectionNavigationMode(host, maxWidth.value.toInt(), destinations.size)
        val twoPane = maxWidth.value >= NextcloudWorkspaceBreakpoints.TwoPaneDp
        val detailOpen = composer != null || state.opened != null
        NextcloudCollectionWorkspaceScaffold(
            model = model,
            mode = mode,
            workspaceLabel = "Mail",
            contentTitle = mailContentTitle(state, composer),
            contentSubtitle = state.selectedAccount?.email,
            onBack = when {
                composer != null && !twoPane -> mail::closeComposer
                state.opened != null && !twoPane -> controller::closeMessage
                else -> onBack
            },
            hasHierarchyBack = detailOpen && !twoPane,
            onDestinationSelected = { destination ->
                when {
                    destination.id == MAIL_OUTBOX_DESTINATION -> {
                        controller.showView(MailWorkspaceView.Outbox)
                        mail.outbox.load()
                    }
                    destination.id == MAIL_SETTINGS_DESTINATION -> controller.showView(MailWorkspaceView.Settings)
                    destination.id.startsWith("mailbox:") ->
                        destination.id.removePrefix("mailbox:").toLongOrNull()?.let(controller::selectMailbox)
                }
            },
            destinationIcon = { destination -> mailDestinationIcon(destination, state) },
            headerActions = {
                MailHeaderActions(state, mail, onFolderDialog = { folderDialog = it })
            },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                MailNotices(state, mail)
                when {
                    state.accounts == null && state.accountsFailure != null ->
                        MailFailurePane(state.accountsFailure, onRetry = controller::refresh)
                    state.accounts?.isEmpty() == true -> EmptyMessage(
                        "No mail account is set up for this Nextcloud account yet. Add one in the Mail web settings; it appears here automatically.",
                    )
                    state.accounts == null -> LoadingMessage("Loading mail accounts...")
                    composer != null && !twoPane -> MailComposerPane(composer, mail, services, session, userId)
                    state.view == MailWorkspaceView.Outbox && composer == null -> MailOutboxPane(mail.outbox, state.selectedAccount)
                    state.view == MailWorkspaceView.Settings && composer == null ->
                        MailAccountSettingsPane(state.selectedAccount, mail.settings, state.writesAllowed)
                    twoPane -> Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                            MailMessageListPane(state, controller, listState, onFolderDialog = { folderDialog = it })
                        }
                        VerticalDivider()
                        Box(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                            when {
                                composer != null -> MailComposerPane(composer, mail, services, session, userId)
                                state.opened != null -> MailMessageReader(state.opened, state, controller, mail)
                                else -> EmptyMessage(if (state.messages is MailMessagesState.Loaded) "Select a message to read it here." else "")
                            }
                        }
                    }
                    state.opened != null -> MailMessageReader(state.opened, state, controller, mail)
                    else -> MailMessageListPane(state, controller, listState, onFolderDialog = { folderDialog = it })
                }
            }
        }
    }
    folderDialog?.let { dialog ->
        MailFolderDialogHost(dialog, state, controller, onDismiss = { folderDialog = null })
    }
}

@Composable
private fun MailHeaderActions(
    state: MailWorkspaceUiState,
    mail: MailWorkspaceSession,
    onFolderDialog: (MailFolderDialog) -> Unit,
) {
    val controller = mail.workspace
    var accountMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    val accounts = state.accounts.orEmpty()
    if (accounts.size > 1) {
        Box {
            TextButton(onClick = { accountMenu = true }) {
                Text(state.selectedAccount?.name ?: "Account", maxLines = 1)
            }
            DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text("${account.name} (${account.email})") },
                        onClick = {
                            accountMenu = false
                            controller.selectAccount(account.id)
                        },
                    )
                }
            }
        }
    }
    if (state.writesAllowed && state.selectedAccount != null) {
        IconButton(onClick = mail::compose) {
            Icon(Icons.Outlined.Edit, contentDescription = "New message")
        }
    }
    if (state.refreshing) {
        CircularProgressIndicator(modifier = Modifier.padding(NextcloudSpacing.Medium).size(20.dp), strokeWidth = 2.dp)
    } else {
        IconButton(onClick = controller::refresh) { Icon(NextcloudIcons.Refresh, contentDescription = "Refresh Mail") }
    }
    val mailbox = state.selectedMailbox
    if (state.writesAllowed && state.view == MailWorkspaceView.Mailbox) {
        NextcloudCardOverflow(
            itemLabel = mailbox?.displayName ?: "folders",
            actions = mailFolderActions(state, mailbox, controller, onFolderDialog),
            expanded = folderMenu,
            onExpandedChange = { folderMenu = it },
        )
    }
}

private fun mailFolderActions(
    state: MailWorkspaceUiState,
    mailbox: MailMailbox?,
    controller: MailWorkspaceController,
    onFolderDialog: (MailFolderDialog) -> Unit,
): List<NextcloudCardAction> = buildList {
    val busy = state.mailboxOperationRunning
    add(NextcloudCardAction("New folder", enabled = !busy) { onFolderDialog(MailFolderDialog.Create(parent = null)) })
    if (mailbox == null) return@buildList
    if (mailbox.rights.canCreateChild) {
        add(NextcloudCardAction("New subfolder in ${mailbox.displayName}", enabled = !busy) { onFolderDialog(MailFolderDialog.Create(mailbox)) })
    }
    if (mailbox.unread > 0 && mailbox.rights.canChangeSeen) {
        add(NextcloudCardAction("Mark all as read") { controller.markMailboxRead(mailbox.id) })
    }
    val special = state.role(mailbox) != MailMailboxRole.Other
    if (!special && mailbox.rights.canRename) {
        add(NextcloudCardAction("Rename folder", enabled = !busy) { onFolderDialog(MailFolderDialog.Rename(mailbox)) })
    }
    if (!special && mailbox.rights.canDelete) {
        add(NextcloudCardAction("Delete folder", destructive = true, enabled = !busy) { onFolderDialog(MailFolderDialog.Delete(mailbox)) })
    }
}

@Composable
private fun MailNotices(state: MailWorkspaceUiState, mail: MailWorkspaceSession) {
    state.contract?.writeBlockedReason?.let { reason -> MailNoticeBar(reason) }
    state.notice?.let { notice -> MailNoticeBar(notice.message, "Dismiss", mail.workspace::dismissNotice) }
    mail.recoveryNotice?.let { MailNoticeBar(it) }
    if (mail.composer == null && state.writesAllowed) {
        mail.recoveredDrafts.firstOrNull()?.let { draft ->
            val label = draft.subject.ifBlank { "Untitled message" }
            val more = mail.recoveredDrafts.size - 1
            MailNoticeBar(
                message = "Unsent draft: $label" + if (more > 0) " (and $more more)" else "",
                actionLabel = "Continue",
                onAction = { mail.resume(draft) },
            )
        }
    }
    state.mailboxesFailure?.let { failure -> MailNoticeBar("${failure.title}. Folders could not be loaded.", "Try again", mail.workspace::refresh) }
}

@Composable
internal fun MailNoticeBar(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().nextcloudFadeInOnLoad()) {
        Row(
            modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
internal fun MailFailurePane(failure: MailFailure, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(NextcloudIcons.Error, contentDescription = null, modifier = Modifier.size(36.dp))
        Text(failure.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = NextcloudSpacing.Medium))
        Text(
            failure.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = NextcloudSpacing.Small),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = NextcloudSpacing.Large)) { Text("Try again") }
        }
    }
}

private fun mailContentTitle(state: MailWorkspaceUiState, composer: MailComposerController?): String = when {
    composer != null -> when (composer.state.draft.mode) {
        MailComposeMode.Reply, MailComposeMode.ReplyAll -> "Reply"
        MailComposeMode.Forward -> "Forward"
        MailComposeMode.EditDraft -> "Edit draft"
        MailComposeMode.New -> "New message"
    }
    state.view == MailWorkspaceView.Outbox -> "Outbox"
    state.view == MailWorkspaceView.Settings -> "Mail settings"
    else -> state.selectedMailbox?.displayName ?: "Mail"
}

internal fun mailDestinations(state: MailWorkspaceUiState): List<NextcloudCollectionDestination> = buildList {
    state.orderedMailboxes.forEach { mailbox ->
        add(
            NextcloudCollectionDestination(
                id = "mailbox:${mailbox.id}",
                label = mailbox.displayName.ifBlank { "Folder" },
                count = mailbox.unread.takeIf { it > 0 },
                supportingText = mailbox.parentName?.let { parent ->
                    "In " + (state.mailboxes?.firstOrNull { it.name == parent }?.displayName ?: parent)
                },
                accessibilityId = "mailbox-${mailbox.id}",
            ),
        )
    }
    if (state.accounts?.isNotEmpty() == true) {
        add(NextcloudCollectionDestination(MAIL_OUTBOX_DESTINATION, "Outbox", section = NextcloudCollectionDestinationSection.Manage))
        add(NextcloudCollectionDestination(MAIL_SETTINGS_DESTINATION, "Mail settings", section = NextcloudCollectionDestinationSection.Manage))
    }
}

private fun mailDestinationIcon(destination: NextcloudCollectionDestination, state: MailWorkspaceUiState): ImageVector? = when {
    destination.id == MAIL_OUTBOX_DESTINATION -> Icons.Outlined.Outbox
    destination.id == MAIL_SETTINGS_DESTINATION -> Icons.Outlined.Settings
    else -> destination.id.removePrefix("mailbox:").toLongOrNull()?.let(state::mailbox)?.let { mailbox ->
        when (state.role(mailbox)) {
            MailMailboxRole.Inbox -> Icons.Outlined.Inbox
            MailMailboxRole.Drafts -> Icons.Outlined.Drafts
            MailMailboxRole.Sent -> Icons.AutoMirrored.Outlined.Send
            MailMailboxRole.Archive -> Icons.Outlined.Archive
            MailMailboxRole.Junk -> Icons.Outlined.Report
            MailMailboxRole.Trash -> Icons.Outlined.Delete
            MailMailboxRole.Other -> Icons.Outlined.Folder
        }
    }
}

private const val MAIL_OUTBOX_DESTINATION = "mail-outbox"
private const val MAIL_SETTINGS_DESTINATION = "mail-settings"
