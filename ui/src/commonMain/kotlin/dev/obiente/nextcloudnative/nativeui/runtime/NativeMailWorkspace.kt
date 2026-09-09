package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationFormAction
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class NativeMailWorkspaceItem(
    val resource: ResourceSpec,
    val record: NativeRecord,
    val presentation: NativeMailboxPresentation,
    val hierarchyDepth: Int = 0,
)

internal data class NativeMailCollectionSummary(
    val total: Int?,
    val unread: Int?,
)

internal data class NativeMailWorkspacePlan(
    val accounts: List<NativeMailWorkspaceItem>,
    val folders: List<NativeMailWorkspaceItem>,
    val messages: List<NativeMailWorkspaceItem>,
    val currentItems: List<NativeMailWorkspaceItem>,
    val selectedContainer: NativeMailWorkspaceItem?,
    val selectedMessage: NativeMailWorkspaceItem?,
    private val cachedMessagesForSelectedMailbox: List<NativeMailWorkspaceItem>,
) {
    val hasMailData: Boolean
        get() = accounts.isNotEmpty() || folders.isNotEmpty() || messages.isNotEmpty()

    val preferredInbox: NativeMailWorkspaceItem?
        get() = folders.maxByOrNull { item -> item.inboxScore() }
            ?.takeIf { item -> item.inboxScore() > 0 }

    val visibleMessages: List<NativeMailWorkspaceItem>
        get() {
            val currentMessages = currentItems.filter { item ->
                item.presentation.kind == NativeMailboxItemKind.Message
            }
            return when {
                currentMessages.isNotEmpty() -> currentMessages
                selectedMessage != null -> messages.visibleMailSiblings(selectedMessage)
                selectedContainer?.presentation?.kind == NativeMailboxItemKind.Folder ->
                    cachedMessagesForSelectedMailbox
                else -> emptyList()
            }
        }
}

internal enum class NativeMailWorkspaceSection {
    Accounts,
    Mailboxes,
    Messages,
    MessageDetail,
    Unknown,
}

internal sealed interface NativeMailWorkspaceContentState {
    data object Ready : NativeMailWorkspaceContentState

    data class Loading(
        val section: NativeMailWorkspaceSection,
    ) : NativeMailWorkspaceContentState

    data class Empty(
        val section: NativeMailWorkspaceSection,
    ) : NativeMailWorkspaceContentState

    data class Error(
        val section: NativeMailWorkspaceSection,
        val message: String,
        val retry: (() -> Unit)?,
        val retryLabel: String,
    ) : NativeMailWorkspaceContentState
}

