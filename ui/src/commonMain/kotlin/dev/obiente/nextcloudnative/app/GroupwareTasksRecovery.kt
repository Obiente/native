package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class TaskDraft(
    val title: String,
    val dueDate: String,
    val description: String,
    val completed: Boolean,
    /** Priority, progress, start, tags and parent; null in records written before they existed. */
    val extras: TaskExtras? = null,
) {
    fun normalized(): TaskDraft = copy(
        title = title.trim().normalizeGroupwareTextLineEndings(),
        dueDate = dueDate.trim(),
        description = description.normalizeGroupwareTextLineEndings(),
        extras = extras?.normalized(),
    )

    fun compactDueDateOrNull(): String? = dueDate.trim().takeIf(String::isNotBlank)?.let { value ->
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "The due date is invalid." }
        value.replace("-", "").also { compact ->
            require(isValidGroupwareTaskDueDate(compact)) { "The due date is invalid." }
        }
    }
}

internal enum class TaskRecoveryVerification {
    Applied,
    Unapplied,
    Unknown,
}

@Serializable
internal sealed interface TaskMutationPostcondition {
    val href: String
    fun isSatisfiedBy(response: NextcloudApiResponse): Boolean
    fun verify(response: NextcloudApiResponse): TaskRecoveryVerification = when {
        isSatisfiedBy(response) -> TaskRecoveryVerification.Applied
        isProvenUnappliedBy(response) -> TaskRecoveryVerification.Unapplied
        else -> TaskRecoveryVerification.Unknown
    }
    fun isProvenUnappliedBy(response: NextcloudApiResponse): Boolean

    @Serializable
    data class Upsert(
        override val href: String,
        val calendarHref: String,
        val expectedUid: String,
        val expectedRecurrenceId: String? = null,
        val previousEtag: String?,
        val draft: TaskDraft,
        val expectedDue: String? = draft.compactDueDateOrNull(),
    ) : TaskMutationPostcondition {
        override fun isSatisfiedBy(response: NextcloudApiResponse): Boolean {
            if (response.status !in 200..299) return false
            val expected = draft.normalized()
            val task = parseGroupwareTasksFromContent(
                calendarHref = calendarHref,
                href = href,
                etag = response.etag,
                content = response.body.decodeToString(),
            ).singleOrNull { candidate ->
                candidate.uid == expectedUid && candidate.recurrenceId == expectedRecurrenceId
            } ?: return false
            if (expected.extras != null) {
                val stored = task.extras().normalized()
                val wanted = expected.extras
                if (stored.priority != wanted.priority || stored.startDate.taskCompactDate() != wanted.startDate.taskCompactDate() ||
                    stored.categories != wanted.categories || stored.parentUid != wanted.parentUid
                ) {
                    return false
                }
            }
            return task.href == href &&
                task.uid == expectedUid &&
                task.recurrenceId == expectedRecurrenceId &&
                task.title == expected.title &&
                task.due == expectedDue &&
                task.completed == expected.completed &&
                task.description.orEmpty() == expected.description
        }

        override fun isProvenUnappliedBy(response: NextcloudApiResponse): Boolean = when {
            previousEtag == null -> groupwareDeleteResponseProvesAbsence(response.status)
            response.status in 200..299 -> response.etag == previousEtag
            else -> false
        }
    }

    @Serializable
    data class Delete(
        override val href: String,
        val previousEtag: String? = null,
    ) : TaskMutationPostcondition {
        override fun isSatisfiedBy(response: NextcloudApiResponse): Boolean =
            groupwareDeleteResponseProvesAbsence(response.status)

        override fun isProvenUnappliedBy(response: NextcloudApiResponse): Boolean =
            previousEtag != null && response.status in 200..299 && response.etag == previousEtag
    }
}

@Serializable
internal data class TaskMutationRecoveryState(
    val accountScope: String,
    val postcondition: TaskMutationPostcondition,
) {
    init {
        require(accountScope.isCanonicalGroupwareMutationAccountScope())
    }
}

private val taskMutationRecoveryJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun TaskMutationRecoveryState.encodeForSavedState(): String =
    taskMutationRecoveryJson.encodeToString(this)

internal fun decodeTaskMutationRecoveryState(
    encoded: String,
    expectedAccountScope: String,
): TaskMutationPostcondition? = runCatching {
    taskMutationRecoveryJson.decodeFromString<TaskMutationRecoveryState>(encoded)
}.getOrNull()?.takeIf { recovery -> recovery.accountScope == expectedAccountScope }?.postcondition

/** The task as an editor draft, with its due date in the editor's `YYYY-MM-DD` form. */
internal fun GroupwareTask.toTaskDraft(): TaskDraft = TaskDraft(
    title = title,
    dueDate = due?.take(8)?.takeIf { it.length == 8 }?.let { "${it.take(4)}-${it.substring(4, 6)}-${it.takeLast(2)}" }.orEmpty(),
    description = description.orEmpty(),
    completed = completed,
)
