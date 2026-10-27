package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class NewsFailureKind {
    Offline,
    Unauthorized,
    Forbidden,
    Unavailable,
    UnsupportedVersion,
    Throttled,
    Server,
    Malformed,
}

internal data class NewsFailure(val kind: NewsFailureKind) {
    val title: String get() = when (kind) {
        NewsFailureKind.Offline -> "Can't reach your server"
        NewsFailureKind.Unauthorized -> "Sign-in expired"
        NewsFailureKind.Forbidden -> "News is not available to this account"
        NewsFailureKind.Unavailable -> "News is not responding"
        NewsFailureKind.UnsupportedVersion -> "This News version is not supported"
        NewsFailureKind.Throttled -> "The server asked to slow down"
        NewsFailureKind.Server -> "The server could not load News"
        NewsFailureKind.Malformed -> "News sent an unexpected response"
    }

    val message: String get() = when (kind) {
        NewsFailureKind.Offline -> "Check your connection, then try again. Articles already shown stay available."
        NewsFailureKind.Unauthorized -> "Sign in to this account again to keep reading."
        NewsFailureKind.Forbidden -> "An administrator may have limited the News app to other groups."
        NewsFailureKind.Unavailable -> "The News app may be disabled or still updating on the server."
        NewsFailureKind.UnsupportedVersion ->
            "The installed News app does not offer the v1-3 reading API. Update News on the server, or read it in a browser."
        NewsFailureKind.Throttled -> "Wait a moment before trying again."
        NewsFailureKind.Server -> "Try again later. If it keeps happening, ask your administrator to check the News app."
        NewsFailureKind.Malformed -> "Try again. If it keeps happening, the News app may need an update."
    }

    val canRetry: Boolean get() = kind != NewsFailureKind.UnsupportedVersion
}

internal fun newsFailureForStatus(status: Int): NewsFailure? = when (status) {
    in 200..299 -> null
    401 -> NewsFailure(NewsFailureKind.Unauthorized)
    403 -> NewsFailure(NewsFailureKind.Forbidden)
    404, 410 -> NewsFailure(NewsFailureKind.Unavailable)
    429 -> NewsFailure(NewsFailureKind.Throttled)
    503 -> NewsFailure(NewsFailureKind.Unavailable)
    else -> NewsFailure(NewsFailureKind.Server)
}

internal sealed interface NewsArticlesState {
    data object Loading : NewsArticlesState
    data class Loaded(
        val items: List<NewsItem>,
        val endReached: Boolean,
        val loadingMore: Boolean = false,
        val pageFailure: NewsFailure? = null,
    ) : NewsArticlesState
    data class Failed(val failure: NewsFailure) : NewsArticlesState
}

internal data class NewsWorkspaceUiState(
    val subscriptions: NewsSubscriptions? = null,
    val subscriptionsFailure: NewsFailure? = null,
    val refreshing: Boolean = true,
    val selection: NewsSelection = NewsSelection.Unread,
    val articles: NewsArticlesState = NewsArticlesState.Loading,
    val openItemId: Long? = null,
    val pendingItemIds: Set<Long> = emptySet(),
    val stateChangeNotice: String? = null,
) {
    val openItem: NewsItem? get() = openItemId?.let { id ->
        (articles as? NewsArticlesState.Loaded)?.items?.firstOrNull { it.id == id }
    }
}

/**
 * Owns News reading state for one account.
 *
 * Every load is fenced by a generation so a slow response for a previous selection, refresh, or
 * page cannot overwrite newer state. Article state changes are idempotent News operations; an
 * optimistic change is rolled back only for the affected article and field when the server does
 * not confirm it. While a change is pending, fetched pages show the requested state, because a
 * read that raced the change may still carry the old server state.
 */