internal fun nativeMailWorkspacePlan(
    schema: NativeAppSchema,
    currentResource: ResourceSpec,
    currentRecords: List<NativeRecord>,
    context: NativeDatasetContext,
    selectedRecordId: String?,
    selectedRecordResourceId: String? = null,
): NativeMailWorkspacePlan {
    val currentIsMessageFacet = context.parentResourceId
        ?.takeUnless { parentResourceId -> parentResourceId == currentResource.id }
        ?.let(schema::resource)
        ?.let { parentResource ->
            val parent = context.parentRecord
            parent != null &&
                nativeMailboxPresentation(parentResource, parent).kind == NativeMailboxItemKind.Message &&
                currentRecords.any { record ->
                    nativeMailMessageRenderTarget(
                        schema = schema,
                        resource = currentResource,
                        record = record,
                        context = context,
                    ) != null
                }
        }
        ?: false
    val datasets = buildList {
        add(currentResource to currentRecords)
        context.relatedRecords.forEach { (resourceId, records) ->
            if (resourceId != currentResource.id) {
                schema.resource(resourceId)?.let { resource -> add(resource to records) }
            }
        }
    }
    val seen = mutableSetOf<String>()
    val rawItems = datasets.flatMap { (resource, records) ->
        if (context.isNativeMailCollectionSummaryResource(resource)) return@flatMap emptyList()
        records.mapNotNull { record ->
            if (currentIsMessageFacet && resource.id == currentResource.id) return@mapNotNull null
            val presentation = nativeMailboxPresentation(resource, record)
            if (presentation.kind == NativeMailboxItemKind.Unknown) return@mapNotNull null
            val key = nativeMailWorkspaceRecordKey(resource, record, presentation)
            if (!seen.add(key)) return@mapNotNull null
            NativeMailWorkspaceItem(
                resource = resource,
                record = record.withNativeResource(resource.id, currentResource.id),
                presentation = presentation,
                hierarchyDepth = if (presentation.kind == NativeMailboxItemKind.Folder) {
                    record.mailboxHierarchyDepth()
                } else {
                    0
                },
            )
        }
    }
    val minimumFolderDepth = rawItems
        .filter { item -> item.presentation.kind == NativeMailboxItemKind.Folder }
        .minOfOrNull { item -> item.hierarchyDepth }
        ?: 0
    val normalizedItems = rawItems.map { item ->
        if (item.presentation.kind == NativeMailboxItemKind.Folder) {
            item.copy(hierarchyDepth = (item.hierarchyDepth - minimumFolderDepth).coerceAtLeast(0))
        } else {
            item
        }
    }
    // A detail response is a facet of the selected envelope, not a replacement mailbox
    // collection. The envelope may no longer be present in relatedRecords after process restore,
    // cache eviction, or a direct deep link. Preserve that proven parent as a workspace item so
    // detail rendering does not depend on an incidental list-cache hit.
    val parentMessageItem = context.parentRecord?.let { parent ->
        context.parentResourceId
            ?.let(schema::resource)
            ?.let { parentResource ->
                val presentation = nativeMailboxPresentation(parentResource, parent)
                if (presentation.kind != NativeMailboxItemKind.Message) {
                    null
                } else {
                    NativeMailWorkspaceItem(
                        resource = parentResource,
                        record = parent.withNativeResource(parentResource.id, currentResource.id),
                        presentation = presentation,
                    )
                }
            }
    }
    val items = parentMessageItem
        ?.takeIf { parent ->
            normalizedItems.none { item ->
                item.nativeMailWorkspaceRecordKey() == parent.nativeMailWorkspaceRecordKey()
            }
        }
        ?.let { parent -> normalizedItems + parent }
        ?: normalizedItems
    val currentKeys = currentRecords.mapNotNullTo(hashSetOf()) { record ->
        nativeMailboxPresentation(currentResource, record)
            .takeIf { presentation -> presentation.kind != NativeMailboxItemKind.Unknown }
            ?.let { presentation -> nativeMailWorkspaceRecordKey(currentResource, record, presentation) }
    }
    val currentItems = items.filter { item ->
        item.nativeMailWorkspaceRecordKey() in currentKeys
    }
    val messages = items
        .filter { item -> item.presentation.kind == NativeMailboxItemKind.Message }
        .sortedWith(
            compareByDescending<NativeMailWorkspaceItem> { item -> item.presentation.unread }
                .thenByDescending { item -> item.presentation.timestamp.orEmpty() }
                .thenBy { item -> item.presentation.title.lowercase() },
        )
    val selectedMessage = context.parentRecord?.let { parent ->
        val parentIsMessage = context.parentResourceId
            ?.let(schema::resource)
            ?.let { resource -> nativeMailboxPresentation(resource, parent).kind }
            ?.let { kind -> kind == NativeMailboxItemKind.Message }
            ?: false
        if (!parentIsMessage) {
            null
        } else {
            items.singleMailWorkspaceSelection(
                resourceId = context.parentResourceId,
                record = parent,
                kind = NativeMailboxItemKind.Message,
            )
        }
    } ?: items.singleMailWorkspaceSelection(
        resourceId = selectedRecordResourceId,
        recordId = selectedRecordId,
        kind = NativeMailboxItemKind.Message,
    )
    val rawSelectedContainer = context.parentRecord?.let { parent ->
        context.parentResourceId?.let { parentResourceId ->
            items.singleMailWorkspaceSelection(
                resourceId = parentResourceId,
                record = parent,
                kinds = setOf(NativeMailboxItemKind.Account, NativeMailboxItemKind.Folder),
            )
        }
    } ?: items.singleMailWorkspaceSelection(
        resourceId = selectedRecordResourceId,
        recordId = selectedRecordId,
        kinds = setOf(NativeMailboxItemKind.Account, NativeMailboxItemKind.Folder),
    ) ?: selectedMessage?.let { message ->
        items.filter { candidate ->
            candidate.presentation.kind == NativeMailboxItemKind.Folder &&
                message.belongsToMailbox(candidate)
        }.singleOrNull()
    }
    val collectionSummary = context.nativeMailCollectionSummary(schema)
    val selectedContainer = rawSelectedContainer?.let { container ->
        if (container.presentation.kind != NativeMailboxItemKind.Folder || collectionSummary == null) {
            container
        } else {
            container.copy(
                presentation = container.presentation.copy(
                    unreadCount = collectionSummary.unread ?: container.presentation.unreadCount,
                    totalCount = collectionSummary.total ?: container.presentation.totalCount,
                    unread = (collectionSummary.unread ?: container.presentation.unreadCount ?: 0) > 0,
                ),
            )
        }
    }
    val cachedMessagesForSelectedMailbox = selectedContainer
        ?.takeIf { container -> container.presentation.kind == NativeMailboxItemKind.Folder }
        ?.let { mailbox -> messages.filter { message -> message.belongsToMailbox(mailbox) } }
        .orEmpty()
    val folders = items
        .filter { item -> item.presentation.kind == NativeMailboxItemKind.Folder }
        .map { item ->
            selectedContainer?.takeIf { selected ->
                selected.nativeMailWorkspaceRecordKey() == item.nativeMailWorkspaceRecordKey()
            } ?: item
        }
        .sortedWith(
            compareByDescending<NativeMailWorkspaceItem> { item -> item.inboxScore() }
                .thenBy { item -> item.mailboxHierarchySortKey() }
                .thenBy { item -> item.hierarchyDepth },
        )
    return NativeMailWorkspacePlan(
        accounts = items
            .filter { item -> item.presentation.kind == NativeMailboxItemKind.Account }
            .sortedBy { item -> item.presentation.title.lowercase() },
        folders = folders,
        messages = messages,
        currentItems = currentItems,
        selectedContainer = selectedContainer,
        selectedMessage = selectedMessage,
        cachedMessagesForSelectedMailbox = cachedMessagesForSelectedMailbox,
    )
}

internal fun NativeDatasetContext.nativeMailCollectionSummary(
    schema: NativeAppSchema,
): NativeMailCollectionSummary? {
    val summaries = relatedRecords.mapNotNull { (resourceId, records) ->
        val resource = schema.resource(resourceId) ?: return@mapNotNull null
        if (!isNativeMailCollectionSummaryResource(resource)) return@mapNotNull null
        records.singleOrNull()?.nativeMailCollectionSummary()
    }
    val total = summaries.mapNotNull(NativeMailCollectionSummary::total).distinct().singleOrNull()
    val unread = summaries.mapNotNull(NativeMailCollectionSummary::unread).distinct().singleOrNull()
    return NativeMailCollectionSummary(total = total, unread = unread)
        .takeIf { summary -> summary.total != null || summary.unread != null }
}

private fun NativeDatasetContext.isNativeMailCollectionSummaryResource(resource: ResourceSpec): Boolean =
    resource.id in mailCollectionSummaryResourceIds ||
        listOf(resource.id, resource.name).flatMap(String::mailSemanticWords).any { word ->
            word in setOf("stat", "stats", "status", "summary", "statistics")
        }

