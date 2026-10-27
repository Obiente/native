package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestination
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationHost
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationModel
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionWorkspaceScaffold
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import dev.obiente.nextcloudnative.app.design.resolveNextcloudCollectionNavigationMode
import dev.obiente.nextcloudnative.nativeui.runtime.sanitizeNativeMailHtml
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Retains the account's News reading state outside the workspace composition, so switching to
 * another app and back keeps the selected view, open article, and loaded pages. Call it from the
 * account shell, not from the News route. Only the selected view survives process recreation:
 * the open article is not restored because a fresh Unread list no longer contains it once read.
 */
@Composable
internal fun rememberNewsWorkspaceController(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
): NewsWorkspaceController {
    val currentServices by rememberUpdatedState(services)
    val currentSession by rememberUpdatedState(session)
    var savedSelection by rememberSaveable(session.accountId) { mutableStateOf(NewsSelection.Unread.destinationId()) }
    val parent = rememberCoroutineScope()
    val scope = remember(session.accountId) {
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val controller = remember(scope) {
        NewsWorkspaceController(
            scope,
            execute = { request -> currentServices.executeNextcloudApi(currentSession, request) },
            initialSelection = newsSelectionForDestination(savedSelection) ?: NewsSelection.Unread,
        )
    }
    LaunchedEffect(controller) {
        snapshotFlow { controller.state.selection.destinationId() }.collect { savedSelection = it }
    }
    return controller
}

/**
 * Native News reader: subscriptions on the left (or in a sheet), the article list, and a reader.
 *
 * Wide windows keep the list and the open article side by side. Compact windows show one of them
 * and use system back to return from the reader to the list.
 */
@Composable
internal fun NewsWorkspaceScreen(
    services: NextcloudPlatformServices,
    controller: NewsWorkspaceController,
    onBack: () -> Unit,
) {
    LaunchedEffect(controller) { controller.start() }
    val state = controller.state
    // Owned here, above the list/reader switch, so returning from the compact reader keeps the position.
    val listState = key(state.selection) { rememberLazyListState() }
    PlatformBackHandler(enabled = state.openItemId != null, onBack = controller::closeArticle)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val destinations = remember(state.subscriptions) { newsDestinations(state.subscriptions) }
        val selectedId = state.selection.destinationId().takeIf { id -> destinations.any { it.id == id } }
        val model = remember(destinations, selectedId) {
            NextcloudCollectionNavigationModel.create(destinations, selectedId)
        }
        val host = if (LocalNextcloudWorkspaceCapabilities.current.isDesktop) {
            NextcloudCollectionNavigationHost.Desktop
        } else {
            NextcloudCollectionNavigationHost.AdaptiveAndroid
        }
        val mode = resolveNextcloudCollectionNavigationMode(host, maxWidth.value.toInt(), destinations.size)
        val twoPane = maxWidth >= NEWS_TWO_PANE_MIN_WIDTH
        val openItem = state.openItem
        NextcloudCollectionWorkspaceScaffold(
            model = model,
            mode = mode,
            workspaceLabel = "News",
            contentTitle = state.selection.title(state.subscriptions),
            contentSubtitle = state.selection.subtitle(state.subscriptions),
            onBack = if (openItem != null && !twoPane) controller::closeArticle else onBack,
            hasHierarchyBack = openItem != null && !twoPane,
            onDestinationSelected = { destination ->
                newsSelectionForDestination(destination.id)?.let(controller::select)
            },
            destinationIcon = ::newsDestinationIcon,
            headerActions = {
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.padding(NextcloudSpacing.Medium).size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = controller::refresh) {
                        Icon(NextcloudIcons.Refresh, contentDescription = "Refresh News")
                    }
                }
            },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                state.stateChangeNotice?.let { notice ->
                    NewsNotice(notice, actionLabel = "Dismiss", onAction = controller::dismissNotice)
                }
                state.subscriptionsFailure?.takeIf { state.subscriptions != null }?.let { failure ->
                    NewsNotice("${failure.title}. Showing the last loaded subscriptions.")
                }
                (state.articles as? NewsArticlesState.Loaded)?.refreshFailure?.let { failure ->
                    NewsNotice(
                        "${failure.title}. Showing the articles loaded earlier.",
                        actionLabel = "Try again",
                        onAction = controller::refresh.takeIf { failure.canRetry },
                    )
                }
                when {
                    state.subscriptions == null && state.subscriptionsFailure != null ->
                        NewsFailurePane(state.subscriptionsFailure, onRetry = controller::refresh)
                    twoPane -> Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(0.42f).fillMaxHeight()) {
                            NewsArticleList(state, listState, controller)
                        }
                        VerticalDivider()
                        Box(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
                            if (openItem != null) {
                                NewsArticleReader(openItem, state, controller, services)
                            } else {
                                NewsReaderPlaceholder(state)
                            }
                        }
                    }
                    openItem != null -> NewsArticleReader(openItem, state, controller, services)
                    else -> NewsArticleList(state, listState, controller)
                }
            }
        }
    }
}