internal class NewsWorkspaceController(
    private val scope: CoroutineScope,
    private val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    initialSelection: NewsSelection = NewsSelection.Unread,
) {
    var state by mutableStateOf(NewsWorkspaceUiState(selection = initialSelection))
        private set

    private val pendingChanges = mutableMapOf<Long, NewsItemStateChange>()

    private var subscriptionGeneration = 0L
    private var articleGeneration = 0L
    private var subscriptionJob: Job? = null
    private var articleJob: Job? = null

    fun start() {
        if (subscriptionJob == null) refresh()
    }

    fun refresh() {
        loadSubscriptions()
        loadArticles(state.selection, keepVisibleItems = true)
    }

    fun select(selection: NewsSelection) {
        if (selection == state.selection && state.articles !is NewsArticlesState.Failed) {
            state = state.copy(openItemId = null)
            return
        }
        state = state.copy(selection = selection, openItemId = null, stateChangeNotice = null)
        loadArticles(selection, keepVisibleItems = false)
    }

    fun loadMore() {
        val loaded = state.articles as? NewsArticlesState.Loaded ?: return
        if (loaded.endReached || loaded.loadingMore || articleJob?.isActive == true) return
        val cursor = loaded.items.minOfOrNull(NewsItem::id) ?: return
        val generation = articleGeneration
        val selection = state.selection
        state = state.copy(articles = loaded.copy(loadingMore = true, pageFailure = null))
        articleJob = scope.launch {
            val result = readItems(selection, cursor)
            if (generation != articleGeneration || selection != state.selection) return@launch
            val current = state.articles as? NewsArticlesState.Loaded ?: return@launch
            state = state.copy(
                articles = when (result) {
                    is NewsRead.Success -> {
                        val known = current.items.mapTo(HashSet(), NewsItem::id)
                        val next = result.value.filterNot { it.id in known }.withPendingChanges()
                        current.copy(
                            items = current.items + next,
                            endReached = result.value.size < NEWS_ITEM_PAGE_SIZE || next.isEmpty(),
                            loadingMore = false,
                        )
                    }
                    is NewsRead.Failure -> current.copy(loadingMore = false, pageFailure = result.failure)
                },
            )
        }
    }

    /** Opens an article and marks it read, as the News web interface does. */
    fun open(item: NewsItem) {
        state = state.copy(openItemId = item.id, stateChangeNotice = null)
        if (item.unread) change(item, NewsItemStateChange.Read)
    }

    fun closeArticle() {
        state = state.copy(openItemId = null)
    }

    fun toggleRead(item: NewsItem) =
        change(item, if (item.unread) NewsItemStateChange.Read else NewsItemStateChange.Unread)

    fun toggleStar(item: NewsItem) =
        change(item, if (item.starred) NewsItemStateChange.Unstar else NewsItemStateChange.Star)

    fun dismissNotice() {
        state = state.copy(stateChangeNotice = null)
    }

    private fun change(item: NewsItem, change: NewsItemStateChange) {
        if (item.id in state.pendingItemIds) return
        applyItemChange(item.id, item.feedId, change)
        pendingChanges[item.id] = change
        state = state.copy(pendingItemIds = state.pendingItemIds + item.id)
        scope.launch {
            val outcome = try {
                val response = execute(newsItemStateRequest(item.id, change))
                when {
                    response.status in 200..299 -> null
                    response.status == 404 -> "This article no longer exists on the server. Refresh to update the list."
                    else -> newsFailureForStatus(response.status)?.let { failure ->
                        "${change.failureLabel()} ${failure.message}"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The request may or may not have reached the server. Both outcomes are safe because
                // the operation is idempotent, but the local copy must not claim it succeeded.
                "${change.failureLabel()} The server did not confirm the change."
            }
            pendingChanges -= item.id
            state = state.copy(pendingItemIds = state.pendingItemIds - item.id)
            if (outcome == null) {
                // Idempotent: corrects a copy that a page fetched before this change replaced.
                applyItemChange(item.id, item.feedId, change)
            } else {
                applyItemChange(item.id, item.feedId, change.inverse())
                state = state.copy(stateChangeNotice = outcome)
            }
        }
    }

    private fun applyItemChange(itemId: Long, feedId: Long, change: NewsItemStateChange) {
        val loaded = state.articles as? NewsArticlesState.Loaded
        val before = loaded?.items?.firstOrNull { it.id == itemId }
        val updatedArticles = loaded?.copy(
            items = loaded.items.map { if (it.id == itemId) it.applying(change) else it },
        ) ?: state.articles
        val unreadDelta = when {
            before == null -> 0
            change == NewsItemStateChange.Read && before.unread -> -1
            change == NewsItemStateChange.Unread && !before.unread -> 1
            else -> 0
        }
        val starredDelta = when {
            before == null -> 0
            change == NewsItemStateChange.Star && !before.starred -> 1
            change == NewsItemStateChange.Unstar && before.starred -> -1
            else -> 0
        }
        val subscriptions = state.subscriptions?.let { current ->
            current.copy(
                feeds = current.feeds.map { feed ->
                    if (feed.id == feedId) feed.copy(unreadCount = (feed.unreadCount + unreadDelta).coerceAtLeast(0)) else feed
                },
                starredCount = (current.starredCount + starredDelta).coerceAtLeast(0),
            )
        }
        state = state.copy(articles = updatedArticles, subscriptions = subscriptions)
    }

    private fun loadSubscriptions() {
        val generation = ++subscriptionGeneration
        subscriptionJob?.cancel()
        state = state.copy(refreshing = true)
        subscriptionJob = scope.launch {
            val result = readSubscriptions()
            if (generation != subscriptionGeneration) return@launch
            when (result) {
                is NewsRead.Success -> {
                    val selection = state.selection.takeIfStillPresent(result.value)
                    val selectionRemoved = selection != state.selection
                    state = state.copy(
                        subscriptions = result.value,
                        subscriptionsFailure = null,
                        refreshing = false,
                        selection = selection,
                        openItemId = state.openItemId.takeUnless { selectionRemoved },
                    )
                    // The removed feed or folder's pending page must not fill the fallback view.
                    if (selectionRemoved) loadArticles(selection, keepVisibleItems = false)
                }
                is NewsRead.Failure -> state = state.copy(subscriptionsFailure = result.failure, refreshing = false)
            }
        }
    }

    private fun loadArticles(selection: NewsSelection, keepVisibleItems: Boolean) {
        val generation = ++articleGeneration
        articleJob?.cancel()
        val previous = state.articles as? NewsArticlesState.Loaded
        if (!keepVisibleItems || previous == null) state = state.copy(articles = NewsArticlesState.Loading)
        articleJob = scope.launch {
            val result = readItems(selection, olderThanItemId = null)
            if (generation != articleGeneration) return@launch
            state = state.copy(
                articles = when (result) {
                    is NewsRead.Success -> NewsArticlesState.Loaded(
                        items = result.value.withPendingChanges(),
                        endReached = result.value.size < NEWS_ITEM_PAGE_SIZE,
                    )
                    is NewsRead.Failure -> if (keepVisibleItems && previous != null) {
                        previous.copy(pageFailure = result.failure, loadingMore = false)
                    } else {
                        NewsArticlesState.Failed(result.failure)
                    }
                },
            )
        }
    }

    private fun List<NewsItem>.withPendingChanges(): List<NewsItem> =
        if (pendingChanges.isEmpty()) this else map { item -> pendingChanges[item.id]?.let(item::applying) ?: item }

    private suspend fun readSubscriptions(): NewsRead<NewsSubscriptions> {
        val levels = read(newsApiLevelsRequest(), ::parseNewsApiLevels)
        if (levels is NewsRead.Success && "v1-3" !in levels.value) {
            return NewsRead.Failure(NewsFailure(NewsFailureKind.UnsupportedVersion))
        }
        if (levels is NewsRead.Failure && levels.failure.kind != NewsFailureKind.Unavailable) return levels
        val folders = read(newsFoldersRequest(), ::parseNewsFolders)
        if (folders is NewsRead.Failure) {
            return if (levels is NewsRead.Failure) NewsRead.Failure(NewsFailure(NewsFailureKind.UnsupportedVersion)) else folders
        }
        val feeds = read(newsFeedsRequest(), ::parseNewsFeeds)
        if (feeds is NewsRead.Failure) return feeds
        val (feedList, starredCount) = (feeds as NewsRead.Success).value
        return NewsRead.Success(
            NewsSubscriptions(
                folders = (folders as NewsRead.Success).value.sortedBy { it.name.lowercase() },
                feeds = feedList.sortedWith(compareByDescending<NewsFeed> { it.pinned }.thenBy { it.title.lowercase() }),
                starredCount = starredCount,
            ),
        )
    }

    private suspend fun readItems(selection: NewsSelection, olderThanItemId: Long?): NewsRead<List<NewsItem>> =
        read(newsItemsRequest(selection, olderThanItemId), ::parseNewsItems)

    private suspend fun <T> read(request: NextcloudApiRequest, parse: (String) -> T): NewsRead<T> = try {
        val response = execute(request)
        newsFailureForStatus(response.status)?.let { NewsRead.Failure(it) }
            ?: NewsRead.Success(withContext(parseDispatcher) { parse(response.body.decodeToString()) })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: NewsProtocolException) {
        NewsRead.Failure(NewsFailure(NewsFailureKind.Malformed))
    } catch (_: Exception) {
        // Transport boundary: platform services throw I/O and TLS exceptions here.
        NewsRead.Failure(NewsFailure(NewsFailureKind.Offline))
    }
}

private sealed interface NewsRead<out T> {
    data class Success<T>(val value: T) : NewsRead<T>
    data class Failure(val failure: NewsFailure) : NewsRead<Nothing>
}

private fun NewsSelection.takeIfStillPresent(subscriptions: NewsSubscriptions): NewsSelection = when (this) {
    is NewsSelection.Feed -> if (subscriptions.feed(feedId) != null) this else NewsSelection.Unread
    is NewsSelection.Folder -> if (subscriptions.folder(folderId) != null) this else NewsSelection.Unread
    else -> this
}

private fun NewsItemStateChange.inverse(): NewsItemStateChange = when (this) {
    NewsItemStateChange.Read -> NewsItemStateChange.Unread
    NewsItemStateChange.Unread -> NewsItemStateChange.Read
    NewsItemStateChange.Star -> NewsItemStateChange.Unstar
    NewsItemStateChange.Unstar -> NewsItemStateChange.Star
}

private fun NewsItemStateChange.failureLabel(): String = when (this) {
    NewsItemStateChange.Read -> "Couldn't mark the article as read."
    NewsItemStateChange.Unread -> "Couldn't mark the article as unread."
    NewsItemStateChange.Star -> "Couldn't star the article."
    NewsItemStateChange.Unstar -> "Couldn't remove the star."
}
