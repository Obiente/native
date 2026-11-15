package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

/**
 * What the event editor saves. It is also kept in the durable recovery record, so a field added
 * here must have a default that means "not changed" for records written by an older version.
 */
@Serializable
internal data class EventDraft(
    val title: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val allDay: Boolean,
    val location: String,
    val description: String,
    val recurrenceRule: String?,
    /** Set once converted for storage: the event's TZID, or null for UTC; see [inDavFrame]. */ val zone: String? = null, val endDate: String? = null,
    /** Reminder offsets in minutes before the start; null leaves the stored reminders alone. */
    val reminderMinutes: List<Int>? = null,
    /** Invited email addresses; null leaves the guest list alone. */
    val attendees: List<String>? = null,
) {
    fun normalizedForDav(): EventDraft = copy(
        title = title.normalizeGroupwareTextLineEndings(),
        location = location.takeUnless(String::isBlank).orEmpty().normalizeGroupwareTextLineEndings(),
        description = description.takeUnless(String::isBlank).orEmpty().normalizeGroupwareTextLineEndings(),
        recurrenceRule = recurrenceRule?.trim()?.takeUnless(String::isBlank),
        reminderMinutes = reminderMinutes?.sorted(),
        attendees = attendees?.map { it.trim().lowercase() }?.filter(String::isNotEmpty)?.distinct()?.sorted(),
    )

    fun startValue(): String = date.isoDateToCompact() + if (allDay) "" else "T${startTime.timeToCompact()}00${if (zone == null) "Z" else ""}"
    fun endValue(): String? = if (allDay) nextIsoDate(date)?.isoDateToCompact() else (endDate ?: date).isoDateToCompact() + "T${endTime.timeToCompact()}00${if (zone == null) "Z" else ""}"
}

internal fun calendarEventDraftIsDirty(
    initial: EventDraft,
    current: EventDraft,
    initialCalendarHref: String?,
    currentCalendarHref: String?,
): Boolean = initial != current || initialCalendarHref != currentCalendarHref

internal fun calendarEventDraftHasDavChanges(
    initial: EventDraft,
    current: EventDraft,
    initialCalendarHref: String?,
    currentCalendarHref: String?,
): Boolean = initial.normalizedForDav() != current.normalizedForDav() ||
    initialCalendarHref != currentCalendarHref

/** Whether saving [current] changes who is invited, which sends invitations or cancellations. */
internal fun calendarEventDraftChangesGuests(initial: EventDraft, current: EventDraft): Boolean =
    current.attendees != null && initial.normalizedForDav().attendees != current.normalizedForDav().attendees
