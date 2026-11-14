package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The calendar trash bin and the contact birthdays calendar. Restoring and removing are verified
 * by reading the trash bin again: an item counts as handled only once it is gone from it. Emptying
 * the trash bin removes items one at a time and reports exactly which ones could not be removed.
 */
@Stable
internal class CalendarTrashBinManager(
    private val services: NextcloudPlatformServices,
    private val session: NextcloudSession,
    private val userId: String,
    private val scope: CoroutineScope,
) {
    var open by mutableStateOf(false)
        private set
    var contents by mutableStateOf<CalendarTrashContents?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var confirmingEmpty by mutableStateOf(false)
        private set
    var confirmingRemoval by mutableStateOf<CalendarTrashItem?>(null)
        private set
    internal var onChanged: () -> Unit = {}

    fun show() {
        open = true
        error = null
        reload()
    }

    fun dismiss() {
        if (busy) return
        open = false
        confirmingEmpty = false
        confirmingRemoval = null
    }

    fun reload() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val loaded = read()
                if (loaded == null) error = "The trash bin could not be loaded. Check your connection and try again."
                else contents = loaded
            } finally {
                loading = false
            }
        }
    }

    fun restore(item: CalendarTrashItem) = process(listOf(item), "Restoring") { calendarTrashRestoreRequest(session.serverUrl, userId, it) }

    fun requestRemoval(item: CalendarTrashItem) {
        if (!busy) confirmingRemoval = item
    }

    fun requestEmpty() {
        if (!busy && contents?.items?.isNotEmpty() == true) confirmingEmpty = true
    }

    fun cancelConfirmation() {
        confirmingEmpty = false
        confirmingRemoval = null
    }

    fun confirmRemoval() {
        val items = confirmingRemoval?.let { listOf(it) } ?: contents?.items.orEmpty().takeIf { confirmingEmpty } ?: return
        confirmingEmpty = false
        confirmingRemoval = null
        process(items, "Deleting") { calendarTrashDeleteRequest(userId, it) }
    }

    /** Sends one request per item, then checks the trash bin to see which items are really gone. */
    private fun process(items: List<CalendarTrashItem>, action: String, build: (CalendarTrashItem) -> GroupwareDavRequest) {
        if (busy || items.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            try {
                var rejected = 0
                var refused: Int? = null
                for (item in items) {
                    val request = try {
                        build(item)
                    } catch (_: IllegalArgumentException) {
                        rejected++
                        continue
                    }
                    val status = try {
                        services.executeGroupwareDav(session, request).status
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        null
                    }
                    if (status != null && status !in 200..299 && !groupwareDeleteResponseProvesAbsence(status) &&
                        groupwareMutationResponseProvesRejection(status)
                    ) {
                        rejected++
                        refused = status
                    }
                }
                val loaded = read()
                if (loaded != null) contents = loaded
                val remaining = loaded?.items.orEmpty().count { left -> items.any { it.href == left.href } }
                error = when {
                    loaded == null -> "$action finished, but the trash bin could not be checked. Refresh to see the result."
                    remaining == 0 -> null
                    refused == 403 || refused == 401 -> "$action failed for $remaining of ${items.size} item(s): you don't have permission."
                    rejected > 0 -> "$action failed for $remaining of ${items.size} item(s)."
                    else -> "$action did not finish for $remaining of ${items.size} item(s). Refresh and try again."
                }
                if (remaining < items.size) onChanged()
            } finally {
                busy = false
            }
        }
    }

    private suspend fun read(): CalendarTrashContents? = try {
        val calendars = parseDeletedCalendars(
            services.executeGroupwareDav(session, groupwareDavCollectionDiscoveryRequest(groupwareCalendarHomeHref(userId))),
        )
        val objects = parseDeletedCalendarObjects(
            services.executeGroupwareDav(session, calendarTrashObjectsRequest(userId)),
            calendarTrashHomeHref(userId) + "objects/",
        )
        val retention = try {
            parseCalendarTrashRetentionDays(services.executeGroupwareDav(session, calendarTrashRetentionRequest(userId)))
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        }
        CalendarTrashContents(
            (calendars + objects).sortedByDescending { it.deletedAt.orEmpty() },
            retention,
        )
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    }
}

@Composable
internal fun rememberCalendarTrashBinManager(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    onChanged: () -> Unit,
): CalendarTrashBinManager {
    val scope = rememberCoroutineScope()
    val currentOnChanged by rememberUpdatedState(onChanged)
    return remember(services, session, userId, scope) {
        CalendarTrashBinManager(services, session, userId, scope).apply { this.onChanged = { currentOnChanged() } }
    }
}

/** The contact birthdays calendar Nextcloud keeps in sync from address books. */
internal const val BIRTHDAY_CALENDAR_SEGMENT = "contact_birthdays"

internal fun GroupwareCalendar.isBirthdayCalendar(): Boolean = href.trimEnd('/').endsWith("/$BIRTHDAY_CALENDAR_SEGMENT")

/** Turns the contact birthdays calendar back on (apps/dav/lib/CalDAV/BirthdayCalendar/EnablePlugin.php). */
internal fun birthdayCalendarEnableRequest(userId: String): GroupwareDavRequest = GroupwareDavRequest(
    method = "POST",
    relativePath = groupwareCalendarHomeHref(userId),
    contentType = "application/xml; charset=utf-8",
    body = """<?xml version="1.0" encoding="UTF-8"?><x3:enable-birthday-calendar xmlns:x3="http://nextcloud.com/ns"/>""".encodeToByteArray(),
    maximumResponseBytes = 64L * 1024L,
)
