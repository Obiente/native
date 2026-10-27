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
    /**
     * [pageFailure] belongs to loading an older page and is retried by paging again.
     * [refreshFailure] belongs to reloading the first page and is retried by refreshing.
     */
    data class Loaded(
        val items: List<NewsItem>,
        val endReached: Boolean,
        val loadingMore: Boolean = false,
        val pageFailure: NewsFailure? = null,
        val refreshFailure: NewsFailure? = null,
    ) : NewsArticlesState
    data class Failed(val failure: NewsFailure) : NewsArticlesState
}

internal data class NewsWorkspaceUiState(
    val subscriptions: NewsSubscriptions? = null,
    val subscriptionsFailure: NewsFailure? = null,
    val refreshing: Boolean = true,
    val selection: NewsSelection = NewsSelection.Unread,
    val articles: NewsArticlesState = NewsArticlesState.Loading,
    /**
     * The article in the reader. It is kept apart from the list, because a refresh can drop it from
     * the list (an opened article is read, so Unread no longer contains it) while it is being read.
     */
    val openedItem: NewsItem? = null,
    val pendingItemIds: Set<Long> = emptySet(),
    val stateChangeNotice: String? = null,
) {
    val openItemId: Long? get() = openedItem?.id

    /** The list's copy when it has one, since it is the most recently read from the server. */
    val openItem: NewsItem? get() = openedItem?.let { opened ->
        (articles as? NewsArticlesState.Loaded)?.items?.firstOrNull { it.id == opened.id } ?: opened
    }

    /** Applies a read or star change to every local copy of one article. */
    fun applying(itemId: Long, change: NewsItemStateChange): NewsWorkspaceUiState = copy(
        articles = (articles as? NewsArticlesState.Loaded)
            ?.let { loaded -> loaded.copy(items = loaded.items.map { if (it.id == itemId) it.applying(change) else it }) }
            ?: articles,
        openedItem = openedItem?.let { if (it.id == itemId) it.applying(change) else it },
    )
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

    /** At most one change per article and field, so a pending star never blocks marking it read. */
    private val pendingChanges = mutableMapOf<PendingNewsKey, PendingNewsChange>()
    private var settledChanges = 0L
    private var countsReloadNeeded = false

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
            state = state.copy(openedItem = null)
            return
        }
        state = state.copy(selection = selection, openedItem = null, stateChangeNotice = null)
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
        state = state.copy(openedItem = item, stateChangeNotice = null)
        if (item.unread) change(item, NewsItemStateChange.Read)
    }

    fun closeArticle() {
        state = state.copy(openedItem = null)
    }

    fun toggleRead(item: NewsItem) =
        change(item, if (item.unread) NewsItemStateChange.Read else NewsItemStateChange.Unread)

    fun toggleStar(item: NewsItem) =
        change(item, if (item.starred) NewsItemStateChange.Unstar else NewsItemStateChange.Star)

    fun dismissNotice() {
        state = state.copy(stateChangeNotice = null)
    }

    private fun change(item: NewsItem, change: NewsItemStateChange) {
        val key = PendingNewsKey(item.id, change.marksRead())
        if (key in pendingChanges) return
        val current = (state.articles as? NewsArticlesState.Loaded)?.items?.firstOrNull { it.id == item.id } ?: item
        val pending = PendingNewsChange(change, item.feedId, current.unreadDelta(change), current.starredDelta(change))
        pendingChanges[key] = pending
        state = state.applying(item.id, change).copy(
            subscriptions = state.subscriptions?.adjustedBy(pending, sign = 1),
            pendingItemIds = state.pendingItemIds + item.id,
        )
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
            val settled = pendingChanges.remove(key) ?: pending
            val stillPending = pendingChanges.keys.any { it.itemId == item.id }
            val pendingItemIds = if (stillPending) state.pendingItemIds else state.pendingItemIds - item.id
            settledChanges++
            state = if (outcome == null) {
                // Idempotent: corrects a copy that a page fetched before this change replaced.
                state.applying(item.id, change).copy(pendingItemIds = pendingItemIds)
            } else {
                // Counts are reversed from the recorded adjustment, so a rejection after the user
                // moved to another view still restores them. Counts read from the server since the
                // change started no longer contain the adjustment and are reloaded instead.
                state.applying(item.id, change.inverse()).copy(
                    subscriptions = if (settled.countsApplied) {
                        state.subscriptions?.adjustedBy(settled, sign = -1)
                    } else {
                        state.subscriptions
                    },
                    pendingItemIds = pendingItemIds,
                    stateChangeNotice = outcome,
                )
            }
            if (pendingChanges.isEmpty() && countsReloadNeeded) loadSubscriptions()
        }
    }

    private fun loadSubscriptions() {
        val generation = ++subscriptionGeneration
        subscriptionJob?.cancel()
        countsReloadNeeded = false
        val changesActiveAtStart = pendingChanges.isNotEmpty()
        val settledAtStart = settledChanges
        state = state.copy(refreshing = true)
        subscriptionJob = scope.launch {
            val result = readSubscriptions()
            if (generation != subscriptionGeneration) return@launch
            when (result) {
                is NewsRead.Success -> {
                    // A change in flight may or may not be included in these counts, so neither
                    // adding nor omitting its adjustment is safe. Show the server counts and read
                    // them again once every change has settled.
                    val countsUncertain = changesActiveAtStart || pendingChanges.isNotEmpty() ||
                        settledChanges != settledAtStart
                    if (countsUncertain) {
                        pendingChanges.entries.forEach { entry -> entry.setValue(entry.value.copy(countsApplied = false)) }
                        countsReloadNeeded = true
                    }
                    val selection = state.selection.takeIfStillPresent(result.value)
                    val selectionRemoved = selection != state.selection
                    state = state.copy(
                        subscriptions = result.value,
                        subscriptionsFailure = null,
                        refreshing = false,
                        selection = selection,
                        openedItem = state.openedItem.takeUnless { selectionRemoved },
                    )
                    // The removed feed or folder's pending page must not fill the fallback view.
                    if (selectionRemoved) loadArticles(selection, keepVisibleItems = false)
                    if (countsUncertain && pendingChanges.isEmpty()) loadSubscriptions()
                }
                is NewsRead.Failure -> state = state.copy(subscriptionsFailure = result.failure, refreshing = false)
            }
        }
    }

    private fun loadArticles(selection: NewsSelection, keepVisibleItems: Boolean) {
        val generation = ++articleGeneration
        articleJob?.cancel()
        val visible = state.articles as? NewsArticlesState.Loaded
        state = state.copy(
            articles = if (!keepVisibleItems || visible == null) {
                NewsArticlesState.Loading
            } else {
                // The older-page request was cancelled above; it must not leave paging blocked.
                visible.copy(loadingMore = false, pageFailure = null)
            },
        )
        articleJob = scope.launch {
            val result = readItems(selection, olderThanItemId = null)
            if (generation != articleGeneration) return@launch
            state = state.copy(
                articles = when (result) {
                    is NewsRead.Success -> NewsArticlesState.Loaded(
                        items = result.value.withPendingChanges(),
                        endReached = result.value.size < NEWS_ITEM_PAGE_SIZE,
                    )
                    // Keep the articles as they are now, including state changes made during the
                    // refresh, rather than the list captured when it started.
                    is NewsRead.Failure -> (state.articles as? NewsArticlesState.Loaded)
                        ?.copy(refreshFailure = result.failure)
                        ?: NewsArticlesState.Failed(result.failure)
                },
            )
        }
    }

    private fun List<NewsItem>.withPendingChanges(): List<NewsItem> =
        if (pendingChanges.isEmpty()) {
            this
        } else {
            map { item ->
                pendingChanges.entries.fold(item) { current, (key, pending) ->
                    if (key.itemId == item.id) current.applying(pending.change) else current
                }
            }
        }

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

