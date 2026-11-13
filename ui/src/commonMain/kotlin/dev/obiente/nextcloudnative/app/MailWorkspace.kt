package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal sealed interface MailMessagesState {
    data object Idle : MailMessagesState
    data object Loading : MailMessagesState
    data class Loaded(
        val items: List<MailMessageSummary>,
        val endReached: Boolean,
        val loadingMore: Boolean = false,
        val pageFailure: MailFailure? = null,
        val refreshFailure: MailFailure? = null,
    ) : MailMessagesState
    data class Failed(val failure: MailFailure) : MailMessagesState
}

internal data class MailOpenedMessage(
    val summary: MailMessageSummary,
    val detail: MailMessageDetail? = null,
    val failure: MailFailure? = null,
)

internal enum class MailWorkspaceView {
    Mailbox,
    Outbox,
    Settings,
}

internal data class MailNotice(val message: String, val retry: (() -> Unit)? = null)

internal data class MailWorkspaceUiState(
    val contract: MailContract? = null,
    val accounts: List<MailAccount>? = null,
    val accountsFailure: MailFailure? = null,
    val selectedAccountId: Long? = null,
    val mailboxes: List<MailMailbox>? = null,
    val mailboxesFailure: MailFailure? = null,
    val selectedMailboxId: Long? = null,
    val messages: MailMessagesState = MailMessagesState.Idle,
    val searchQuery: String = "",
    val opened: MailOpenedMessage? = null,
    val pendingMessageIds: Set<Long> = emptySet(),
    val mailboxOperationRunning: Boolean = false,
    val knownTags: List<MailTag> = emptyList(),
    val notice: MailNotice? = null,
    val refreshing: Boolean = false,
    val view: MailWorkspaceView = MailWorkspaceView.Mailbox,
) {
    val selectedAccount: MailAccount? get() = accounts?.firstOrNull { it.id == selectedAccountId }
    val selectedMailbox: MailMailbox? get() = mailboxes?.firstOrNull { it.id == selectedMailboxId }
    val writesAllowed: Boolean get() = contract?.writesAllowed == true

    fun mailbox(id: Long): MailMailbox? = mailboxes?.firstOrNull { it.id == id }

    fun role(mailbox: MailMailbox): MailMailboxRole = mailbox.role(selectedAccount)

    /** Mailboxes in tree order: parents before their children, special folders first. */
    val orderedMailboxes: List<MailMailbox>
        get() = mailboxes.orEmpty().sortedWith(
            compareBy<MailMailbox> { mailbox -> mailbox.rootRoleOrder(this) }.thenBy { it.name.lowercase() },
        )

    fun canChangeMessages(mailboxId: Long): Boolean = writesAllowed && mailbox(mailboxId)?.rights?.canChangeFlags != false

    fun canChangeSeen(mailboxId: Long): Boolean = writesAllowed && mailbox(mailboxId)?.rights?.canChangeSeen != false

    fun canRemoveMessages(mailboxId: Long): Boolean = writesAllowed && mailbox(mailboxId)?.rights?.canDeleteMessages != false

    fun moveTargets(sourceMailboxId: Long): List<MailMailbox> =
        orderedMailboxes.filter { it.id != sourceMailboxId && it.rights.canInsert }
}

private fun MailMailbox.rootRoleOrder(state: MailWorkspaceUiState): String {
    val roots = generateSequence(this) { child -> child.parentName?.let { parent -> state.mailboxes?.firstOrNull { it.name == parent } } }
    val root = roots.last()
    val rank = when (state.role(root)) {
        MailMailboxRole.Inbox -> 0
        MailMailboxRole.Drafts -> 1
        MailMailboxRole.Sent -> 2
        MailMailboxRole.Archive -> 3
        MailMailboxRole.Junk -> 4
        MailMailboxRole.Trash -> 5
        MailMailboxRole.Other -> 6
    }
    return "$rank:${root.name.lowercase()}"
}