@Composable
private fun NewsArticleList(state: NewsWorkspaceUiState, listState: LazyListState, controller: NewsWorkspaceController) {
    when (val articles = state.articles) {
        NewsArticlesState.Loading -> LoadingMessage("Loading articles...")
        is NewsArticlesState.Failed -> NewsFailurePane(articles.failure, onRetry = controller::refresh)
        is NewsArticlesState.Loaded -> {
            if (articles.items.isEmpty()) {
                NewsEmptyState(state)
                return
            }
            LaunchedEffect(listState, articles.endReached) {
                if (articles.endReached) return@LaunchedEffect
                snapshotFlow {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= listState.layoutInfo.totalItemsCount - NEWS_PREFETCH_DISTANCE
                }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) controller.loadMore() }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(articles.items, key = NewsItem::id) { item ->
                    NewsArticleRow(
                        item = item,
                        feed = state.subscriptions?.feed(item.feedId),
                        selected = item.id == state.openItemId,
                        pending = item.id in state.pendingItemIds,
                        showFeed = state.selection !is NewsSelection.Feed,
                        controller = controller,
                    )
                    HorizontalDivider()
                }
                item(key = "news-list-footer") {
                    NewsListFooter(articles, onRetry = controller::loadMore)
                }
            }
        }
    }
}

@Composable
private fun NewsArticleRow(
    item: NewsItem,
    feed: NewsFeed?,
    selected: Boolean,
    pending: Boolean,
    showFeed: Boolean,
    controller: NewsWorkspaceController,
) {
    var menuOpen by remember(item.id) { mutableStateOf(false) }
    val actions = newsArticleActions(item, pending, controller)
    val meta = listOfNotNull(
        feed?.title?.takeIf { showFeed },
        item.publishedAtEpochSeconds?.let { newsPublishedLabel(it, Clock.System.now().epochSeconds) },
    ).joinToString(" - ")
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .nextcloudCardInteractions(
                    onOpen = { controller.open(item) },
                    onShowActions = { menuOpen = true },
                    openLabel = "Read article",
                    actionsLabel = "Article actions",
                )
                .semantics(mergeDescendants = true) {
                    stateDescription = listOfNotNull(
                        if (item.unread) "Unread" else "Read",
                        "Starred".takeIf { item.starred },
                    ).joinToString(", ")
                }
                .padding(start = NextcloudSpacing.Large, top = NextcloudSpacing.Medium, bottom = NextcloudSpacing.Medium),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.padding(top = 7.dp, end = NextcloudSpacing.Medium).size(8.dp)) {
                if (item.unread) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (item.unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (item.unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.excerpt.isNotEmpty()) {
                    Text(
                        item.excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.starred) {
                Icon(
                    Icons.Outlined.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = NextcloudSpacing.Small).size(18.dp),
                )
            }
            NextcloudCardOverflow(
                itemLabel = item.title,
                actions = actions,
                expanded = menuOpen,
                onExpandedChange = { menuOpen = it },
            )
        }
    }
}

private fun newsArticleActions(
    item: NewsItem,
    pending: Boolean,
    controller: NewsWorkspaceController,
): List<NextcloudCardAction> = listOf(
    NextcloudCardAction(
        label = if (item.unread) "Mark as read" else "Mark as unread",
        enabled = !pending,
        onClick = { controller.toggleRead(item) },
    ),
    NextcloudCardAction(
        label = if (item.starred) "Remove star" else "Star",
        enabled = !pending,
        onClick = { controller.toggleStar(item) },
    ),
)