private fun NativeRecord.nativeMailCollectionSummary(): NativeMailCollectionSummary? {
    val values = (values + displayValues).entries.associate { (key, value) ->
        key.mailSemanticKey() to value
    }
    fun integer(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        values[key]?.trim()?.toIntOrNull()?.takeIf { value -> value >= 0 }
    }
    val total = integer("total", "totalmessages", "messagecount", "messagescount")
    val unread = integer("unread", "unseen", "unreadcount", "unseenmessages")
    return NativeMailCollectionSummary(total = total, unread = unread)
        .takeIf { summary -> summary.total != null || summary.unread != null }
}

/**
 * Resolves a selected message body only from the active response. A body endpoint commonly
 * returns a separate record that must be merged with the selected envelope to render the desktop
 * detail pane. The retained envelope remains navigation context, never body authority.
 */
internal fun nativeMailWorkspaceDetailTarget(
    schema: NativeAppSchema,
    currentResource: ResourceSpec,
    currentRecords: List<NativeRecord>,
    context: NativeDatasetContext,
    selectedMessage: NativeMailWorkspaceItem?,
): NativeMailMessageRenderTarget? {
    if (selectedMessage == null) return null
    val parentMatchesSelection = context.parentRecord?.let { parent ->
        val parentResource = context.parentResourceId?.let(schema::resource) ?: return@let false
        val presentation = nativeMailboxPresentation(parentResource, parent)
        presentation.kind == NativeMailboxItemKind.Message &&
            nativeMailWorkspaceRecordKey(parentResource, parent, presentation) ==
            selectedMessage.nativeMailWorkspaceRecordKey()
    } == true
    val currentTarget = currentRecords.firstNotNullOfOrNull { record ->
        val presentation = nativeMailboxPresentation(currentResource, record)
        val recordMatchesSelection = presentation.kind == NativeMailboxItemKind.Message &&
            nativeMailWorkspaceRecordKey(currentResource, record, presentation) ==
            selectedMessage.nativeMailWorkspaceRecordKey()
        if (!parentMatchesSelection && !recordMatchesSelection) return@firstNotNullOfOrNull null
        nativeMailMessageRenderTarget(schema, currentResource, record, context)
    }
    if (currentTarget != null) return currentTarget
    if (currentResource.id != selectedMessage.resource.id) return null
    val currentEnvelope = currentRecords.firstOrNull { record ->
        val presentation = nativeMailboxPresentation(currentResource, record)
        presentation.kind == NativeMailboxItemKind.Message &&
            nativeMailWorkspaceRecordKey(currentResource, record, presentation) ==
            selectedMessage.nativeMailWorkspaceRecordKey()
    } ?: return null
    return nativeMailMessageRenderTarget(schema, currentResource, currentEnvelope, context)
}

internal fun isNativeMailWorkspaceContext(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    records: List<NativeRecord>,
    context: NativeDatasetContext,
): Boolean = nativeMailWorkspacePlan(schema, resource, records, context, null).hasMailData

internal fun nativeMailSoleAccountLandingRecord(
    resource: ResourceSpec,
    records: List<NativeRecord>,
): NativeRecord? {
    if (records.isEmpty()) return null
    val accounts = records.filter { record ->
        nativeMailboxPresentation(resource, record).kind == NativeMailboxItemKind.Account
    }
    return accounts.singleOrNull()?.takeIf { accounts.size == records.size }
}

internal fun nativeMailInboxLandingRecord(
    resource: ResourceSpec,
    records: List<NativeRecord>,
): NativeRecord? {
    if (records.isEmpty()) return null
    val folders = records.mapNotNull { record ->
        val presentation = nativeMailboxPresentation(resource, record)
        if (presentation.kind != NativeMailboxItemKind.Folder) return@mapNotNull null
        NativeMailWorkspaceItem(
            resource = resource,
            record = record,
            presentation = presentation,
            hierarchyDepth = record.mailboxHierarchyDepth(),
        )
    }
    if (folders.size != records.size) return null
    val highestScore = folders.maxOfOrNull { item -> item.inboxScore() } ?: return null
    if (highestScore <= 0) return null
    return folders
        .filter { item -> item.inboxScore() == highestScore }
        .map { item -> item.record }
        .singleOrNull()
}

internal fun isNativeMailContainerRecord(
    schema: NativeAppSchema,
    resourceId: String,
    record: NativeRecord,
): Boolean = schema.resource(resourceId)
    ?.let { resource -> nativeMailboxPresentation(resource, record).kind }
    ?.let { kind -> kind == NativeMailboxItemKind.Account || kind == NativeMailboxItemKind.Folder }
    ?: false

/**
 * A sparse Mail selection must name its account before it can reuse a screen snapshot. Mailbox
 * IDs are commonly reused (for example, Inbox) and guessing their account would show the wrong
 * cached messages after an account switch.
 */
internal fun nativeMailScreenCacheScopeIsSafe(
    schema: NativeAppSchema,
    resourceId: String?,
    record: NativeRecord?,
): Boolean {
    if (resourceId == null || record == null) return true
    val resource = schema.resource(resourceId) ?: return true
    return when (val kind = nativeMailboxPresentation(resource, record).kind) {
        NativeMailboxItemKind.Unknown -> true
        NativeMailboxItemKind.Account,
        NativeMailboxItemKind.Folder -> record.mailWorkspaceAccountIds(kind).isNotEmpty()
        NativeMailboxItemKind.Message ->
            record.mailWorkspaceAccountIds(kind).isNotEmpty() &&
                record.mailWorkspaceMailboxIds(kind).isNotEmpty()
    }
}

