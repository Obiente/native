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

/** What the calendar list can ask for: a new calendar or subscription, or managing one calendar. */
internal class CalendarCollectionActions(
    val onCreate: (subscription: Boolean) -> Unit,
    val onManage: (GroupwareCalendar) -> Unit,
)

internal sealed interface CalendarCollectionEdit {
    data class New(val subscription: Boolean) : CalendarCollectionEdit
    data class Existing(val calendar: GroupwareCalendar) : CalendarCollectionEdit
}

/**
 * Creates, changes, and removes calendars and subscriptions in the user's calendar home.
 *
 * Every change is confirmed against a fresh read of the calendar list, so a lost response never
 * looks like a success or a failure: the result is reported as unknown until the list shows it.
 * A new calendar keeps its address while its dialog is open, so retrying after an unknown result
 * finds the calendar the first attempt made instead of creating a second one.
 */
@Stable
internal class CalendarCollectionManager(
    private val services: NextcloudPlatformServices,
    private val session: NextcloudSession,
    private val userId: String,
    private val scope: CoroutineScope,
) {
    var edit by mutableStateOf<CalendarCollectionEdit?>(null)
        private set
    var deleting by mutableStateOf<GroupwareCalendar?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    internal var onChanged: () -> Unit = {}
    private var newHref: String? = null

    val actions = CalendarCollectionActions(
        onCreate = { subscription -> if (!busy) open(CalendarCollectionEdit.New(subscription)) },
        onManage = { calendar ->
            // A calendar shared with the user can only be removed from their list.
            when {
                busy -> Unit
                calendar.kind == GroupwareCalendarKind.Shared -> requestDelete(calendar)
                else -> open(CalendarCollectionEdit.Existing(calendar))
            }
        },
    )

    fun dismiss() {
        if (busy) return
        edit = null
        deleting = null
        error = null
        newHref = null
    }

    fun requestDelete(calendar: GroupwareCalendar) {
        if (busy) return
        edit = null
        error = null
        deleting = calendar
    }

    fun save(draft: CalendarCollectionDraft) {
        val target = edit ?: return
        draft.validationMessage()?.let { error = it; return }
        when (target) {
            is CalendarCollectionEdit.New -> {
                val href = newHref ?: try {
                    newCalendarCollectionHref(groupwareCalendarHomeHref(userId), draft.name).also { newHref = it }
                } catch (_: IllegalArgumentException) {
                    error = "This account's calendar home is not available."
                    return
                }
                val request = if (target.subscription) calendarSubscriptionCreateRequest(href, draft)
                    else calendarCollectionCreateRequest(href, draft)
                change(
                    request,
                    // An address that already exists may be this dialog's earlier attempt; the list decides.
                    provesRejection = { status -> status != 405 && groupwareMutationResponseProvesRejection(status) },
                    applied = { true },
                    confirmed = { calendars -> calendars.any { it.href == href && it.displayName == draft.name.trim() } },
                )
            }
            is CalendarCollectionEdit.Existing -> {
                val href = target.calendar.href
                change(
                    calendarCollectionUpdateRequest(href, draft),
                    provesRejection = ::groupwareMutationResponseProvesRejection,
                    applied = ::calendarPropertyUpdateApplied,
                    confirmed = { calendars ->
                        calendars.any { it.href == href && it.displayName == draft.name.trim() && it.color.sameCalendarColor(draft.color) }
                    },
                )
            }
        }
    }

    fun confirmDelete() {
        val calendar = deleting ?: return
        change(
            calendarCollectionDeleteRequest(calendar.href),
            provesRejection = { status -> !groupwareDeleteResponseProvesAbsence(status) && groupwareMutationResponseProvesRejection(status) },
            applied = { true },
            confirmed = { calendars -> calendars.none { it.href == calendar.href } },
        )
    }

    private fun open(target: CalendarCollectionEdit) {
        error = null
        newHref = null
        deleting = null
        edit = target
    }

    private fun change(
        request: GroupwareDavRequest,
        provesRejection: (Int) -> Boolean,
        applied: (NextcloudApiResponse) -> Boolean,
        confirmed: (List<GroupwareCalendar>) -> Boolean,
    ) {
        if (busy) return
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
                if (response != null && provesRejection(response.status)) {
                    error = calendarCollectionRejectionMessage(response.status)
                    return@launch
                }
                if (response != null && response.status in 200..299 && !applied(response)) {
                    error = "The server did not accept every change. Check the name and color, then try again."
                    return@launch
                }
                val listed = try {
                    parseGroupwareCalendarsForComponent(
                        services.executeGroupwareDav(session, groupwareDavCollectionDiscoveryRequest(groupwareCalendarHomeHref(userId))),
                        componentName = null,
                    )
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    null
                }
                if (listed != null && confirmed(listed)) {
                    edit = null
                    deleting = null
                    newHref = null
                    onChanged()
                } else {
                    error = if (response != null && response.status in 200..299) {
                        "The change has not appeared on the server yet. Refresh your calendars to check it."
                    } else {
                        "The result is unknown. Refresh your calendars before trying again."
                    }
                }
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun rememberCalendarCollectionManager(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    onChanged: () -> Unit,
): CalendarCollectionManager {
    val scope = rememberCoroutineScope()
    val currentOnChanged by rememberUpdatedState(onChanged)
    return remember(services, session, userId, scope) {
        CalendarCollectionManager(services, session, userId, scope).apply { this.onChanged = { currentOnChanged() } }
    }
}

internal fun calendarCollectionRejectionMessage(status: Int): String = when (status) {
    401, 403 -> "You don't have permission to change this calendar."
    404, 410 -> "This calendar no longer exists. Refresh your calendars."
    405 -> "The server does not allow this change."
    409, 412 -> "The calendar changed on the server. Refresh your calendars and try again."
    415, 422 -> "The server could not use these calendar details."
    else -> "The server refused the change (HTTP $status)."
}

private fun String?.sameCalendarColor(color: String): Boolean =
    this != null && take(7).equals(color.take(7), ignoreCase = true)