@Composable
private fun NewsArticleReader(
    item: NewsItem,
    state: NewsWorkspaceUiState,
    controller: NewsWorkspaceController,
    services: NextcloudPlatformServices,
) {
    val feed = state.subscriptions?.feed(item.feedId)
    val pending = item.id in state.pendingItemIds
    val sanitizedBody = remember(item.id, item.bodyHtml) {
        item.bodyHtml.takeIf(String::isNotBlank)?.let(::sanitizeNativeMailHtml)
    }
    val richText = rememberRichTextState()
    LaunchedEffect(sanitizedBody) {
        if (!sanitizedBody.isNullOrBlank()) richText.setHtml(sanitizedBody)
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(item.id) { scrollState.scrollTo(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Column(modifier = Modifier.widthIn(max = NEWS_READER_MAX_WIDTH), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            feed?.let {
                Text(it.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                item.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            val byline = listOfNotNull(
                item.author,
                item.publishedAtEpochSeconds?.let { newsPublishedLabel(it, Clock.System.now().epochSeconds) },
            ).joinToString(" - ")
            if (byline.isNotEmpty()) {
                Text(byline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
                NewsReaderAction(
                    icon = if (item.starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    label = if (item.starred) "Starred" else "Star",
                    description = if (item.starred) "Remove star" else "Star article",
                    enabled = !pending,
                    onClick = { controller.toggleStar(item) },
                )
                NewsReaderAction(
                    icon = Icons.Outlined.MarkEmailUnread,
                    label = if (item.unread) "Mark read" else "Keep unread",
                    description = if (item.unread) "Mark as read" else "Mark as unread",
                    enabled = !pending,
                    onClick = { controller.toggleRead(item) },
                )
                item.url?.let { url ->
                    NewsReaderAction(
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        label = "Original",
                        description = "Open the original article in the browser",
                        enabled = true,
                        onClick = { services.openExternalUrl(url) },
                    )
                }
            }
            HorizontalDivider()
            CompositionLocalProvider(
                LocalLayoutDirection provides if (item.rtl) LayoutDirection.Rtl else LocalLayoutDirection.current,
            ) {
                if (!sanitizedBody.isNullOrBlank()) {
                    RichText(state = richText, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(
                        "This article has no text in the feed. Open the original to read it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item.enclosureLink?.let { link ->
                OutlinedButton(onClick = { services.openExternalUrl(link) }) {
                    Text(newsEnclosureLabel(item.enclosureMime))
                }
            }
            Text(
                "Images and embedded media are not loaded here, so opening an article does not contact the publisher.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NewsReaderAction(
    icon: ImageVector,
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = NextcloudSpacing.XSmall))
    }
}

@Composable
private fun NewsReaderPlaceholder(state: NewsWorkspaceUiState) {
    val loaded = state.articles as? NewsArticlesState.Loaded
    EmptyMessage(
        if (loaded?.items?.isNotEmpty() == true) "Select an article to read it here." else "",
    )
}

@Composable
private fun NewsEmptyState(state: NewsWorkspaceUiState) {
    val subscriptions = state.subscriptions
    val message = when {
        subscriptions != null && subscriptions.feeds.isEmpty() ->
            "You have no feeds yet. Subscribe to feeds in the News app on the web; they appear here automatically."
        state.selection == NewsSelection.Unread -> "You're all caught up. There are no unread articles."
        state.selection == NewsSelection.Starred -> "No starred articles. Star an article to keep it here."
        else -> "There are no articles here yet."
    }
    EmptyMessage(message)
}

@Composable
private fun NewsListFooter(articles: NewsArticlesState.Loaded, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large), contentAlignment = Alignment.Center) {
        when {
            articles.loadingMore -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            articles.pageFailure != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(articles.pageFailure.title, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            articles.endReached -> Text(
                "End of list",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NewsFailurePane(failure: NewsFailure, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(NextcloudIcons.Error, contentDescription = null, modifier = Modifier.size(36.dp))
        Text(failure.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = NextcloudSpacing.Medium))
        Text(
            failure.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = NextcloudSpacing.Small),
        )
        if (failure.canRetry) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = NextcloudSpacing.Large)) { Text("Try again") }
        }
    }
}

@Composable
private fun NewsNotice(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private val NEWS_TWO_PANE_MIN_WIDTH = 840.dp
private val NEWS_READER_MAX_WIDTH = 760.dp
private const val NEWS_PREFETCH_DISTANCE = 8