internal fun DynamicAppDescriptor.hasNativeMailWorkspaceSemantics(): Boolean {
    val words = resources.map { resource ->
        listOf(resource.id, resource.label).flatMap { value -> value.mailSemanticWords() }.toSet()
    }
    return words.hasCompleteMailWorkspaceSemantics()
}

internal fun NativeAppSchema.hasNativeMailWorkspaceSemantics(): Boolean {
    val words = resources.map { resource ->
        listOf(resource.id, resource.name).flatMap { value -> value.mailSemanticWords() }.toSet()
    }
    return words.hasCompleteMailWorkspaceSemantics()
}

private fun List<Set<String>>.hasCompleteMailWorkspaceSemantics(): Boolean {
    val hasAccounts = any { resourceWords ->
        resourceWords.any { word -> word in setOf("account", "accounts", "mailaccount", "mailaccounts") }
    }
    val hasFolders = any { resourceWords ->
        resourceWords.any { word ->
            word in setOf("mailbox", "mailboxes", "folder", "folders", "mailfolder", "mailfolders")
        }
    }
    val hasMessages = any { resourceWords ->
        resourceWords.any { word ->
            word in setOf("message", "messages", "email", "emails", "thread", "threads")
        }
    }
    return hasAccounts && hasFolders && hasMessages
}

internal fun nativeMailWorkspaceSection(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    context: NativeDatasetContext,
): NativeMailWorkspaceSection {
    val parentIsMessage = context.parentRecord?.let { parentRecord ->
        context.parentResourceId
            ?.let(schema::resource)
            ?.let { parentResource ->
                nativeMailboxPresentation(parentResource, parentRecord).kind == NativeMailboxItemKind.Message
            }
    } == true
    if (parentIsMessage) return NativeMailWorkspaceSection.MessageDetail
    val words = listOf(resource.id, resource.name)
        .flatMap { value -> value.mailSemanticWords() }
        .toSet()
    return when {
        words.any { word -> word in setOf("account", "accounts", "mailaccount", "mailaccounts") } ->
            NativeMailWorkspaceSection.Accounts
        words.any { word ->
            word in setOf("mailbox", "mailboxes", "folder", "folders", "mailfolder", "mailfolders")
        } -> NativeMailWorkspaceSection.Mailboxes
        words.any { word ->
            word in setOf("message", "messages", "email", "emails", "thread", "threads")
        } -> NativeMailWorkspaceSection.Messages
        else -> NativeMailWorkspaceSection.Unknown
    }
}

/**
 * Finds a parameter-free compose form using only contract semantics.
 *
 * The result remains null when the contract exposes several equally plausible routes. This keeps
 * the workspace app-neutral and prevents a guessed write from becoming a prominent action.
 */
internal fun DynamicAppDescriptor.preferredNativeMailComposeAction(
    schema: NativeAppSchema,
): DynamicNavigationFormAction? {
    val actionsById = actions.associateBy { action -> action.id }
    val candidates = forms.mapNotNull { form ->
        val action = actionsById[form.actionId] ?: return@mapNotNull null
        val schemaAction = schema.action(action.id) ?: return@mapNotNull null
        if (
            action.binding.method == HttpMethod.GET ||
            action.binding.pathParameters.isNotEmpty() ||
            schemaAction.intent != ActionIntent.create
        ) {
            return@mapNotNull null
        }
        val resource = schema.resource(form.resourceId)
        val resourceWords = listOf(form.resourceId, resource?.name.orEmpty())
            .flatMap { value -> value.mailSemanticWords() }
        val actionWords = listOf(form.title, action.label, action.id)
            .flatMap { value -> value.mailSemanticWords() }
        val mailResource = resourceWords.any { word ->
            word in setOf("mail", "message", "messages", "email", "emails", "draft", "drafts", "outbox")
        }
        val composeIntent = actionWords.any { word ->
            word in setOf("compose", "send", "write", "newmessage", "createmessage", "sendmessage")
        }
        if (!mailResource || !composeIntent) return@mapNotNull null
        val score = (if ("compose" in actionWords) 40 else 0) +
            (if ("send" in actionWords || "sendmessage" in actionWords) 30 else 0) +
            (if (resourceWords.any { it in setOf("message", "messages") }) 20 else 0)
        score to DynamicNavigationFormAction(
            formId = form.id,
            label = form.title,
            resourceId = form.resourceId,
            actionId = action.id,
        )
    }
    val highest = candidates.maxOfOrNull { (score, _) -> score } ?: return null
    return candidates
        .filter { (score, _) -> score == highest }
        .map { (_, action) -> action }
        .singleOrNull()
}


internal fun nativeMailCompactSearchAvailable(
    items: List<NativeMailWorkspaceItem>,
    searchHandlerAvailable: Boolean,
    query: String = "",
): Boolean = searchHandlerAvailable && (
    query.isNotBlank() ||
        (
            items.isNotEmpty() &&
                items.all { item -> item.presentation.kind == NativeMailboxItemKind.Message }
            )
    )

internal fun nativeMailWorkspaceSearchAvailable(
    stateReady: Boolean,
    messageCount: Int,
    query: String,
): Boolean = query.isNotBlank() || (stateReady && messageCount > 1)

internal fun nativeMailVisibleMessages(
    items: List<NativeMailWorkspaceItem>,
    query: String,
): List<NativeMailWorkspaceItem> = if (query.isBlank()) {
    items
} else {
    items.filter { item ->
        nativeRecordMatchesCollectionQuery(
            resource = item.resource,
            record = item.record,
            query = query,
        )
    }
}

