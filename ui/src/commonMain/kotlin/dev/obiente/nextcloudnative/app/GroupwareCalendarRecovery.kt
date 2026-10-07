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
                candidate.uid == expectedUid && candidate.recurrenceId == null
            } ?: return false
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
