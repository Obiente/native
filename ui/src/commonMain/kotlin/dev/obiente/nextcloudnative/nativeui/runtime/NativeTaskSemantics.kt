package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

/**
 * Independent structural evidence required before a boolean field can acquire task-completion
 * meaning. Boolean values only describe two states; their field label does not prove those states
 * mean incomplete and completed.
 */
internal fun ResourceSpec.hasIndependentNativeTaskEvidence(
    completionField: FieldSpec,
): Boolean {
    val resourceTokens = listOf(id, name)
        .flatMap(String::nativeTaskSemanticTokens)
        .toSet()
    if (resourceTokens.any(NATIVE_TASK_RESOURCE_WORDS::contains)) return true

    return fields
        .asSequence()
        .filterNot { field -> field.id == completionField.id }
        .any { field ->
            field.id.nativeTaskSemanticKey() in NATIVE_TASK_STRUCTURAL_FIELD_NAMES ||
                field.label.nativeTaskSemanticKey() in NATIVE_TASK_STRUCTURAL_FIELD_NAMES
        }
}

internal fun FieldSpec.requiresIndependentNativeTaskEvidence(): Boolean =
    id.nativeTaskSemanticKey() in AMBIGUOUS_TASK_COMPLETION_FIELD_NAMES ||
        label.nativeTaskSemanticKey() in AMBIGUOUS_TASK_COMPLETION_FIELD_NAMES

private fun String.nativeTaskSemanticTokens(): List<String> =
    buildString(length + 4) {
        this@nativeTaskSemanticTokens.forEachIndexed { index, character ->
            if (
                index > 0 &&
                character.isUpperCase() &&
                this@nativeTaskSemanticTokens[index - 1].isLowerCase()
            ) {
                append(' ')
            }
            append(if (character.isLetterOrDigit()) character.lowercaseChar() else ' ')
        }
    }
        .split(' ')
        .filter(String::isNotBlank)

private fun String.nativeTaskSemanticKey(): String =
    lowercase().filter(Char::isLetterOrDigit)

private val NATIVE_TASK_RESOURCE_WORDS = setOf(
    "assignment",
    "assignments",
    "chore",
    "chores",
    "duty",
    "duties",
    "rota",
    "rotas",
    "task",
    "tasks",
    "todo",
    "todos",
    "vtodo",
)

private val NATIVE_TASK_STRUCTURAL_FIELD_NAMES = setOf(
    "assignedto",
    "assignee",
    "assigneeid",
    "completionpercent",
    "deadline",
    "due",
    "duedate",
    "effort",
    "effortpoints",
    "parenttask",
    "parenttaskid",
    "percentcomplete",
    "points",
    "priority",
    "relatedto",
)

private val AMBIGUOUS_TASK_COMPLETION_FIELD_NAMES = setOf("state", "status")
