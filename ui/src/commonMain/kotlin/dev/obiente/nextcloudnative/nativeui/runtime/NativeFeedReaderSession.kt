package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Local read and star state of one app's feed reader.
 *
 * A requested change shows immediately and stays while its request runs. Afterwards it stays only
 * while the article's record is the one it was applied to and does not show the change yet, so
 * appending a page keeps it, while a server copy of that article, changed in any way, replaces it.
 * The last error survives page appends and is cleared when the list is read again.
 */
@Stable
internal class NativeFeedReaderSession {
    var overrides: Map<String, NativeFeedArticleState> by mutableStateOf(emptyMap())
        private set
    var actionError: String? by mutableStateOf(null)
        private set

    private val pending = mutableSetOf<String>()
    private val appliedTo = mutableMapOf<String, NativeRecord>()
    private var articleKeys: List<String> = emptyList()

    /** Reconciles local state with a newly presented article list. */
    fun reconcile(articles: List<NativeFeedArticle>) {
        val keys = articles.map(NativeFeedArticle::key)
        val appended = keys.size >= articleKeys.size && keys.subList(0, articleKeys.size) == articleKeys
        val byKey = articles.associateBy(NativeFeedArticle::key)
        val kept = overrides.filter { (key, state) ->
            val article = byKey[key] ?: return@filter false
            key in pending || (article.record == appliedTo[key] && !article.shows(state))
        }
        appliedTo.keys.retainAll(kept.keys)
        overrides = kept
        if (!appended) actionError = null
        articleKeys = keys
    }

    fun begin(article: NativeFeedArticle, change: NativeFeedItemActionPlan) {
        val current = overrides[article.key] ?: NativeFeedArticleState()
        val requested = change.requestedState
        overrides = overrides + (article.key to NativeFeedArticleState(requested.unread ?: current.unread, requested.starred ?: current.starred))
        appliedTo[article.key] = article.record
        pending += article.key
        actionError = null
    }

    fun succeeded(article: NativeFeedArticle) {
        pending -= article.key
    }

    /** Rolls back this article's change and records why. */
    fun failed(article: NativeFeedArticle, message: String) {
        pending -= article.key
        appliedTo -= article.key
        overrides = overrides - article.key
        actionError = message
    }

    private fun NativeFeedArticle.shows(state: NativeFeedArticleState): Boolean =
        (state.unread == null || state.unread == unread) && (state.starred == null || state.starred == starred)
}

/**
 * Automatic loading of the next page when the end of the list is reached. Under a filter a page
 * can add nothing visible; after [maxUnproductivePages] such pages in a row it stops, and the
 * Load more button remains, so a filter never silently pulls every page.
 */
internal class NativeFeedAutoLoad(private val maxUnproductivePages: Int = 3) {
    private var lastLoaded = -1
    private var lastVisible = 0
    private var misses = 0

    fun shouldLoad(loadedCount: Int, visibleCount: Int): Boolean {
        if (loadedCount != lastLoaded) {
            misses = if (lastLoaded >= 0 && visibleCount <= lastVisible) misses + 1 else 0
            lastLoaded = loadedCount
            lastVisible = visibleCount
        }
        return misses < maxUnproductivePages
    }
}