/** Identifies the read field ([readField] true) or the star field of one article. */
private data class PendingNewsKey(val itemId: Long, val readField: Boolean)

private fun NewsItemStateChange.marksRead(): Boolean =
    this == NewsItemStateChange.Read || this == NewsItemStateChange.Unread

/**
 * One in-flight article change and the count adjustment applied when it started. [countsApplied]
 * turns false once server counts replace the adjusted ones, so a rejection does not reverse an
 * adjustment that is no longer shown.
 */
private data class PendingNewsChange(
    val change: NewsItemStateChange,
    val feedId: Long,
    val unreadDelta: Int,
    val starredDelta: Int,
    val countsApplied: Boolean = true,
)

private fun NewsItem.unreadDelta(change: NewsItemStateChange): Int = when {
    change == NewsItemStateChange.Read && unread -> -1
    change == NewsItemStateChange.Unread && !unread -> 1
    else -> 0
}

private fun NewsItem.starredDelta(change: NewsItemStateChange): Int = when {
    change == NewsItemStateChange.Star && !starred -> 1
    change == NewsItemStateChange.Unstar && starred -> -1
    else -> 0
}

private fun NewsSubscriptions.adjustedBy(pending: PendingNewsChange, sign: Int): NewsSubscriptions = copy(
    feeds = feeds.map { feed ->
        if (feed.id == pending.feedId) feed.copy(unreadCount = (feed.unreadCount + sign * pending.unreadDelta).coerceAtLeast(0)) else feed
    },
    starredCount = (starredCount + sign * pending.starredDelta).coerceAtLeast(0),
)

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
