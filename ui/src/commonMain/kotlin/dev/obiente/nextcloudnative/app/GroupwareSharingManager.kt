package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The calendar or address book whose sharing is open. */
internal data class GroupwareShareTarget(
    val href: String,
    val name: String,
    /** Calendars can also be published as a public read-only link. */
    val publishable: Boolean,
)

/**
 * Shares one calendar or address book. Every change is followed by a fresh read of the sharing
 * state, and only that read decides what the dialog shows: a lost response is reported as
 * unknown, never as done. Share changes are idempotent on the server, so trying again after an
 * unknown result cannot create a duplicate.
 */
@Stable
internal class GroupwareSharingManager(
    private val services: NextcloudPlatformServices,
    private val session: NextcloudSession,
    private val scope: CoroutineScope,
) {
    var target by mutableStateOf<GroupwareShareTarget?>(null)
        private set
    var state by mutableStateOf<DavSharingState?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var results by mutableStateOf<List<DavSharee>>(emptyList())
        private set
    var searchMessage by mutableStateOf<String?>(null)
        private set
    private var generation = 0

    /** Counts refreshes and changes, so a refresh a change overtook cannot replace its result. */
    private var readEpoch = 0

    fun open(value: GroupwareShareTarget) {
        if (busy) return
        generation++
        target = value
        state = null
        error = null
        results = emptyList()
        searchMessage = null
        refresh()
    }

    fun dismiss() {
        if (busy) return
        generation++
        target = null
        state = null
        error = null
        results = emptyList()
    }

    fun refresh() {
        val current = target ?: return
        val expected = generation
        val epoch = ++readEpoch
        loading = true
        scope.launch {
            try {
                val loaded = readState(current.href)
                if (expected != generation || epoch != readEpoch) return@launch
                if (loaded == null) error = "Sharing details could not be loaded. Check your connection and try again."
                else state = loaded
            } finally {
                if (expected == generation && epoch == readEpoch) loading = false
            }
        }
    }

    /** Looks up people and groups; called by the dialog after the query settles. */
    suspend fun search(query: String) {
        val term = query.trim()
        if (term.length < MIN_PRINCIPAL_SEARCH_LENGTH) {
            results = emptyList()
            searchMessage = null
            return
        }
        val found = try {
            parseDavPrincipalSearch(services.executeGroupwareDav(session, davPrincipalSearchRequest(term)))
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        }
        val shared = state?.shares.orEmpty().map { it.sharee.principal }.toSet()
        results = found.orEmpty().filterNot { it.principal in shared }
        searchMessage = when {
            found == null -> "Search is not available right now."
            results.isEmpty() -> "No people or groups match \"$term\"."
            else -> null
        }
    }

    fun share(sharee: DavSharee) = change({ davShareUpdateRequest(it, set = listOf(DavShare(sharee, false))) }) { state ->
        state.shares.any { it.sharee.principal == sharee.principal }
    }

    fun setWritable(share: DavShare, writable: Boolean) =
        change({ davShareUpdateRequest(it, set = listOf(share.copy(writable = writable))) }) { state ->
            state.shares.any { it.sharee.principal == share.sharee.principal && it.writable == writable }
        }

    fun unshare(sharee: DavSharee) = change({ davShareUpdateRequest(it, remove = listOf(sharee)) }) { state ->
        state.shares.none { it.sharee.principal == sharee.principal }
    }

    fun setPublished(published: Boolean) = change({ calendarPublishRequest(it, published) }) { state ->
        (state.publishUrl != null) == published
    }

    fun copyLink(): Boolean = state?.publishUrl?.let { services.copyTextToClipboard("Calendar link", it) } == true

    private fun change(build: (String) -> GroupwareDavRequest, confirmed: (DavSharingState) -> Boolean) {
        val current = target ?: return
        if (busy) return
        val request = try {
            build(current.href)
        } catch (_: IllegalArgumentException) {
            error = "This change cannot be made safely."
            return
        }
        val expected = generation
        // A refresh still in flight read the state from before this change; its answer is dropped.
        readEpoch++
        loading = false
        busy = true
        error = null
        scope.launch {
            try {
                val response = try {
                    services.executeGroupwareDav(session, request)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    null
                }
                if (response != null && davSharingResponseProvesRejection(response.status)) {
                    if (expected == generation) error = groupwareSharingRejectionMessage(response.status)
                    return@launch
                }
                val loaded = readState(current.href)
                if (expected != generation) return@launch
                loaded?.let { state = it }
                if (loaded == null || !confirmed(loaded)) {
                    error = if (response != null && response.status in 200..299) {
                        "The server did not apply this change. Your account or the server may not allow it."
                    } else {
                        "The result is unknown. The list shows what the server reports now."
                    }
                } else {
                    results = results.filterNot { sharee -> loaded.shares.any { it.sharee.principal == sharee.principal } }
                }
            } finally {
                busy = false
            }
        }
    }

    private suspend fun readState(href: String): DavSharingState? = try {
        parseDavSharingState(services.executeGroupwareDav(session, davSharingStateRequest(href)))
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    }
}

@Composable
internal fun rememberGroupwareSharingManager(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
): GroupwareSharingManager {
    val scope = rememberCoroutineScope()
    return remember(services, session, scope) { GroupwareSharingManager(services, session, scope) }
}

internal fun groupwareSharingRejectionMessage(status: Int): String = when (status) {
    401, 403 -> "You don't have permission to change who can see this."
    404, 410 -> "This no longer exists. Refresh and try again."
    429 -> "Too many sharing changes. Wait a moment and try again."
    else -> "The server refused the change (HTTP $status)."
}
