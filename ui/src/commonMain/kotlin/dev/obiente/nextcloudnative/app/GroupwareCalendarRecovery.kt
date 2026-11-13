package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal sealed interface CalendarMutationPostcondition {
    val href: String
    fun isSatisfiedBy(response: NextcloudApiResponse): Boolean

    @Serializable
    data class Upsert(
        override val href: String,
        val calendarHref: String,
        val expectedUid: String,
        val previousEtag: String?,
        val draft: EventDraft,
        /** Set when one occurrence of a series was changed: the exception that must hold the draft. */
        val expectedRecurrenceId: String? = null,
    ) : CalendarMutationPostcondition {
        override fun isSatisfiedBy(response: NextcloudApiResponse): Boolean {
            if (response.status !in 200..299) return false
            val expected = draft.normalizedForDav()
            val event = parseGroupwareCalendarEventsFromContent(
                calendarHref = calendarHref,
                href = href,
                etag = response.etag,
                content = response.body.decodeToString(),
            ).firstOrNull { candidate ->
                candidate.uid == expectedUid && candidate.recurrenceId == expectedRecurrenceId
            } ?: return false
            if (expected.reminderMinutes != null && event.reminderMinutes() != expected.reminderMinutes) return false
            if (expected.attendees != null &&
                event.invitation().attendees.map(CalendarEventParticipant::address).sorted() != expected.attendees
            ) {
                return false
            }
            return event.href == href &&
                event.uid == expectedUid &&
                event.title == expected.title &&
                event.allDay == expected.allDay &&
                event.location.orEmpty() == expected.location &&
                event.description.orEmpty() == expected.description &&
                event.recurrenceRule == expected.recurrenceRule &&
                event.start == expected.startValue() &&
                event.end == expected.endValue()
        }
    }

    /** One occurrence removed from a series: it is excluded and no exception keeps it alive. */
    @Serializable
    data class ExcludeOccurrence(
        override val href: String,
        val calendarHref: String,
        val expectedUid: String,
        val occurrence: String,
    ) : CalendarMutationPostcondition {
        override fun isSatisfiedBy(response: NextcloudApiResponse): Boolean {
            if (response.status !in 200..299) return false
            val components = parseGroupwareCalendarEventsFromContent(calendarHref, href, response.etag, response.body.decodeToString())
                .filter { it.uid == expectedUid }
            val master = components.firstOrNull { it.recurrenceId == null } ?: return false
            return occurrence in master.excludedStarts && components.none { it.recurrenceId == occurrence }
        }
    }

    /** The user's answer to an invitation is stored on their attendee entry. */
    @Serializable
    data class Respond(
        override val href: String,
        val calendarHref: String,
        val expectedUid: String,
        val recurrenceId: String?,
        val address: String,
        val participation: String,
    ) : CalendarMutationPostcondition {
        override fun isSatisfiedBy(response: NextcloudApiResponse): Boolean {
            if (response.status !in 200..299) return false
            val event = parseGroupwareCalendarEventsFromContent(calendarHref, href, response.etag, response.body.decodeToString())
                .firstOrNull { it.uid == expectedUid && it.recurrenceId == recurrenceId } ?: return false
            return event.invitation().attendees.any { it.address == address && it.participation.icalValue == participation }
        }
    }

    @Serializable
    data class Delete(override val href: String) : CalendarMutationPostcondition {
        override fun isSatisfiedBy(response: NextcloudApiResponse): Boolean =
            groupwareDeleteResponseProvesAbsence(response.status)
    }
}

@Serializable
internal data class CalendarMutationRecoveryState(
    val accountScope: String,
    val postcondition: CalendarMutationPostcondition,
) {
    init {
        require(accountScope.isCanonicalGroupwareMutationAccountScope())
    }
}

private val calendarMutationRecoveryJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun durableMutationAccountScope(session: NextcloudSession): String =
    publicContentSha256(
        listOf(session.serverUrl.trimEnd('/'), session.loginName)
            .joinToString("|") { value -> "${value.length}:$value" }
            .encodeToByteArray(),
    )

internal fun String.isCanonicalGroupwareMutationAccountScope(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }

internal fun CalendarMutationRecoveryState.encodeForSavedState(): String =
    calendarMutationRecoveryJson.encodeToString(this)

internal fun decodeCalendarMutationRecoveryState(
    encoded: String,
    expectedAccountScope: String,
): CalendarMutationPostcondition? = runCatching {
    calendarMutationRecoveryJson.decodeFromString<CalendarMutationRecoveryState>(encoded)
}.getOrNull()?.takeIf { recovery -> recovery.accountScope == expectedAccountScope }?.postcondition

/** Keeps record presence and decoded identity from the same recovery read. */
internal data class CalendarRecoverySnapshot(
    val encoded: String?,
    val postcondition: CalendarMutationPostcondition?,
) {
    fun readyToVerify(loaded: Boolean, requestRunning: Boolean): Boolean = loaded && !requestRunning
    val hasRecord: Boolean get() = encoded != null
    val unreadable: Boolean get() = hasRecord && postcondition == null
}

internal fun calendarRecoverySnapshot(encoded: String?, accountScope: String): CalendarRecoverySnapshot =
    CalendarRecoverySnapshot(encoded, encoded?.let { decodeCalendarMutationRecoveryState(it, accountScope) })
