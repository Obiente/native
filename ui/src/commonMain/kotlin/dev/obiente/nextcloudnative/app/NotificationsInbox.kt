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

internal enum class NotificationsFailureKind {
    Offline,
    Unauthorized,
    Forbidden,
    Unavailable,
    Throttled,
    Server,
    Malformed,
    Oversized,
}

internal data class NotificationsFailure(val kind: NotificationsFailureKind) {
    val title: String get() = when (kind) {
        NotificationsFailureKind.Offline -> "Can't reach your server"
        NotificationsFailureKind.Unauthorized -> "Sign-in expired"
        NotificationsFailureKind.Forbidden -> "Notifications are not available to this account"
        NotificationsFailureKind.Unavailable -> "Notifications are not responding"
        NotificationsFailureKind.Throttled -> "The server asked to slow down"
        NotificationsFailureKind.Server -> "The server could not load notifications"
        NotificationsFailureKind.Malformed -> "The server sent an unexpected response"
        NotificationsFailureKind.Oversized -> "The notification list is too large"
    }

    val message: String get() = when (kind) {
        NotificationsFailureKind.Offline -> "Check your connection, then try again. Notifications already shown stay available."
        NotificationsFailureKind.Unauthorized -> "Sign in to this account again to see new notifications."
        NotificationsFailureKind.Forbidden -> "An administrator may have restricted the Notifications app."
        NotificationsFailureKind.Unavailable -> "The Notifications app may be disabled or still updating on the server."
        NotificationsFailureKind.Throttled -> "Wait a moment before trying again."
        NotificationsFailureKind.Server -> "Try again later. If it keeps happening, ask your administrator to check the server."
        NotificationsFailureKind.Malformed -> "Try again. If it keeps happening, the Notifications app may need an update."
        NotificationsFailureKind.Oversized ->
            "Dismiss older notifications in a browser, then try again. Nothing was changed."
    }
}

internal fun notificationsFailureForStatus(status: Int): NotificationsFailure? = when (status) {
    in 200..299 -> null
    401 -> NotificationsFailure(NotificationsFailureKind.Unauthorized)
    403 -> NotificationsFailure(NotificationsFailureKind.Forbidden)
    404, 410, 503 -> NotificationsFailure(NotificationsFailureKind.Unavailable)
    429 -> NotificationsFailure(NotificationsFailureKind.Throttled)
    else -> NotificationsFailure(NotificationsFailureKind.Server)
}

internal sealed interface NotificationsAvailability {
    data object Unknown : NotificationsAvailability
    data class Available(val capabilities: NotificationsCapabilities) : NotificationsAvailability
    data object NotEnabled : NotificationsAvailability
    data object Unsupported : NotificationsAvailability
}

/** A destructive request that waits for an explicit, separate confirmation. */
internal sealed interface NotificationsConfirmation {
    data object DismissAll : NotificationsConfirmation
    data class Action(val notificationId: Long, val action: NotificationAction) : NotificationsConfirmation
}

internal data class NotificationsInboxState(
    val availability: NotificationsAvailability = NotificationsAvailability.Unknown,
    /** Null until a list was read from the server or the account's cache. */
    val notifications: List<NextcloudNotification>? = null,
    val truncated: Boolean = false,
    val noNotifierApps: Boolean = false,
    /** True while the list comes from this process's cache and has not been read again yet. */
    val showingCachedCopy: Boolean = false,
    val refreshing: Boolean = false,
    val failure: NotificationsFailure? = null,
    val selectedId: Long? = null,
    val pendingIds: Set<Long> = emptySet(),
    val dismissingAll: Boolean = false,
    /** The last write to these had an unknown result; writes stay off until a later read. */
    val unconfirmedIds: Set<Long> = emptySet(),
    val allUnconfirmed: Boolean = false,
    /** A server action on these had an unknown result. It is not offered again. */
    val ambiguousActionIds: Set<Long> = emptySet(),
    val confirmation: NotificationsConfirmation? = null,
    val notice: String? = null,
) {
    val capabilities: NotificationsCapabilities?
        get() = (availability as? NotificationsAvailability.Available)?.capabilities

    val selected: NextcloudNotification? get() = selectedId?.let(::notification)

    /** The badge counts every notification the server still lists; the API has no read state. */
    val badgeCount: Int get() = notifications?.size ?: 0

    fun notification(id: Long): NextcloudNotification? = notifications?.firstOrNull { it.id == id }

    private val writesSettled: Boolean get() = !dismissingAll && !allUnconfirmed

    fun canDismiss(notification: NextcloudNotification): Boolean =
        capabilities?.canDismiss == true && writesSettled &&
            notification.id !in pendingIds && notification.id !in unconfirmedIds

    val canDismissAll: Boolean
        get() = capabilities?.canDismissAll == true && writesSettled &&
            pendingIds.isEmpty() && unconfirmedIds.isEmpty() && !notifications.isNullOrEmpty()

    fun canRunActions(notification: NextcloudNotification): Boolean =
        writesSettled && notification.id !in pendingIds && notification.id !in unconfirmedIds &&
            notification.id !in ambiguousActionIds
}