/**
 * Owns Mail reading and organizing state for one Nextcloud account.
 *
 * Every load is fenced by a generation so a slow response for an earlier account, folder, search,
 * or message cannot overwrite newer state. Flag changes are idempotent and applied optimistically;
 * a rejection rolls back only the affected message, and an unknown result re-reads the folder.
 * Moves, deletes, and folder changes are not idempotent: an unknown result is reconciled by
 * re-reading the server state, and nothing is retried on its own.
 */
internal class MailWorkspaceController(
    private val scope: CoroutineScope,
    private val transport: MailTransport,
    private val discoverInstalledVersion: suspend () -> String?,
) {
    var state by mutableStateOf(MailWorkspaceUiState())
        private set

    private var started = false
    private var accountGeneration = 0L
    private var messagesGeneration = 0L
    private var detailGeneration = 0L
    private var messagesJob: Job? = null
    private var detailJob: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val contract = mailContractFor(discoverInstalledVersion())
            state = state.copy(contract = contract)
        }
        loadAccounts(selectFirst = true)
    }

    fun refresh() {
        if (state.accounts == null) {
            loadAccounts(selectFirst = true)
            return
        }
        loadAccounts(selectFirst = false)
        state.selectedAccountId?.let { loadMailboxes(it, keepSelection = true) }
        state.selectedMailboxId?.let { syncAndReload(it) }
    }

    fun dismissNotice() {
        state = state.copy(notice = null)
    }

    fun showView(view: MailWorkspaceView) {
        state = state.copy(view = view)
    }

    fun selectAccount(accountId: Long) {
        if (state.accounts?.none { it.id == accountId } != false || accountId == state.selectedAccountId) return
        accountGeneration += 1
        invalidateDetail()
        state = state.copy(
            selectedAccountId = accountId,
            mailboxes = null,
            mailboxesFailure = null,
            selectedMailboxId = null,
            messages = MailMessagesState.Idle,
            opened = null,
            view = MailWorkspaceView.Mailbox,
        )
        loadMailboxes(accountId, keepSelection = false)
    }

    fun selectMailbox(mailboxId: Long) {
        if (state.mailbox(mailboxId) == null) return
        invalidateDetail()
        state = state.copy(selectedMailboxId = mailboxId, opened = null, searchQuery = "", view = MailWorkspaceView.Mailbox)
        loadMessages(mailboxId, reset = true)
        syncAndReload(mailboxId, reloadWithoutSync = false)
    }

    fun search(query: String) {
        val mailboxId = state.selectedMailboxId ?: return
        if (query == state.searchQuery) return
        state = state.copy(searchQuery = query.take(MAX_MAIL_FILTER_CHARACTERS))
        loadMessages(mailboxId, reset = true)
    }

    fun loadMore() {
        val mailboxId = state.selectedMailboxId ?: return
        val loaded = state.messages as? MailMessagesState.Loaded ?: return
        if (loaded.endReached || loaded.loadingMore) return
        val cursor = loaded.items.mapNotNull(MailMessageSummary::sentAtEpochSeconds).minOrNull() ?: return
        val generation = messagesGeneration
        state = state.copy(messages = loaded.copy(loadingMore = true, pageFailure = null))
        messagesJob = scope.launch {
            val outcome = transport.read(mailMessagesRequest(mailboxId, cursor, state.searchQuery)) { body ->
                parseMailMessagePage(body, mailboxId, MAIL_MESSAGE_PAGE_SIZE)
            }
            if (generation != messagesGeneration) return@launch
            val current = state.messages as? MailMessagesState.Loaded ?: return@launch
            state = when (outcome) {
                is MailReadOutcome.Loaded -> {
                    val known = current.items.mapTo(mutableSetOf()) { it.id }
                    val added = outcome.value.messages.filterNot { it.id in known }
                    state.copy(
                        messages = current.copy(
                            items = current.items + added,
                            // A page that adds nothing new would page forever on equal timestamps.
                            endReached = outcome.value.endReached || added.isEmpty(),
                            loadingMore = false,
                        ),
                    ).withObservedTags(added)
                }
                is MailReadOutcome.Failed -> state.copy(messages = current.copy(loadingMore = false, pageFailure = outcome.failure))
            }
        }
    }

    fun open(summary: MailMessageSummary) {
        detailGeneration += 1
        val generation = detailGeneration
        detailJob?.cancel()
        state = state.copy(opened = MailOpenedMessage(summary), view = MailWorkspaceView.Mailbox)
        detailJob = scope.launch {
            val outcome = transport.read(mailMessageBodyRequest(summary.id)) { body -> parseMailMessageDetail(body, summary.id) }
            if (generation != detailGeneration) return@launch
            state = when (outcome) {
                is MailReadOutcome.Loaded -> state.copy(opened = MailOpenedMessage(outcome.value.summary, outcome.value))
                is MailReadOutcome.Failed -> state.copy(opened = MailOpenedMessage(summary, failure = outcome.failure))
            }
            if (outcome is MailReadOutcome.Loaded && !summary.flags.seen && state.canChangeSeen(summary.mailboxId)) {
                setFlag(summary.id, "seen", true, announce = false)
            }
        }
    }

    fun retryOpen() {
        state.opened?.summary?.let(::open)
    }

    /** A message body still loading belongs to the context being left and must not reopen there. */
    private fun invalidateDetail() {
        detailGeneration += 1
        detailJob?.cancel()
    }

    fun closeMessage() {
        detailGeneration += 1
        detailJob?.cancel()
        state = state.copy(opened = null)
    }

    fun toggleRead(message: MailMessageSummary) = setFlag(message.id, "seen", !message.flags.seen)

    fun toggleFlagged(message: MailMessageSummary) = setFlag(message.id, "flagged", !message.flags.flagged)

    fun toggleImportant(message: MailMessageSummary) = setFlag(message.id, "important", !message.flags.important)

    fun toggleJunk(message: MailMessageSummary) {
        val junk = !message.flags.junk
        setFlags(message.id, mapOf("\$junk" to junk, "\$notjunk" to !junk), announce = true)
    }

    private fun setFlag(messageId: Long, flag: String, value: Boolean, announce: Boolean = true) =
        setFlags(messageId, mapOf(flag to value), announce)

    private fun setFlags(messageId: Long, flags: Map<String, Boolean>, announce: Boolean) {
        val message = findMessage(messageId) ?: return
        if (!state.writesAllowed || messageId in state.pendingMessageIds) return
        val before = message
        updateMessage(messageId) { it.withFlags(flags) }
        state = state.copy(pendingMessageIds = state.pendingMessageIds + messageId)
        scope.launch {
            val outcome = transport.write(mailSetFlagsRequest(messageId, flags), ignoreMailBody)
            state = state.copy(pendingMessageIds = state.pendingMessageIds - messageId)
            when (outcome) {
                is MailWriteOutcome.Confirmed -> Unit
                is MailWriteOutcome.Rejected -> {
                    updateMessage(messageId) { current -> current.copy(flags = before.flags) }
                    if (announce) notice("${outcome.failure.title}. The message was not changed.")
                }
                MailWriteOutcome.Unknown -> {
                    if (announce) notice("The change could not be confirmed. Showing the current state from the server.")
                    state.selectedMailboxId?.let { loadMessages(it, reset = false) }
                }
            }
        }
    }

    fun setTag(message: MailMessageSummary, tag: MailTag, applied: Boolean) {
        if (!state.canChangeMessages(message.mailboxId) || message.id in state.pendingMessageIds) return
        if (applied == message.tags.any { it.imapLabel == tag.imapLabel }) return
        val before = message.tags
        updateMessage(message.id) { current ->
            current.copy(tags = if (applied) current.tags + tag else current.tags.filterNot { it.imapLabel == tag.imapLabel })
        }
        state = state.copy(pendingMessageIds = state.pendingMessageIds + message.id)
        scope.launch {
            val outcome = transport.write(mailSetTagRequest(message.id, tag.imapLabel, applied), ignoreMailBody)
            state = state.copy(pendingMessageIds = state.pendingMessageIds - message.id)
            when (outcome) {
                is MailWriteOutcome.Confirmed -> Unit
                is MailWriteOutcome.Rejected -> {
                    updateMessage(message.id) { it.copy(tags = before) }
                    notice("${outcome.failure.title}. The tag was not changed.")
                }
                MailWriteOutcome.Unknown -> {
                    notice("The tag change could not be confirmed. Showing the current state from the server.")
                    state.selectedMailboxId?.let { loadMessages(it, reset = false) }
                }
            }
        }
    }

    /** Creates a tag, then applies it. A tag with an unknown result is not applied. */
    fun createTagAndApply(message: MailMessageSummary, name: String, color: String) {
        if (!state.canChangeMessages(message.mailboxId)) return
        val request = runCatching { mailCreateTagRequest(name, color) }.getOrElse {
            notice(it.message ?: "The tag is invalid.")
            return
        }
        scope.launch {
            when (val outcome = transport.write(request, ::parseMailTag)) {
                is MailWriteOutcome.Confirmed -> {
                    state = state.copy(knownTags = (state.knownTags + outcome.value).distinctBy(MailTag::imapLabel))
                    findMessage(message.id)?.let { setTag(it, outcome.value, applied = true) }
                }
                is MailWriteOutcome.Rejected -> notice("${outcome.failure.title}. The tag was not created.")
                MailWriteOutcome.Unknown -> notice("The tag may have been created. Refresh to see it before trying again.")
            }
        }
    }

    fun move(message: MailMessageSummary, destinationMailboxId: Long) {
        if (!state.canRemoveMessages(message.mailboxId) || message.id in state.pendingMessageIds) return
        val destination = state.mailbox(destinationMailboxId) ?: return
        removeWithReconciliation(
            message,
            mailMoveMessageRequest(message.id, destination.id),
            done = "Moved to ${destination.displayName}.",
            failed = "The message was not moved.",
        )
    }

    fun archive(message: MailMessageSummary) {
        val archiveId = state.selectedAccount?.archiveMailboxId ?: return
        if (archiveId == message.mailboxId) return
        move(message, archiveId)
    }

    /** Moves to Trash, or deletes permanently inside Trash. The caller has already confirmed. */
    fun delete(message: MailMessageSummary) {
        if (!state.canRemoveMessages(message.mailboxId) || message.id in state.pendingMessageIds) return
        val permanent = state.mailbox(message.mailboxId)?.let(state::role) == MailMailboxRole.Trash
        removeWithReconciliation(
            message,
            mailDeleteMessageRequest(message.id),
            done = if (permanent) "Message deleted." else "Moved to Trash.",
            failed = "The message was not deleted.",
        )
    }

    private fun removeWithReconciliation(
        message: MailMessageSummary,
        request: NextcloudApiRequest,
        done: String,
        failed: String,
    ) {
        state = state.copy(pendingMessageIds = state.pendingMessageIds + message.id)
        scope.launch {
            val outcome = transport.write(request, ignoreMailBody)
            state = state.copy(pendingMessageIds = state.pendingMessageIds - message.id)
            when (outcome) {
                is MailWriteOutcome.Confirmed -> {
                    removeMessageLocally(message.id)
                    notice(done)
                }
                is MailWriteOutcome.Rejected -> notice("${outcome.failure.title}. $failed")
                MailWriteOutcome.Unknown -> {
                    // The message leaves its folder only if the change happened; re-read to find out.
                    if (state.opened?.summary?.id == message.id) closeMessage()
                    if (state.selectedMailboxId == message.mailboxId) {
                        notice("The result could not be confirmed. The folder was reloaded to show what happened.")
                        loadMessages(message.mailboxId, reset = true)
                    } else {
                        // The folder is read again when it is opened; the one on screen stays as it is.
                        notice("The result could not be confirmed. Open that folder again to see what happened.")
                    }
                }
            }
        }
    }

    fun markMailboxRead(mailboxId: Long) {
        val mailbox = state.mailbox(mailboxId) ?: return
        if (!state.writesAllowed || !mailbox.rights.canChangeSeen) return
        scope.launch {
            when (val outcome = transport.write(mailMarkMailboxReadRequest(mailboxId), ignoreMailBody)) {
                is MailWriteOutcome.Confirmed -> {
                    state = state.copy(mailboxes = state.mailboxes?.map { if (it.id == mailboxId) it.copy(unread = 0) else it })
                    if (state.selectedMailboxId == mailboxId) {
                        val loaded = state.messages as? MailMessagesState.Loaded
                        if (loaded != null) {
                            state = state.copy(messages = loaded.copy(items = loaded.items.map { it.withFlags(mapOf("seen" to true)) }))
                        }
                    }
                }
                is MailWriteOutcome.Rejected -> notice("${outcome.failure.title}. Nothing was marked as read.")
                MailWriteOutcome.Unknown -> {
                    notice("Could not confirm the change. Showing the current state.")
                    state.selectedAccountId?.let { loadMailboxes(it, keepSelection = true) }
                    if (state.selectedMailboxId == mailboxId) loadMessages(mailboxId, reset = false)
                }
            }
        }
    }

    /** Creates a folder under [parent], or at the top level. The result is verified by re-listing. */
    fun createMailbox(name: String, parent: MailMailbox?) {
        val account = state.selectedAccount ?: return
        if (!state.writesAllowed || state.mailboxOperationRunning) return
        val fullName = mailboxFullName(name, parent) ?: return notice("Folder names cannot contain the folder separator.")
        if (state.mailboxes?.any { it.name.equals(fullName, ignoreCase = true) } == true) return notice("A folder with this name already exists.")
        runMailboxOperation(
            request = { mailCreateMailboxRequest(account.id, fullName) },
            succeeded = { mailboxes -> mailboxes.any { it.name == fullName } },
            done = "Folder created.",
            failed = "The folder was not created.",
        )
    }

    fun renameMailbox(mailbox: MailMailbox, newName: String) {
        if (!state.writesAllowed || state.mailboxOperationRunning || !mailbox.rights.canRename) return
        val parent = mailbox.parentName?.let { parentName -> state.mailboxes?.firstOrNull { it.name == parentName } }
        val fullName = mailboxFullName(newName, parent, mailbox.delimiter) ?: return notice("Folder names cannot contain the folder separator.")
        if (fullName == mailbox.name) return
        runMailboxOperation(
            request = { mailRenameMailboxRequest(mailbox.id, fullName) },
            succeeded = { mailboxes -> mailboxes.any { it.name == fullName } && mailboxes.none { it.name == mailbox.name } },
            done = "Folder renamed.",
            failed = "The folder was not renamed.",
        )
    }

    /** Deletes a folder and its messages. The caller has already confirmed. */
    fun deleteMailbox(mailbox: MailMailbox) {
        if (!state.writesAllowed || state.mailboxOperationRunning || !mailbox.rights.canDelete) return
        if (state.role(mailbox) != MailMailboxRole.Other) return notice("Special folders such as the Inbox cannot be deleted here.")
        runMailboxOperation(
            request = { mailDeleteMailboxRequest(mailbox.id) },
            succeeded = { mailboxes -> mailboxes.none { it.id == mailbox.id } },
            done = "Folder deleted.",
            failed = "The folder was not deleted.",
        )
    }

    private fun runMailboxOperation(
        request: () -> NextcloudApiRequest,
        succeeded: (List<MailMailbox>) -> Boolean,
        done: String,
        failed: String,
    ) {
        val accountId = state.selectedAccountId ?: return
        val built = runCatching(request).getOrElse { return notice(it.message ?: failed) }
        state = state.copy(mailboxOperationRunning = true)
        scope.launch {
            val outcome = transport.write(built, ignoreMailBody)
            // Folder state is always re-read: a rename or delete also changes children and counts.
            val listed = transport.read(mailMailboxesRequest(accountId)) { body -> parseMailMailboxes(body, accountId) }
            state = state.copy(mailboxOperationRunning = false)
            if (accountId != state.selectedAccountId) return@launch
            if (listed is MailReadOutcome.Loaded) applyMailboxes(listed.value, keepSelection = true)
            val verified = (listed as? MailReadOutcome.Loaded)?.value?.let(succeeded)
            notice(
                when {
                    outcome is MailWriteOutcome.Rejected && verified != true -> "${outcome.failure.title}. $failed"
                    verified == true -> done
                    verified == false -> failed
                    else -> "The result could not be confirmed. Refresh before trying again."
                },
            )
        }
    }

    fun rememberTag(tag: MailTag) {
        state = state.copy(knownTags = (state.knownTags + tag).distinctBy(MailTag::imapLabel))
    }

    /** Applies a confirmed account change from the settings editor. */
    fun replaceAccount(account: MailAccount) {
        state = state.copy(accounts = state.accounts?.map { if (it.id == account.id) account else it })
    }

    fun reloadAccounts() = loadAccounts(selectFirst = false)

    private fun loadAccounts(selectFirst: Boolean) {
        val generation = ++accountGeneration
        state = state.copy(refreshing = true)
        scope.launch {
            val outcome = transport.read(mailAccountsRequest(), ::parseMailAccounts)
            if (generation != accountGeneration) return@launch
            when (outcome) {
                is MailReadOutcome.Loaded -> {
                    val accounts = outcome.value
                    val selected = state.selectedAccountId?.takeIf { id -> accounts.any { it.id == id } }
                        ?: accounts.firstOrNull()?.id
                    val changedAccount = selected != state.selectedAccountId
                    state = state.copy(accounts = accounts, accountsFailure = null, selectedAccountId = selected, refreshing = false)
                    if (selected != null && (selectFirst || changedAccount)) loadMailboxes(selected, keepSelection = false)
                }
                is MailReadOutcome.Failed -> state = state.copy(accountsFailure = outcome.failure, refreshing = false)
            }
        }
    }

    private fun loadMailboxes(accountId: Long, keepSelection: Boolean) {
        val generation = accountGeneration
        scope.launch {
            val outcome = transport.read(mailMailboxesRequest(accountId)) { body -> parseMailMailboxes(body, accountId) }
            if (generation != accountGeneration || accountId != state.selectedAccountId) return@launch
            when (outcome) {
                is MailReadOutcome.Loaded -> applyMailboxes(outcome.value, keepSelection)
                is MailReadOutcome.Failed -> state = state.copy(mailboxesFailure = outcome.failure)
            }
        }
    }

    private fun applyMailboxes(mailboxes: List<MailMailbox>, keepSelection: Boolean) {
        val previous = state.selectedMailboxId?.takeIf { id -> keepSelection && mailboxes.any { it.id == id } }
        state = state.copy(mailboxes = mailboxes, mailboxesFailure = null)
        val target = previous ?: mailboxes.firstOrNull { state.role(it) == MailMailboxRole.Inbox }?.id ?: mailboxes.firstOrNull()?.id
        if (target != state.selectedMailboxId) {
            state = state.copy(selectedMailboxId = target, opened = null)
            target?.let {
                loadMessages(it, reset = true)
                syncAndReload(it, reloadWithoutSync = false)
            } ?: run { state = state.copy(messages = MailMessagesState.Idle) }
        } else if (state.messages == MailMessagesState.Idle && target != null) {
            loadMessages(target, reset = true)
        }
    }

    /**
     * Asks Mail to fetch new mail from IMAP, then re-reads the folder. Sync is a POST that only
     * changes Mail's server cache, but it is still skipped while writes are off, so an unconfirmed
     * installation is never sent anything but reads.
     */
    private fun syncAndReload(mailboxId: Long, reloadWithoutSync: Boolean = true) {
        if (!state.writesAllowed) {
            if (reloadWithoutSync && state.selectedMailboxId == mailboxId) loadMessages(mailboxId, reset = false)
            return
        }
        scope.launch {
            // Sync changes only Mail's server cache. Failures leave the cached listing usable.
            val synced = transport.write(mailMailboxSyncRequest(mailboxId, initialize = false), ignoreMailBody)
            if (synced is MailWriteOutcome.Rejected && synced.failure.kind == MailFailureKind.Conflict) {
                transport.write(mailMailboxSyncRequest(mailboxId, initialize = true), ignoreMailBody)
            }
            if (state.selectedMailboxId == mailboxId) loadMessages(mailboxId, reset = false)
        }
    }

    /**
     * Loads the first page. [reset] replaces the list with a loading state; otherwise the current
     * list stays visible and a failure is shown as a refresh notice.
     */
    private fun loadMessages(mailboxId: Long, reset: Boolean) {
        val generation = ++messagesGeneration
        messagesJob?.cancel()
        val previous = state.messages as? MailMessagesState.Loaded
        if (reset || previous == null) state = state.copy(messages = MailMessagesState.Loading)
        val query = state.searchQuery
        messagesJob = scope.launch {
            val outcome = transport.read(mailMessagesRequest(mailboxId, filter = query)) { body ->
                parseMailMessagePage(body, mailboxId, MAIL_MESSAGE_PAGE_SIZE)
            }
            if (generation != messagesGeneration || mailboxId != state.selectedMailboxId) return@launch
            state = when (outcome) {
                is MailReadOutcome.Loaded -> state.copy(
                    messages = MailMessagesState.Loaded(
                        items = outcome.value.messages.map { fetched ->
                            // A pending change wins over a read that may have raced it.
                            if (fetched.id in state.pendingMessageIds) findMessage(fetched.id) ?: fetched else fetched
                        },
                        endReached = outcome.value.endReached,
                    ),
                ).withObservedTags(outcome.value.messages)
                is MailReadOutcome.Failed ->
                    if (!reset && previous != null) {
                        state.copy(messages = previous.copy(refreshFailure = outcome.failure))
                    } else {
                        state.copy(messages = MailMessagesState.Failed(outcome.failure))
                    }
            }
        }
    }

    private fun MailWorkspaceUiState.withObservedTags(messages: List<MailMessageSummary>): MailWorkspaceUiState =
        copy(knownTags = (knownTags + messages.flatMap(MailMessageSummary::tags)).distinctBy(MailTag::imapLabel))

    private fun findMessage(id: Long): MailMessageSummary? =
        (state.messages as? MailMessagesState.Loaded)?.items?.firstOrNull { it.id == id }
            ?: state.opened?.summary?.takeIf { it.id == id }

    private fun updateMessage(id: Long, change: (MailMessageSummary) -> MailMessageSummary) {
        val loaded = state.messages as? MailMessagesState.Loaded
        val opened = state.opened
        state = state.copy(
            messages = loaded?.copy(items = loaded.items.map { if (it.id == id) change(it) else it }) ?: state.messages,
            opened = opened?.takeIf { it.summary.id == id }?.let { open ->
                val summary = change(open.summary)
                open.copy(summary = summary, detail = open.detail?.copy(summary = summary))
            } ?: opened,
        )
    }

    private fun removeMessageLocally(id: Long) {
        val loaded = state.messages as? MailMessagesState.Loaded
        state = state.copy(
            messages = loaded?.copy(items = loaded.items.filterNot { it.id == id }) ?: state.messages,
            opened = state.opened?.takeUnless { it.summary.id == id },
        )
    }

    private fun notice(message: String) {
        state = state.copy(notice = MailNotice(message))
    }
}

internal fun MailMessageSummary.withFlags(flags: Map<String, Boolean>): MailMessageSummary = copy(
    flags = this.flags.copy(
        seen = flags["seen"] ?: this.flags.seen,
        flagged = flags["flagged"] ?: this.flags.flagged,
        important = flags["important"] ?: this.flags.important,
        junk = flags["\$junk"] ?: this.flags.junk,
    ),
)

/** Builds an IMAP folder name under [parent]. Returns null when the name is unusable. */
internal fun mailboxFullName(name: String, parent: MailMailbox?, delimiter: String? = parent?.delimiter): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_MAIL_MAILBOX_NAME || trimmed.any(Char::isISOControl)) return null
    val separator = delimiter?.takeIf(String::isNotEmpty)
    if (separator != null && separator in trimmed) return null
    return if (parent == null) trimmed else "${parent.name}${separator ?: "/"}$trimmed"
}