@Composable
internal fun NativeMailSearchableMessageList(
    items: List<NativeMailWorkspaceItem>,
    selectedMessage: NativeMailWorkspaceItem?,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    modifier: Modifier = Modifier,
    contentState: NativeMailWorkspaceContentState = NativeMailWorkspaceContentState.Ready,
    emptyContent: (@Composable () -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    loadMoreError: String? = null,
    searchQuery: String,
    onSearchQueryChanged: ((String) -> Unit)?,
) {
    val visibleItems = remember(items, searchQuery) {
        nativeMailVisibleMessages(items, searchQuery)
    }
    val searchAllowsPaging = nativeMailSearchAllowsAutoPaging(searchQuery)
    val activeLoadMore = onLoadMore.takeIf { searchAllowsPaging }
    Column(modifier = modifier) {
        onSearchQueryChanged?.let { onQueryChanged ->
            GenericCollectionSearchField(
                resourceName = "Loaded messages",
                query = searchQuery,
                onQueryChanged = onQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = NextcloudSpacing.Medium,
                        vertical = NextcloudSpacing.Small,
                    ),
            )
        }
        NativeMailMessageList(
            items = visibleItems,
            selectedMessage = selectedMessage,
            onSelectRecord = onSelectRecord,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentState = contentState,
            emptyContent = if (searchQuery.isBlank()) {
                emptyContent
            } else {
                {
                    NativeMailSearchEmpty(
                        query = searchQuery,
                        loading = false,
                        error = null,
                        onRetry = null,
                        onClear = { onSearchQueryChanged?.invoke("") },
                    )
                }
            },
            onLoadMore = activeLoadMore,
            loadingMore = loadingMore && searchAllowsPaging,
            loadMoreError = loadMoreError.takeIf { searchAllowsPaging },
        )
    }
}

internal fun nativeMailSearchAllowsAutoPaging(query: String): Boolean = query.isBlank()

@Composable
private fun NativeMailSearchEmpty(
    query: String,
    loading: Boolean,
    error: String?,
    onRetry: (() -> Unit)?,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            Text(
                "Searching more messages...",
                modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                if (error == null) "No matching messages" else "Could not finish searching",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                error ?: "Nothing matches \"$query\".",
                modifier = Modifier.padding(top = NextcloudSpacing.Small),
                color = if (error == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Row(
                modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                if (error != null && onRetry != null) {
                    Button(onClick = onRetry) { Text("Retry") }
                }
                Button(onClick = onClear) { Text("Clear search") }
            }
        }
    }
}