/**
 * Owns the Notifications inbox for one account: capabilities, the cached list, refresh, and
 * dismiss and action writes.
 *
 * Every list read is numbered when it starts, and a result is applied only when no read that
 * started later was applied first, so a slow refresh cannot restore notifications that a newer
 * read no longer lists. Writes are never retried. After each write a fresh read checks the
 * postcondition; when both the write and that read have unknown results, writes to the affected
 * notifications stay off until a read that started afterwards settles their state.
 */
internal class NotificationsInboxController(
    private val scope: CoroutineScope,
    private val accountId: NextcloudAccountId,
    private val serverUrl: String,
    private val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    private val cache: NotificationsInboxMemoryCache? = null,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val cacheProducer = cache?.producer(accountId)

    var state by mutableStateOf(
        cache?.get(accountId, cacheProducer)?.let { cached ->
            NotificationsInboxState(
                notifications = cached.notifications,
                truncated = cached.truncated,
                showingCachedCopy = true,
            )
        } ?: NotificationsInboxState(),
    )
        private set

    private var readSequence = 0L
    private var appliedSequence = 0L
    private var refreshGeneration = 0L
    private var refreshJob: Job? = null
    /** Read sequence at which each notification's write result became unknown. */
    private val unconfirmedSince = mutableMapOf<Long, Long>()
    private var allUnconfirmedSince: Long? = null

    fun start() {
        if (refreshJob == null) refresh()
    }

    fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        state = state.copy(refreshing = true)
        refreshJob = scope.launch {
            try {
                val read = readInbox(verification = false)
                if (generation == refreshGeneration) state = state.copy(failure = (read as? InboxRead.Failed)?.failure)
            } finally {
                // A replaced refresh leaves the indicator to the refresh that replaced it.
                if (generation == refreshGeneration) state = state.copy(refreshing = false)
            }
        }
    }

    fun select(notificationId: Long) {
        if (state.notification(notificationId) != null) state = state.copy(selectedId = notificationId)
    }

    fun clearSelection() {
        state = state.copy(selectedId = null)
    }

    fun dismissNotice() {
        state = state.copy(notice = null)
    }

    fun dismiss(notificationId: Long) {
        val notification = state.notification(notificationId) ?: return
        if (!state.canDismiss(notification)) return
        state = state.copy(pendingIds = state.pendingIds + notificationId, notice = null)
        scope.launch {
            val ids = setOf(notificationId)
            val notice = when (val outcome = write(dismissNotificationRequest(notificationId))) {
                // 404: the notification is already gone, which is the requested postcondition.
                WriteOutcome.Confirmed, WriteOutcome.NotFound -> verifyRemoved(ids, writeConfirmed = true)
                is WriteOutcome.Rejected -> "Couldn't dismiss the notification. ${outcome.failure.message}"
                WriteOutcome.Unknown -> verifyRemoved(ids, writeConfirmed = false)
            }
            state = state.copy(pendingIds = state.pendingIds - notificationId, notice = notice)
        }
    }

    fun requestDismissAll() {
        if (state.canDismissAll) state = state.copy(confirmation = NotificationsConfirmation.DismissAll, notice = null)
    }

    /** Runs a server action, or asks for confirmation first when it is destructive. */
    fun runAction(notificationId: Long, action: NotificationAction) {
        val notification = state.notification(notificationId) ?: return
        if (action.method == NotificationActionMethod.Web || !state.canRunActions(notification)) return
        if (action !in notification.actions) return
        if (action.destructive) {
            state = state.copy(confirmation = NotificationsConfirmation.Action(notificationId, action), notice = null)
        } else {
            performAction(notification, action)
        }
    }

    fun confirm() {
        val confirmation = state.confirmation ?: return
        state = state.copy(confirmation = null)
        when (confirmation) {
            NotificationsConfirmation.DismissAll -> if (state.canDismissAll) performDismissAll()
            is NotificationsConfirmation.Action -> state.notification(confirmation.notificationId)
                ?.takeIf { state.canRunActions(it) && confirmation.action in it.actions }
                ?.let { performAction(it, confirmation.action) }
        }
    }

    fun cancelConfirmation() {
        state = state.copy(confirmation = null)
    }

    private fun performDismissAll() {
        val ids = state.notifications.orEmpty().mapTo(HashSet(), NextcloudNotification::id)
        state = state.copy(dismissingAll = true, notice = null)
        scope.launch {
            val notice = when (val outcome = write(dismissAllNotificationsRequest())) {
                WriteOutcome.Confirmed -> verifyRemoved(ids, writeConfirmed = true)
                WriteOutcome.NotFound -> "Couldn't dismiss all notifications. This server does not support it."
                is WriteOutcome.Rejected -> "Couldn't dismiss all notifications. ${outcome.failure.message}"
                WriteOutcome.Unknown -> verifyRemoved(ids, writeConfirmed = false, all = true)
            }
            state = state.copy(dismissingAll = false, notice = notice)
        }
    }

    private fun performAction(notification: NextcloudNotification, action: NotificationAction) {
        val request = notificationActionRequest(serverUrl, action)
        if (request == null) {
            state = state.copy(notice = "\"${action.label}\" can't be done here. Open the notification instead.")
            return
        }
        val id = notification.id
        state = state.copy(pendingIds = state.pendingIds + id, notice = null)
        scope.launch {
            val notice = when (val outcome = write(request)) {
                WriteOutcome.Confirmed -> when (val read = verificationRead()) {
                    is InboxRead.Loaded -> if (read.ids.contains(id)) {
                        "The server accepted \"${action.label}\"."
                    } else {
                        "\"${action.label}\" is done."
                    }
                    is InboxRead.Failed -> "The server accepted \"${action.label}\". Refresh to see the result."
                }
                WriteOutcome.NotFound -> {
                    verificationRead()
                    "\"${action.label}\" is no longer available. The item it refers to may have been removed."
                }
                is WriteOutcome.Rejected -> "The server declined \"${action.label}\". ${outcome.failure.message}"
                WriteOutcome.Unknown -> {
                    // The request may have reached the server. Its effect is not known, so the
                    // action is not offered again here and is never repeated automatically.
                    state = state.copy(ambiguousActionIds = state.ambiguousActionIds + id)
                    val gone = (verificationRead() as? InboxRead.Loaded)?.ids?.contains(id) == false
                    if (gone) {
                        "The notification is no longer listed, so \"${action.label}\" probably completed."
                    } else {
                        "Couldn't confirm whether \"${action.label}\" reached the server. " +
                            "Open the notification to check before doing it again."
                    }
                }
            }
            state = state.copy(pendingIds = state.pendingIds - id, notice = notice)
        }
    }

    /**
     * Reads the list again after a dismissal and reports anything that is still listed. A
     * confirmed dismissal is applied locally when that read fails, because the API's success
     * response means the notification was deleted.
     */
    private suspend fun verifyRemoved(ids: Set<Long>, writeConfirmed: Boolean, all: Boolean = false): String? {
        val startedAt = readSequence + 1
        return when (val read = verificationRead()) {
            is InboxRead.Loaded -> {
                val stillListed = ids.count(read.ids::contains)
                when {
                    stillListed == 0 -> null
                    writeConfirmed -> if (all) {
                        "The server accepted the request, but ${stillListedLabel(stillListed)} still listed."
                    } else {
                        "The server accepted the dismissal, but still lists this notification."
                    }
                    all -> "The notifications were not dismissed. You can try again."
                    else -> "The notification was not dismissed. You can try again."
                }
            }
            is InboxRead.Failed -> if (writeConfirmed) {
                removeLocally(ids)
                null
            } else {
                if (all) {
                    allUnconfirmedSince = startedAt
                } else {
                    ids.forEach { unconfirmedSince[it] = startedAt }
                }
                state = state.copy(unconfirmedIds = unconfirmedSince.keys.toSet(), allUnconfirmed = allUnconfirmedSince != null)
                "Couldn't confirm whether the ${if (all) "notifications were" else "notification was"} dismissed. " +
                    "Refresh before trying again."
            }
        }
    }

    private fun removeLocally(ids: Set<Long>) {
        val remaining = state.notifications?.filterNot { it.id in ids } ?: return
        state = state.copy(notifications = remaining, selectedId = state.selectedId?.takeUnless { it in ids })
        storeInCache(remaining, state.truncated)
    }

    private suspend fun verificationRead(): InboxRead = readInbox(verification = true)

    private suspend fun readInbox(verification: Boolean): InboxRead {
        val sequence = ++readSequence
        if (state.capabilities == null) {
            when (val capabilities = readCapabilities()) {
                is CapabilitiesRead.Failed -> return InboxRead.Failed(capabilities.failure)
                is CapabilitiesRead.Loaded -> {
                    state = state.copy(availability = capabilities.availability)
                    if (capabilities.availability !is NotificationsAvailability.Available) {
                        state = state.copy(notifications = emptyList(), showingCachedCopy = false, selectedId = null)
                        storeInCache(emptyList(), truncated = false)
                        return InboxRead.Loaded(emptySet())
                    }
                }
            }
        }
        val result = try {
            val response = execute(notificationsListRequest(verification))
            notificationsFailureForStatus(response.status)?.let { return InboxRead.Failed(it) }
            withContext(parseDispatcher) { parseNotificationsList(response) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NotificationsProtocolException) {
            return InboxRead.Failed(failure.toFailure())
        } catch (_: NextcloudResponseTooLargeException) {
            return InboxRead.Failed(NotificationsFailure(NotificationsFailureKind.Oversized))
        } catch (_: Exception) {
            // Transport boundary: platform services throw I/O and TLS exceptions here.
            return InboxRead.Failed(NotificationsFailure(NotificationsFailureKind.Offline))
        }
        val notifications = (result as? NotificationsListResult.Loaded)?.notifications.orEmpty()
        if (sequence > appliedSequence) {
            appliedSequence = sequence
            unconfirmedSince.entries.removeAll { (_, since) -> since < sequence }
            if (allUnconfirmedSince?.let { it < sequence } == true) allUnconfirmedSince = null
            val truncated = (result as? NotificationsListResult.Loaded)?.truncated == true
            state = state.copy(
                notifications = notifications,
                truncated = truncated,
                noNotifierApps = result == NotificationsListResult.NoNotifierApps,
                showingCachedCopy = false,
                failure = null,
                selectedId = state.selectedId?.takeIf { selected -> notifications.any { it.id == selected } },
                unconfirmedIds = unconfirmedSince.keys.toSet(),
                allUnconfirmed = allUnconfirmedSince != null,
            )
            storeInCache(notifications, truncated)
        }
        return InboxRead.Loaded(notifications.mapTo(HashSet(), NextcloudNotification::id))
    }

    private suspend fun readCapabilities(): CapabilitiesRead = try {
        val response = execute(notificationsCapabilitiesRequest())
        notificationsFailureForStatus(response.status)?.let { CapabilitiesRead.Failed(it) }
            ?: CapabilitiesRead.Loaded(
                when (val parsed = withContext(parseDispatcher) { parseNotificationsCapabilities(response) }) {
                    is NotificationsCapabilitiesResult.Available -> NotificationsAvailability.Available(parsed.capabilities)
                    NotificationsCapabilitiesResult.NotEnabled -> NotificationsAvailability.NotEnabled
                    NotificationsCapabilitiesResult.Unsupported -> NotificationsAvailability.Unsupported
                },
            )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: NotificationsProtocolException) {
        CapabilitiesRead.Failed(failure.toFailure())
    } catch (_: NextcloudResponseTooLargeException) {
        CapabilitiesRead.Failed(NotificationsFailure(NotificationsFailureKind.Oversized))
    } catch (_: Exception) {
        CapabilitiesRead.Failed(NotificationsFailure(NotificationsFailureKind.Offline))
    }

    private suspend fun write(request: NextcloudApiRequest): WriteOutcome = try {
        val response = execute(request)
        when {
            notificationWriteSucceeded(response) -> WriteOutcome.Confirmed
            response.status == 404 -> WriteOutcome.NotFound
            // A gateway or server failure may come after the request took effect.
            response.status >= 500 -> WriteOutcome.Unknown
            else -> WriteOutcome.Rejected(
                notificationsFailureForStatus(response.status) ?: NotificationsFailure(NotificationsFailureKind.Server),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The request may or may not have reached the server.
        WriteOutcome.Unknown
    }

    private fun storeInCache(notifications: List<NextcloudNotification>, truncated: Boolean) {
        cache?.store(accountId, CachedNotificationsInbox(notifications, truncated), cacheProducer)
    }
}

private sealed interface InboxRead {
    data class Loaded(val ids: Set<Long>) : InboxRead
    data class Failed(val failure: NotificationsFailure) : InboxRead
}

private sealed interface CapabilitiesRead {
    data class Loaded(val availability: NotificationsAvailability) : CapabilitiesRead
    data class Failed(val failure: NotificationsFailure) : CapabilitiesRead
}

private sealed interface WriteOutcome {
    data object Confirmed : WriteOutcome
    data object NotFound : WriteOutcome
    data class Rejected(val failure: NotificationsFailure) : WriteOutcome
    data object Unknown : WriteOutcome
}

private fun NotificationsProtocolException.toFailure(): NotificationsFailure = NotificationsFailure(
    when (failure) {
        NotificationsProtocolFailure.Malformed -> NotificationsFailureKind.Malformed
        NotificationsProtocolFailure.Oversized -> NotificationsFailureKind.Oversized
    },
)

private fun stillListedLabel(count: Int): String = if (count == 1) "1 notification is" else "$count notifications are"
