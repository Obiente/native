package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import dev.obiente.nextcloudnative.app.PlatformBackHandler
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * Feed reader for any app whose contract exposes articles. The selected filter and feed survive
 * recreation; the open article does not, because opening it may have changed its read state.
 */
@Composable
internal fun NativeFeedReaderWorkspace(
    schema: NativeAppSchema,
    plan: NativeFeedReaderPlan,
    actionExecutor: NativeActionExecutor,
    onStateChanged: ((ActionSpec) -> Unit)?,
    onOpenLink: ((String) -> Unit)?,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
    modifier: Modifier = Modifier,
    authority: NativeRuleAuthority? = plan.authority,
) {
    var savedFilter by rememberSaveable(schema.app.id) { mutableStateOf(plan.filters.first()) }
    var savedFeedId by rememberSaveable(schema.app.id) { mutableStateOf<String?>(null) }
    // A filter or feed that the current data no longer supports falls back without a state write.
    val filter = savedFilter.takeIf { it in plan.filters } ?: plan.filters.first()
    val feedId = savedFeedId?.takeIf { id -> plan.feeds.any { it.record.id == id } }
    var openKey by remember(schema.app.id, actionExecutor) { mutableStateOf<String?>(null) }
    // Local read and star state belongs to the app, so loading another page keeps it.
    val session = remember(schema.app.id, actionExecutor) { NativeFeedReaderSession() }
    val activeSession by rememberUpdatedState(session)
    LaunchedEffect(session, plan.articles, authority?.state?.generation) { session.reconcile(plan.articles, authority?.state?.generation ?: 0L) }
    val overrides = session.overrides
    val actionError = session.actionError
    val scope = rememberCoroutineScope()
    var unresolved by remember(schema.app.id, actionExecutor) { mutableStateOf<Pair<NativeFeedItemActionPlan, NativeFeedArticle>?>(null) }
    var recoveryMessage by remember(schema.app.id, actionExecutor) { mutableStateOf<String?>(null) }
    val visible = plan.visible(filter, feedId, overrides)
    val open = (openKey ?: plan.selectedArticle?.key)
        ?.let { key -> plan.articles.firstOrNull { it.key == key } }
        ?.withOverride(overrides[openKey ?: plan.selectedArticle?.key])

    fun apply(change: NativeFeedItemActionPlan, article: NativeFeedArticle, repeat: Boolean = false) {
        val token = session.begin(article, change) ?: return
        scope.launch {
            val result = try { runCatchingUnlessCancelled { actionExecutor.execute(change.request().copy(
                ruleRecovery = if (repeat) NativeRuleRecoveryIntent.RepeatSameOperation else null,
            )) }.getOrElse { failure ->
                NativeActionExecutionResult.Failure(failure.message.orEmpty(), NativeActionFailureOutcome.Unknown)
            } } catch (cancelled: CancellationException) {
                session.failed(article, "The change still needs verification.", token)
                throw cancelled
            }
            if (activeSession !== session) return@launch
            when (result) {
                is NativeActionExecutionResult.Success -> {
                    if (session.succeeded(article, token) { onStateChanged?.invoke(change.action) }) { unresolved = null; recoveryMessage = null }
                }
                is NativeActionExecutionResult.Failure -> {
                    // A rejected change is rolled back for this article alone. An unknown result is
                    // also rolled back and the list is read again, so the server state wins.
                    if (result.outcome == NativeActionFailureOutcome.Rejected) {
                        session.failed(article, result.message.ifBlank { "The app did not accept this change." }, token)
                    } else {
                        val repeatable = session.checkUnresolved(article, result.message, token) {
                            (actionExecutor as? NativeRuleRecoveryExecutor)?.canRepeatRuleOperation(change.request()) == true
                        }
                        if (activeSession !== session) return@launch
                        if (repeatable) unresolved = change to article
                        onStateChanged?.invoke(change.action)
                    }
                }
            }
        }
    }

    fun openArticle(article: NativeFeedArticle) {
        openKey = article.key
        // Opening marks only this article read, through a route bound to its identity.
        if (article.unread == true) {
            nativeFeedItemActions(schema, article, authority).readState
                ?.takeIf { it.kind == NativeFeedItemActionKind.MarkRead }
                ?.let { apply(it, article) }
        }
    }

    Column(modifier.fillMaxSize()) {
        if (actionExecutor is NativeRuleRecoveryExecutor) {
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                TextButton(onClick = { scope.launch {
                    val result = actionExecutor.recoverRuleOperations()
                    if (activeSession !== session) return@launch
                    recoveryMessage = when (result) {
                        is NativeActionExecutionResult.Success -> {
                            unresolved = null
                            session.recoveryVerified()
                            authority?.sourceActionId?.let(schema::action)?.let { onStateChanged?.invoke(it) }
                            result.message
                        }
                        is NativeActionExecutionResult.Failure -> result.message
                    }
                } }) { Text("Check earlier changes") }
                unresolved?.let { (change, article) ->
                    val refreshed = authority?.let { current -> planNativePatternRule(schema, change.action.id, article.record.id, current, change.request().values) }
                    if (refreshed != null) TextButton(onClick = { apply(change.copy(rulePlan = refreshed), article, true) }) { Text("Repeat same change") }
                }
            }
            recoveryMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val width = maxWidth
        val list: @Composable (Modifier) -> Unit = { modifier ->
            NativeFeedArticleList(plan, visible, open?.key, filter, feedId, ::openArticle, onLoadMore, loadingMore, loadMoreError, modifier)
        }
        val reader: @Composable (Modifier) -> Unit = { modifier ->
            if (open == null) {
                NativeFeedPlaceholder("Choose an article to read it here.", modifier)
            } else {
                NativeFeedArticleReader(schema, plan, open, actionError, { change, article -> apply(change, article) }, onOpenLink, modifier, authority)
            }
        }
        val rail: @Composable (Modifier) -> Unit = { modifier ->
            NativeFeedSourceRail(plan, filter, feedId, { savedFilter = it; savedFeedId = null }, { savedFeedId = it; savedFilter = NativeFeedFilter.All }, modifier)
        }
        when {
            width >= 980.dp -> Row(Modifier.fillMaxSize()) {
                rail(Modifier.width(248.dp).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                list(Modifier.weight(0.42f).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                reader(Modifier.weight(0.58f).fillMaxHeight())
            }
            width >= 680.dp -> Row(Modifier.fillMaxSize()) {
                rail(Modifier.width(220.dp).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (open != null) {
                        PlatformBackHandler(enabled = true) { openKey = null }
                        reader(Modifier.fillMaxSize())
                    } else {
                        list(Modifier.fillMaxSize())
                    }
                }
            }
            open != null -> {
                PlatformBackHandler(enabled = true) { openKey = null }
                Column(Modifier.fillMaxSize()) {
                    TextButton(onClick = { openKey = null }, modifier = Modifier.padding(start = NextcloudSpacing.Small)) {
                        Icon(NextcloudIcons.Back, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Articles", modifier = Modifier.padding(start = NextcloudSpacing.XSmall))
                    }
                    reader(Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                NativeFeedCompactFilters(plan, filter, feedId, { savedFilter = it; savedFeedId = null }, { savedFeedId = it; savedFilter = NativeFeedFilter.All })
                list(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    }
}

@Composable
private fun NativeFeedSourceRail(
    plan: NativeFeedReaderPlan,
    filter: NativeFeedFilter,
    feedId: String?,
    onFilter: (NativeFeedFilter) -> Unit,
    onFeed: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier.padding(vertical = NextcloudSpacing.Small)) {
        items(plan.filters, key = { "filter:${it.name}" }) { option ->
            NativeFeedRailRow(option.label, null, selected = feedId == null && filter == option) { onFilter(option) }
        }
        if (plan.feeds.isNotEmpty()) {
            item(key = "feeds-header") {
                Text(
                    "Feeds",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
                )
            }
        }
        val grouped = plan.feeds.groupBy { feed -> plan.folders.firstOrNull { it.record.id == feed.folderId } }
        grouped.forEach { (folder, feeds) ->
            if (folder != null) {
                item(key = "folder:${folder.record.id}") {
                    Text(
                        folder.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.XSmall),
                    )
                }
            }
            items(feeds, key = { "feed:${it.record.id}" }) { feed ->
                NativeFeedRailRow(feed.title, feed.unreadCount, selected = feedId == feed.record.id) { onFeed(feed.record.id) }
            }
        }
    }
}

@Composable
private fun NativeFeedRailRow(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .semantics { this.selected = selected }
            .clickable(onClick = onClick)
            .padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (count != null && count > 0) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NativeFeedCompactFilters(
    plan: NativeFeedReaderPlan,
    filter: NativeFeedFilter,
    feedId: String?,
    onFilter: (NativeFeedFilter) -> Unit,
    onFeed: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        plan.filters.forEach { option ->
            FilterChip(selected = feedId == null && filter == option, onClick = { onFilter(option) }, label = { Text(option.label) })
        }
        plan.feeds.forEach { feed ->
            FilterChip(selected = feedId == feed.record.id, onClick = { onFeed(feed.record.id) }, label = { Text(feed.title, maxLines = 1) })
        }
    }
}

@Composable
private fun NativeFeedArticleList(
    plan: NativeFeedReaderPlan,
    articles: List<NativeFeedArticle>,
    openKey: String?,
    filter: NativeFeedFilter,
    feedId: String?,
    onOpen: (NativeFeedArticle) -> Unit,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val autoLoad = remember(filter, feedId) { NativeFeedAutoLoad() }
    val loadedCount = plan.articles.size
    if (articles.isEmpty()) {
        NativeFeedPlaceholder(
            when (filter) {
                NativeFeedFilter.Unread -> "No unread articles in the loaded pages."
                NativeFeedFilter.Starred -> "No starred articles in the loaded pages."
                NativeFeedFilter.All -> "No articles here yet."
            },
            modifier,
            onLoadMore.takeIf { !loadingMore }?.let { "Load more" to it },
        )
        return
    }
    LazyColumn(modifier, state = listState) {
        nativeFeedFilterCaption(plan, filter, feedId)?.let { caption ->
            item(key = "filter-caption") {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.XSmall),
                )
            }
        }
        items(articles, key = NativeFeedArticle::key) { article ->
            val selected = article.key == openKey
            Column(
                Modifier.fillMaxWidth()
                    .semantics {
                        this.selected = selected
                        stateDescription = listOfNotNull(
                            "Unread".takeIf { article.unread == true },
                            "Starred".takeIf { article.starred == true },
                        ).joinToString(", ")
                    }
                    .clickable { onOpen(article) }
                    .padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        article.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (article.unread == true) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (article.starred == true) {
                        Icon(NextcloudIcons.Favorite, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                val meta = listOfNotNull(plan.feedTitle(article), article.author, article.publishedLabel).joinToString(" - ")
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (onLoadMore != null || loadingMore || loadMoreError != null) {
            item(key = "load-more") {
                Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Large), horizontalAlignment = Alignment.CenterHorizontally) {
                    loadMoreError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (loadingMore) {
                        Text("Loading more articles...", style = MaterialTheme.typography.bodySmall)
                    } else if (onLoadMore != null) {
                        // Keyed by loaded pages, not visible rows, so a filter that hides a new page
                        // still advances; NativeFeedAutoLoad stops after repeated empty pages.
                        LaunchedEffect(loadedCount, loadMoreError) {
                            if (loadMoreError == null && autoLoad.shouldLoad(loadedCount, articles.size)) onLoadMore()
                        }
                        TextButton(onClick = onLoadMore) { Text(if (loadMoreError != null) "Try again" else "Load more") }
                    }
                }
            }
        }
    }
}

/** One line saying that a filter covers only the loaded pages, or null when nothing is filtered. */
internal fun nativeFeedFilterCaption(plan: NativeFeedReaderPlan, filter: NativeFeedFilter, feedId: String?): String? {
    val feed = feedId?.let { id -> plan.feeds.firstOrNull { it.record.id == id }?.title }
    val subject = when {
        feed != null -> "articles from $feed"
        filter == NativeFeedFilter.Unread -> "unread articles"
        filter == NativeFeedFilter.Starred -> "starred articles"
        else -> return null
    }
    val loaded = plan.articles.size
    return "Showing $subject among the $loaded loaded ${if (loaded == 1) "article" else "articles"}."
}

@OptIn(ExperimentalRichTextApi::class)
@Composable
private fun NativeFeedArticleReader(
    schema: NativeAppSchema,
    plan: NativeFeedReaderPlan,
    article: NativeFeedArticle,
    actionError: String?,
    onApply: (NativeFeedItemActionPlan, NativeFeedArticle) -> Unit,
    onOpenLink: ((String) -> Unit)?,
    modifier: Modifier,
    authority: NativeRuleAuthority? = null,
) {
    val actions = remember(schema, article, authority) { nativeFeedItemActions(schema, article, authority) }
    val html = remember(article.body, article.html) { article.body?.takeIf { article.html }?.let(::sanitizeNativeMailHtml) }
    val richText = rememberRichTextState()
    LaunchedEffect(html) { if (!html.isNullOrBlank()) richText.setHtml(html) }
    // Each article starts at the top; the scroll state belongs to the open article.
    val scroll = remember(article.key) { androidx.compose.foundation.ScrollState(0) }
    Column(
        modifier.verticalScroll(scroll).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Text(article.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        val meta = listOfNotNull(plan.feedTitle(article), article.author, article.publishedLabel).joinToString(" - ")
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            actions.all.forEach { change ->
                OutlinedButton(onClick = { onApply(change, article) }) { Text(change.kind.label) }
            }
            if (article.link != null && onOpenLink != null) {
                TextButton(onClick = { onOpenLink(article.link) }) { Text("Open original") }
            }
        }
        if (actions.all.isEmpty() && (article.unread != null || article.starred != null)) {
            Text(
                "Reading view. This app's contract does not describe read or star changes for articles.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        SelectionContainer {
            if (!html.isNullOrBlank()) {
                RichText(state = richText, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(article.body?.trim()?.takeIf(String::isNotBlank) ?: "This article has no readable text.", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun NativeFeedPlaceholder(message: String, modifier: Modifier, action: Pair<String, () -> Unit>? = null) {
    Column(
        modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(NextcloudIcons.Feed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = NextcloudSpacing.Small))
        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
    }
}