@Composable
internal fun NativeMailRail(
    plan: NativeMailWorkspacePlan,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val selectedKey = plan.selectedContainer?.nativeMailWorkspaceRecordKey()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
    ) {
        if (plan.accounts.isNotEmpty()) {
            item {
                Text(
                    "Accounts",
                    modifier = Modifier.padding(
                        start = NextcloudSpacing.Small,
                        top = NextcloudSpacing.Small,
                        bottom = NextcloudSpacing.XSmall,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(plan.accounts, key = { item -> "account:${item.nativeMailWorkspaceRecordKey()}" }) { item ->
                NativeMailRailRow(
                    item = item,
                    selected = selectedKey == item.nativeMailWorkspaceRecordKey(),
                    onSelectRecord = onSelectRecord,
                )
            }
        }
        if (plan.folders.isNotEmpty()) {
            item {
                Text(
                    "Mailboxes",
                    modifier = Modifier.padding(
                        start = NextcloudSpacing.Small,
                        top = NextcloudSpacing.Large,
                        bottom = NextcloudSpacing.XSmall,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(plan.folders, key = { item -> "folder:${item.nativeMailWorkspaceRecordKey()}" }) { item ->
                NativeMailRailRow(
                    item = item,
                    selected = selectedKey == item.nativeMailWorkspaceRecordKey(),
                    onSelectRecord = onSelectRecord,
                )
            }
        }
    }
}

@Composable
private fun NativeMailRailRow(
    item: NativeMailWorkspaceItem,
    selected: Boolean,
    onSelectRecord: ((NativeRecord) -> Unit)?,
) {
    val interaction = onSelectRecord
        ?.let { callback -> Modifier.clickable { callback(item.record) } }
        ?: Modifier
    Surface(
        modifier = interaction
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                this.selected = selected
                val unreadCount = item.presentation.unreadCount?.takeIf { count -> count > 0 }
                if (unreadCount != null) {
                    stateDescription = "$unreadCount unread"
                } else if (selected) {
                    stateDescription = "Selected"
                }
            },
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = (item.hierarchyDepth * 12).dp + NextcloudSpacing.Small,
                    top = NextcloudSpacing.Small,
                    end = NextcloudSpacing.Small,
                    bottom = NextcloudSpacing.Small,
                ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (item.presentation.kind == NativeMailboxItemKind.Account) {
                    NextcloudIcons.app("mail")
                } else {
                    NextcloudIcons.Folder
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NextcloudTheme.colors.appIcon,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.presentation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.presentation.unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.presentation.sender?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (
                    item.presentation.kind == NativeMailboxItemKind.Folder &&
                    item.presentation.totalCount != null
                ) {
                    val total = item.presentation.totalCount
                    val unread = item.presentation.unreadCount
                    Text(
                        if (unread != null) "$unread unread of $total" else "$total messages",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item.presentation.unreadCount?.takeIf { count -> count > 0 }?.let { count ->
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun NativeMailMessageList(
    items: List<NativeMailWorkspaceItem>,
    selectedMessage: NativeMailWorkspaceItem?,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    modifier: Modifier = Modifier,
    contentState: NativeMailWorkspaceContentState = NativeMailWorkspaceContentState.Ready,
    emptyContent: (@Composable () -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    loadMoreError: String? = null,
) {
    if (items.isEmpty()) {
        if (contentState == NativeMailWorkspaceContentState.Ready && emptyContent != null) {
            Box(modifier = modifier.fillMaxSize()) {
                emptyContent()
            }
        } else {
            NativeMailWorkspaceStatus(
                state = contentState.takeUnless { state -> state == NativeMailWorkspaceContentState.Ready }
                    ?: NativeMailWorkspaceContentState.Empty(NativeMailWorkspaceSection.Messages),
                modifier = modifier,
            )
        }
        return
    }
    val listState = rememberLazyListState()
    val pagingMessages = items.all { item -> item.presentation.kind == NativeMailboxItemKind.Message }
    NativeMailAutoPager(
        listState = listState,
        itemCount = items.size,
        onLoadMore = onLoadMore.takeIf { pagingMessages },
        loadingMore = loadingMore,
        loadMoreError = loadMoreError,
    )
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = NextcloudSpacing.Medium,
            top = NextcloudSpacing.Small,
            end = NextcloudSpacing.Medium,
            bottom = NextcloudSpacing.XXLarge,
        ),
    ) {
        items(items, key = { item -> item.nativeMailWorkspaceRecordKey() }) { item ->
            val selected = item.presentation.kind == NativeMailboxItemKind.Message &&
                item.nativeMailWorkspaceRecordKey() == selectedMessage?.nativeMailWorkspaceRecordKey()
            val senderLabel = nativeMailSenderLabel(item.presentation.sender)
            val timestampLabel = nativeMailTimestampLabel(item.presentation.timestamp)
            val interaction = onSelectRecord
                ?.let { callback -> Modifier.clickable { callback(item.record) } }
                ?: Modifier
            Surface(
                modifier = interaction
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        this.selected = selected
                        stateDescription = if (item.presentation.unread) "Unread" else "Read"
                    },
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when (item.presentation.kind) {
                            NativeMailboxItemKind.Folder -> NextcloudIcons.Folder
                            else -> NextcloudIcons.app("mail")
                        },
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = NextcloudTheme.colors.appIcon,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            Text(
                                senderLabel ?: item.presentation.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (item.presentation.unread) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            timestampLabel?.let { timestamp ->
                                Text(
                                    timestamp,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (senderLabel != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    item.presentation.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (item.presentation.unread) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                item.presentation.threadSize?.let { count ->
                                    Text(
                                        "$count messages",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        item.presentation.preview?.let { preview ->
                            Text(
                                preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (item.presentation.flagged) {
                        Icon(
                            NextcloudIcons.Favorite,
                            contentDescription = "Flagged",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (pagingMessages && (loadingMore || loadMoreError != null)) {
            item(key = "mail-paging-footer") {
                NativeMailPagingStatus(
                    loadingMore = loadingMore,
                    loadMoreError = loadMoreError,
                    onRetry = onLoadMore,
                )
            }
        }
    }
}

internal fun nativeMailShouldLoadMore(
    lastVisibleIndex: Int,
    totalItems: Int,
    prefetchDistance: Int = 3,
): Boolean = totalItems > 0 && lastVisibleIndex >= (totalItems - prefetchDistance).coerceAtLeast(0)

@Composable
private fun NativeMailAutoPager(
    listState: LazyListState,
    itemCount: Int,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    LaunchedEffect(listState, itemCount, onLoadMore, loadingMore, loadMoreError) {
        if (onLoadMore == null || loadingMore || loadMoreError != null) return@LaunchedEffect
        snapshotFlow {
            nativeMailShouldLoadMore(
                lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                totalItems = listState.layoutInfo.totalItemsCount,
            )
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) onLoadMore()
        }
    }
}

@Composable
private fun NativeMailPagingStatus(
    loadingMore: Boolean,
    loadMoreError: String?,
    onRetry: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loadingMore) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
            )
            Text(
                "Loading more messages...",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                loadMoreError ?: "Could not load more messages.",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (onRetry != null) {
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
internal fun NativeMailSelectionPlaceholder(plan: NativeMailWorkspacePlan) {
    val title: String
    val message: String
    when {
        plan.accounts.isNotEmpty() && plan.selectedContainer == null -> {
            title = "Choose a mail account"
            message = "Select an account to view its mailboxes."
        }

        plan.selectedContainer?.presentation?.kind == NativeMailboxItemKind.Account -> {
            title = "Choose a mailbox"
            message = "Select a mailbox to view its messages."
        }

        else -> {
            title = "No messages in this mailbox"
            message = "New messages delivered here will appear in this list."
        }
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Icon(
                NextcloudIcons.app("mail"),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = NextcloudTheme.colors.appIcon,
            )
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun NativeMailWorkspaceStatus(
    state: NativeMailWorkspaceContentState,
    modifier: Modifier = Modifier,
) {
    val title: String
    val message: String
    val loading: Boolean
    val retry: (() -> Unit)?
    val retryLabel: String
    when (state) {
        NativeMailWorkspaceContentState.Ready -> return
        is NativeMailWorkspaceContentState.Loading -> {
            loading = true
            retry = null
            retryLabel = "Try again"
            title = when (state.section) {
                NativeMailWorkspaceSection.Accounts -> "Loading mail accounts"
                NativeMailWorkspaceSection.Mailboxes -> "Loading mailboxes"
                NativeMailWorkspaceSection.Messages -> "Loading messages"
                NativeMailWorkspaceSection.MessageDetail -> "Opening message"
                NativeMailWorkspaceSection.Unknown -> "Loading mail"
            }
            message = when (state.section) {
                NativeMailWorkspaceSection.MessageDetail -> "Fetching the message body and attachments..."
                else -> "Fetching the latest mail from your server..."
            }
        }
        is NativeMailWorkspaceContentState.Empty -> {
            loading = false
            retry = null
            retryLabel = "Try again"
            title = when (state.section) {
                NativeMailWorkspaceSection.Accounts -> "No mail accounts found"
                NativeMailWorkspaceSection.Mailboxes -> "No mailboxes found"
                NativeMailWorkspaceSection.Messages -> "No messages in this mailbox"
                NativeMailWorkspaceSection.MessageDetail -> "No readable message body"
                NativeMailWorkspaceSection.Unknown -> "Nothing to show yet"
            }
            message = when (state.section) {
                NativeMailWorkspaceSection.Accounts ->
                    "Connect a mail account on the server, then refresh this view."
                NativeMailWorkspaceSection.Mailboxes ->
                    "This account did not return any mailboxes."
                NativeMailWorkspaceSection.Messages ->
                    "New messages delivered here will appear in this list."
                NativeMailWorkspaceSection.MessageDetail ->
                    "The envelope is available, but the server returned no readable content."
                NativeMailWorkspaceSection.Unknown ->
                    "Mail content will appear here when the server returns it."
            }
        }
        is NativeMailWorkspaceContentState.Error -> {
            loading = false
            retry = state.retry
            retryLabel = state.retryLabel
            title = when (state.section) {
                NativeMailWorkspaceSection.Accounts -> "Could not load mail accounts"
                NativeMailWorkspaceSection.Mailboxes -> "Could not load mailboxes"
                NativeMailWorkspaceSection.Messages -> "Could not refresh this mailbox"
                NativeMailWorkspaceSection.MessageDetail -> "Could not open this message"
                NativeMailWorkspaceSection.Unknown -> "Could not load mail"
            }
            message = state.message
        }
    }
    Box(modifier = modifier.fillMaxSize().padding(NextcloudSpacing.XLarge), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            } else {
                Icon(
                    imageVector = if (state is NativeMailWorkspaceContentState.Error) {
                        NextcloudIcons.Error
                    } else {
                        NextcloudIcons.app("mail")
                    },
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = if (state is NativeMailWorkspaceContentState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        NextcloudTheme.colors.appIcon
                    },
                )
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            retry?.let { action ->
                Button(onClick = action) {
                    Text(retryLabel)
                }
            }
        }
    }
}

@Composable
internal fun NativeMailDetailPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Icon(
                NextcloudIcons.app("mail"),
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Select a message to read it",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun MailPaneDivider() {
    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun NativeRecord.withNativeResource(
    resourceId: String,
    currentResourceId: String,
): NativeRecord = if (resourceId == currentResourceId) {
    this
} else {
    copy(values = values + (NATIVE_SYNTHETIC_RESOURCE_FIELD to resourceId))
}

/**
 * Cached mail rows may share a server ID across accounts or collection resources. Keep their
 * renderer-local identity composite so an unrelated cache row cannot replace, select, or render
 * beside the active mailbox response.
 */
internal fun nativeMailWorkspaceRecordKey(
    resource: ResourceSpec,
    record: NativeRecord,
    presentation: NativeMailboxPresentation,
): String = listOf(
    resource.id,
    record.id,
    presentation.kind.name,
    record.mailWorkspaceAccountIds(presentation.kind).sorted().joinToString(","),
    record.mailWorkspaceMailboxIds(presentation.kind).sorted().joinToString(","),
).joinToString("\u0000")

internal fun NativeMailWorkspaceItem.nativeMailWorkspaceRecordKey(): String =
    nativeMailWorkspaceRecordKey(resource, record, presentation)

private fun List<NativeMailWorkspaceItem>.singleMailWorkspaceSelection(
    resourceId: String?,
    record: NativeRecord,
    kind: NativeMailboxItemKind,
): NativeMailWorkspaceItem? = singleMailWorkspaceSelection(
    resourceId = resourceId,
    recordId = record.id,
    recordAccountIds = record.mailWorkspaceAccountIds(kind),
    kind = kind,
)

private fun List<NativeMailWorkspaceItem>.singleMailWorkspaceSelection(
    resourceId: String?,
    record: NativeRecord,
    kinds: Set<NativeMailboxItemKind>,
): NativeMailWorkspaceItem? {
    val candidates = filter { item ->
        item.resource.id == resourceId &&
            item.record.id == record.id &&
            item.presentation.kind in kinds
    }
    val matchingKinds = candidates.map { item -> item.presentation.kind }.toSet()
    if (matchingKinds.size != 1) return null
    val kind = matchingKinds.single()
    return candidates.singleMailWorkspaceSelection(
        resourceId = resourceId,
        recordId = record.id,
        recordAccountIds = record.mailWorkspaceAccountIds(kind),
        kind = kind,
    )
}

private fun List<NativeMailWorkspaceItem>.singleMailWorkspaceSelection(
    resourceId: String?,
    recordId: String?,
    kind: NativeMailboxItemKind,
): NativeMailWorkspaceItem? = singleMailWorkspaceSelection(
    resourceId = resourceId,
    recordId = recordId,
    recordAccountIds = emptySet(),
    kind = kind,
)

private fun List<NativeMailWorkspaceItem>.singleMailWorkspaceSelection(
    resourceId: String?,
    recordId: String?,
    kinds: Set<NativeMailboxItemKind>,
): NativeMailWorkspaceItem? {
    if (resourceId == null || recordId == null) return null
    val candidates = filter { item ->
        item.resource.id == resourceId &&
            item.record.id == recordId &&
            item.presentation.kind in kinds
    }
    return candidates.singleOrNull()
}

private fun List<NativeMailWorkspaceItem>.singleMailWorkspaceSelection(
    resourceId: String?,
    recordId: String?,
    recordAccountIds: Set<String>,
    kind: NativeMailboxItemKind,
): NativeMailWorkspaceItem? {
    if (resourceId == null || recordId == null) return null
    val candidates = filter { item ->
        item.resource.id == resourceId &&
            item.record.id == recordId &&
            item.presentation.kind == kind
    }
    if (candidates.size <= 1) return candidates.singleOrNull()
    if (recordAccountIds.isEmpty()) return null
    return candidates.filter { item ->
        item.record.mailWorkspaceAccountIds(item.presentation.kind).intersect(recordAccountIds).isNotEmpty()
    }.singleOrNull()
}

/**
 * A cached message is eligible only when it names the active mailbox and, whenever that mailbox
 * belongs to an account, also names the same account. Missing relationship values stay hidden:
 * guessing would leak an orphaned row into the wrong mailbox when IDs overlap.
 */
private fun NativeMailWorkspaceItem.belongsToMailbox(mailbox: NativeMailWorkspaceItem): Boolean {
    val mailboxIds = mailbox.record.mailWorkspaceMailboxIds(mailbox.presentation.kind)
    if (mailboxIds.isEmpty()) return false
    val messageMailboxIds = record.mailWorkspaceMailboxIds(presentation.kind)
    if (messageMailboxIds.intersect(mailboxIds).isEmpty()) return false
    val mailboxAccountIds = mailbox.record.mailWorkspaceAccountIds(mailbox.presentation.kind)
    if (mailboxAccountIds.isEmpty()) return true
    val messageAccountIds = record.mailWorkspaceAccountIds(presentation.kind)
    return messageAccountIds.isNotEmpty() && messageAccountIds.intersect(mailboxAccountIds).isNotEmpty()
}

/**
 * A message body response normally has no rows of its own. Keep the selected message in its
 * desktop list alongside only cached siblings that prove the same account and mailbox identity.
 * Without both relation IDs, do not guess a mailbox and retain just the selected message.
 */
private fun List<NativeMailWorkspaceItem>.visibleMailSiblings(
    selected: NativeMailWorkspaceItem,
): List<NativeMailWorkspaceItem> {
    val selectedAccountIds = selected.record.mailWorkspaceAccountIds(selected.presentation.kind)
    val selectedMailboxIds = selected.record.mailWorkspaceMailboxIds(selected.presentation.kind)
    if (selectedAccountIds.isEmpty() || selectedMailboxIds.isEmpty()) return listOf(selected)
    val siblings = filter { candidate ->
        candidate.presentation.kind == NativeMailboxItemKind.Message &&
            candidate.record.mailWorkspaceAccountIds(candidate.presentation.kind)
                .intersect(selectedAccountIds)
                .isNotEmpty() &&
            candidate.record.mailWorkspaceMailboxIds(candidate.presentation.kind)
                .intersect(selectedMailboxIds)
                .isNotEmpty()
    }
    return siblings
}

internal fun NativeRecord.mailWorkspaceAccountIds(kind: NativeMailboxItemKind): Set<String> = buildSet {
    mailWorkspaceRelationValue("accountid", "mailaccountid")?.let(::add)
    if (kind == NativeMailboxItemKind.Account) {
        add(id)
        mailWorkspaceRelationValue("databaseid", "id")?.let(::add)
    }
}

private fun NativeRecord.mailWorkspaceMailboxIds(kind: NativeMailboxItemKind): Set<String> = buildSet {
    mailWorkspaceRelationValue("mailboxid", "folderid", "mailfolderid")?.let(::add)
    if (kind == NativeMailboxItemKind.Folder) {
        add(id)
        mailWorkspaceRelationValue("databaseid", "id")?.let(::add)
    }
}

private fun NativeRecord.mailWorkspaceRelationValue(vararg names: String): String? {
    val normalizedNames = names.mapTo(hashSetOf()) { name -> name.mailSemanticKey() }
    return sequenceOf(values, displayValues, bindingContext)
        .flatMap { fields -> fields.entries.asSequence() }
        .firstOrNull { (key, value) ->
            key.mailSemanticKey() in normalizedNames && !value.isNullOrBlank()
        }
        ?.value
}

private fun NativeRecord.mailboxHierarchyDepth(): Int {
    val semanticValues = values.entries.associate { (key, value) -> key.mailSemanticKey() to value }
    val path = listOf("path", "mailboxpath", "fullpath", "displaypath")
        .firstNotNullOfOrNull { key -> semanticValues[key]?.takeIf { value -> !value.isNullOrBlank() } }
        ?: return if (
            listOf("parentid", "parentmailboxid", "parentfolderid")
                .any { key -> !semanticValues[key].isNullOrBlank() }
        ) {
            1
        } else {
            0
        }
    return path.split('/', '\\').count(String::isNotBlank).minus(1).coerceIn(0, 4)
}

private fun NativeMailWorkspaceItem.mailboxHierarchySortKey(): String {
    val semanticValues = record.values.entries.associate { (key, value) -> key.mailSemanticKey() to value }
    return listOf("path", "mailboxpath", "fullpath", "displaypath")
        .firstNotNullOfOrNull { key -> semanticValues[key]?.takeIf { value -> !value.isNullOrBlank() } }
        ?.lowercase()
        ?: presentation.title.lowercase()
}

private fun NativeMailWorkspaceItem.inboxScore(): Int {
    val semanticValues = record.values.entries.associate { (key, value) -> key.mailSemanticKey() to value }
    val role = listOf("specialuse", "specialrole", "role", "type")
        .firstNotNullOfOrNull { key -> semanticValues[key] }
        ?.mailSemanticKey()
    val title = presentation.title.mailSemanticKey()
    return when {
        role == "inbox" -> 100
        title == "inbox" -> 80
        title.endsWith("inbox") -> 60
        else -> 0
    }
}

private fun String.mailSemanticKey(): String = lowercase().filter(Char::isLetterOrDigit)

private fun String.mailSemanticWords(): List<String> {
    val separated = replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotBlank)
    return separated + separated.joinToString("")
}
