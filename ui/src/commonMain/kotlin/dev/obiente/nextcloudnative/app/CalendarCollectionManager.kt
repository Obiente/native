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
    /** Opens the deleted calendars, events and tasks. */
    val onOpenTrash: (() -> Unit)? = null,
    /** Set while the contact birthdays calendar is off: turns it back on. */
    val onEnableBirthdays: (() -> Unit)? = null,
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
 * finds the calendar the first attempt made instead of creating a second one. The address is also
 * recorded on the device before the request leaves, so the same holds after the dialog was closed
 * or the app restarted: the next creation first checks whether the earlier one arrived.
 */
@Stable
internal class CalendarCollectionManager(
    private val services: NextcloudPlatformServices,
    private val session: NextcloudSession,
    private val userId: String,
    private val scope: CoroutineScope,
    /** Manages task lists: new collections hold only tasks. */
    val forTasks: Boolean = false,
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

    /** Turns the contact birthdays calendar on; the calendar list confirms it appeared. */
    fun enableBirthdayCalendar() {
        if (busy) return
        change(
            birthdayCalendarEnableRequest(userId),
            provesRejection = ::groupwareMutationResponseProvesRejection,
            applied = { true },
            confirmed = { calendars -> calendars.any(GroupwareCalendar::isBirthdayCalendar) },
            onFailure = { message -> birthdayError = message },
        )
    }

    var birthdayError by mutableStateOf<String?>(null)
        private set

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
            is CalendarCollectionEdit.New -> create(target, draft)
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

    private fun create(target: CalendarCollectionEdit.New, draft: CalendarCollectionDraft) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            var handedOver = false
            try {
                val href = creationHref(draft) ?: return@launch
                val request = if (target.subscription) calendarSubscriptionCreateRequest(href, draft)
                    else calendarCollectionCreateRequest(href, draft.copy(tasksOnly = forTasks))
                busy = false
                handedOver = true
                change(
                    request,
                    // An address that already exists may be this dialog's earlier attempt; the list decides.
                    provesRejection = { status -> status != 405 && groupwareMutationResponseProvesRejection(status) },
                    applied = { true },
                    confirmed = { calendars -> calendars.any { it.href == href && it.displayName == draft.name.trim() } },
                    onSettled = { forgetCreation() },
                )
            } finally {
                if (!handedOver) busy = false
            }
        }
    }

    /**
     * The address to create at, recorded on the device before it is used. Null, with [error] set,
     * when nothing may be sent: an earlier creation turned out to exist, or could not be checked.
     */
    private suspend fun creationHref(draft: CalendarCollectionDraft): String? {
        newHref?.let { return it }
        val home = try {
            groupwareCalendarHomeHref(userId)
        } catch (_: IllegalArgumentException) {
            error = "This account's calendar home is not available."
            return null
        }
        val store = services.textEditorDraftStore(session, pendingCreationPath)
        val earlier = try {
            store?.load()?.text?.takeIf { it.startsWith(home) && it.length <= MAX_PENDING_CREATION_HREF }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Persistence fault boundary: an unreadable record may be a calendar already created.
            error = "An earlier attempt saved on this device could not be read. Refresh your calendars before creating another."
            return null
        }
        if (earlier != null) {
            val listed = try {
                parseGroupwareCalendarsForComponent(
                    services.executeGroupwareDav(session, groupwareDavCollectionDiscoveryRequest(home)),
                    componentName = null,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                error = "An earlier attempt could not be checked. Refresh your calendars before trying again."
                return null
            }
            if (listed.any { it.href == earlier }) {
                forgetCreation()
                onChanged()
                error = "An earlier attempt was created after all and is now in your list. Check it before creating another."
                return null
            }
        }
        val href = try {
            newCalendarCollectionHref(home, draft.name)
        } catch (_: IllegalArgumentException) {
            error = "This account's calendar home is not available."
            return null
        }
        try {
            store?.save(TextEditorDraft(originalText = "", text = href, etag = null))
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            error = "This device could not keep a record of the new calendar, so it was not created."
            return null
        }
        newHref = href
        return href
    }

    /** The creation was confirmed or refused; its record is dropped. */
    private suspend fun forgetCreation() {
        try {
            services.textEditorDraftStore(session, pendingCreationPath)?.remove()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // A record left behind only causes one more check of the calendar list.
        }
    }

    private val pendingCreationPath: String get() = PENDING_CREATION_PATH_PREFIX + if (forTasks) "tasks" else "calendar"

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
        onFailure: (String) -> Unit = { error = it },
        /** Runs once the server answered definitely: the change was refused or is confirmed. */
        onSettled: suspend () -> Unit = {},
    ) {
        if (busy) return
        busy = true
        error = null
        birthdayError = null
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
                    onSettled()
                    onFailure(calendarCollectionRejectionMessage(response.status))
                    return@launch
                }
                if (response != null && response.status in 200..299 && !applied(response)) {
                    onFailure("The server did not accept every change. Check the name and color, then try again.")
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
                    onSettled()
                    edit = null
                    deleting = null
                    newHref = null
                    onChanged()
                } else {
                    onFailure(
                        if (response != null && response.status in 200..299) {
                            "The change has not appeared on the server yet. Refresh your calendars to check it."
                        } else {
                            "The result is unknown. Refresh your calendars before trying again."
                        },
                    )
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
    forTasks: Boolean = false,
    onChanged: () -> Unit,
): CalendarCollectionManager {
    val scope = rememberCoroutineScope()
    val currentOnChanged by rememberUpdatedState(onChanged)
    return remember(services, session, userId, scope, forTasks) {
        CalendarCollectionManager(services, session, userId, scope, forTasks).apply { this.onChanged = { currentOnChanged() } }
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

private const val PENDING_CREATION_PATH_PREFIX = "nextcloud-native:calendar-pending-creation:"
private const val MAX_PENDING_CREATION_HREF = 2048
