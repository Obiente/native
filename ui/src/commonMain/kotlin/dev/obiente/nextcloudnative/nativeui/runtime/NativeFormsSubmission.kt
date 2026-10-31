package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.putJsonArray

/*
 * Reviewed Forms submissions.
 *
 * The Forms OpenAPI document declares `answers` as an object of string arrays keyed by question
 * ID. The reviewed server source defines the values:
 * - SubmissionService::validateSubmission requires answers to required questions, accepts one
 *   value except for multiple-choice questions, takes option IDs for choice questions, an integer
 *   in the configured range for linear scales, and Y-m-d, Y-m-d H:i, or H:i for date, date and
 *   time, and time questions:
 *   https://github.com/nextcloud/forms/blob/v5.4.1/lib/Service/SubmissionService.php
 *   https://github.com/nextcloud/forms/blob/v5.4.1/lib/Constants.php
 * - ApiController::newSubmission refuses closed forms, repeated submissions, and forms without
 *   the submit permission:
 *   https://github.com/nextcloud/forms/blob/v5.4.1/lib/Controller/ApiController.php
 *
 * File, grid, ranking, color, and date or time range questions are not answered natively yet.
 * A form whose required question uses one of them explains that instead of offering a submit.
 */

internal enum class NativeFormsQuestionType {
    Short, Long, Multiple, MultipleUnique, Dropdown, Date, DateTime, Time, LinearScale, Unsupported,
}

internal data class NativeFormsQuestion(
    val id: Long,
    val text: String,
    val description: String,
    val type: NativeFormsQuestionType,
    val required: Boolean,
    val options: List<NativeReviewedChoice> = emptyList(),
    val scaleLowest: Int = 1,
    val scaleHighest: Int = 5,
    val minOptions: Int? = null,
    val maxOptions: Int? = null,
)

internal data class NativeFormsSubmissionPlan(
    val formId: Long,
    val title: String,
    val description: String,
    val questions: List<NativeFormsQuestion>,
    val submit: NativeReviewedAction?,
    /** Why this person cannot respond here, or null when [submit] is available. */
    val unavailableReason: String?,
) {
    val answerable: List<NativeFormsQuestion> get() = questions.filter { it.type != NativeFormsQuestionType.Unsupported }
}

internal sealed interface NativeFormsSubmissionResult {
    data class Ready(val request: NativeActionRequest.Submit) : NativeFormsSubmissionResult

    data class Invalid(val errors: Map<Long, String>) : NativeFormsSubmissionResult

    data object Unavailable : NativeFormsSubmissionResult
}

internal fun NativeAppSchema.supportsReviewedFormsSubmissions(): Boolean =
    app.id == "forms" && app.reviewedMajorMinor()?.first == 5

internal fun nativeFormsSubmissionPlan(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    record: NativeRecord,
): NativeFormsSubmissionPlan? {
    if (!schema.supportsReviewedFormsSubmissions() || resource.id != "forms") return null
    if (!record.actionSafeIdentity || !record.actionBindingProvenanceValid) return null
    val formId = record.id.reviewedPositiveId() ?: return null
    val questions = parseFormsQuestions(record.values["questions"], formId) ?: return null
    val action = schema.reviewedAction(HttpMethod.POST, SUBMISSIONS_ROUTE, setOf("answers"))
        ?.takeIf { it.placeholders.size == 1 }
    val permissions = (record.values["permissions"].reviewedJsonOrNull() as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: record.values["permissions"]?.split(',')?.map(String::trim)
        ?: emptyList()
    val reason = when {
        action == null -> "This server's contract does not offer responses."
        record.values["state"]?.trim() != "0" -> "This form is closed."
        "submit" !in permissions -> "You cannot respond to this form."
        record.values["isMaxSubmissionsReached"]?.trim() == "true" -> "This form accepts no more responses."
        record.values["canSubmit"]?.trim()?.toBooleanStrictOrNull() != true -> "You have already responded to this form."
        questions.isEmpty() -> "This form has no questions yet."
        questions.any { it.required && it.type == NativeFormsQuestionType.Unsupported } ->
            "A required question uses a type that cannot be answered here yet, such as a file upload or a grid."
        else -> null
    }
    return NativeFormsSubmissionPlan(
        formId = formId,
        title = record.values["title"]?.trim()?.takeIf(String::isNotEmpty) ?: "Form",
        description = record.values["description"].orEmpty(),
        questions = questions,
        submit = action.takeIf { reason == null },
        unavailableReason = reason,
    )
}

private fun parseFormsQuestions(raw: String?, formId: Long): List<NativeFormsQuestion>? {
    val array = raw.reviewedJsonOrNull() as? JsonArray ?: return null
    if (array.size > MAX_FORMS_QUESTIONS) return null
    val parsed = array.map { element ->
        val question = element as? JsonObject ?: return null
        val id = (question["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
        val owner = (question["formId"] as? JsonPrimitive)?.longOrNull
        if (owner != null && owner != formId) return null
        val order = (question["order"] as? JsonPrimitive)?.longOrNull ?: 0L
        order to question.toFormsQuestion(id)
    }
    if (parsed.map { it.second.id }.distinct().size != parsed.size) return null
    return parsed.sortedBy { it.first }.map { it.second }
}

private fun JsonObject.toFormsQuestion(id: Long): NativeFormsQuestion {
    fun text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val settings = this["extraSettings"] as? JsonObject ?: JsonObject(emptyMap())
    fun setting(key: String) = settings[key] as? JsonPrimitive
    val ranged = setting("dateRange")?.booleanOrNull == true || setting("timeRange")?.booleanOrNull == true
    val declared = when (text("type")) {
        "short" -> NativeFormsQuestionType.Short
        "long" -> NativeFormsQuestionType.Long
        "multiple" -> NativeFormsQuestionType.Multiple
        "multiple_unique" -> NativeFormsQuestionType.MultipleUnique
        "dropdown" -> NativeFormsQuestionType.Dropdown
        "date" -> NativeFormsQuestionType.Date
        "datetime" -> NativeFormsQuestionType.DateTime
        "time" -> NativeFormsQuestionType.Time
        "linearscale" -> NativeFormsQuestionType.LinearScale
        else -> NativeFormsQuestionType.Unsupported
    }
    val options = (this["options"] as? JsonArray).orEmpty().mapNotNull { element ->
        val option = element as? JsonObject ?: return@mapNotNull null
        val optionType = (option["optionType"] as? JsonPrimitive)?.contentOrNull
        if (optionType != null && optionType != "choice") return@mapNotNull null
        val optionId = (option["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        NativeReviewedChoice(optionId.toString(), (option["text"] as? JsonPrimitive)?.contentOrNull?.take(500).orEmpty())
    }
    val choice = declared in setOf(
        NativeFormsQuestionType.Multiple, NativeFormsQuestionType.MultipleUnique, NativeFormsQuestionType.Dropdown,
    )
    val type = when {
        ranged && declared in setOf(NativeFormsQuestionType.Date, NativeFormsQuestionType.Time) ->
            NativeFormsQuestionType.Unsupported
        choice && options.isEmpty() -> NativeFormsQuestionType.Unsupported
        else -> declared
    }
    val lowest = setting("optionsLowest")?.longOrNull?.toInt()?.takeIf { it in 0..1 } ?: 1
    val highest = setting("optionsHighest")?.longOrNull?.toInt()?.takeIf { it in 2..10 } ?: 5
    return NativeFormsQuestion(
        id = id,
        text = text("text").ifBlank { "Question" }.take(1000),
        description = text("description").take(2000),
        type = type,
        required = (this["isRequired"] as? JsonPrimitive)?.booleanOrNull == true,
        options = options,
        scaleLowest = lowest,
        scaleHighest = highest,
        minOptions = setting("optionsLimitMin")?.longOrNull?.toInt()?.takeIf { it > 0 },
        maxOptions = setting("optionsLimitMax")?.longOrNull?.toInt()?.takeIf { it > 0 },
    )
}

/** The values one draft answer sends; a multiple-choice draft is comma-separated option IDs. */
internal fun NativeFormsQuestion.answerValues(draft: String): List<String> = when (type) {
    NativeFormsQuestionType.Multiple -> draft.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    NativeFormsQuestionType.Long -> listOf(draft).filter(String::isNotBlank)
    else -> listOf(draft.trim()).filter(String::isNotEmpty)
}

internal fun NativeFormsQuestion.validate(draft: String): String? {
    val values = answerValues(draft)
    if (values.isEmpty()) return if (required) "An answer is required." else null
    val optionIds = options.mapTo(hashSetOf(), NativeReviewedChoice::id)
    return when (type) {
        NativeFormsQuestionType.Multiple -> when {
            values.any { it !in optionIds } -> "Choose from the listed options."
            minOptions != null && values.size < minOptions -> "Choose at least $minOptions."
            maxOptions != null && values.size > maxOptions -> "Choose at most $maxOptions."
            else -> null
        }
        NativeFormsQuestionType.MultipleUnique, NativeFormsQuestionType.Dropdown ->
            if (values.single() !in optionIds) "Choose one of the options." else null
        NativeFormsQuestionType.LinearScale ->
            if (values.single().toIntOrNull() !in scaleLowest..scaleHighest) "Choose $scaleLowest to $scaleHighest." else null
        NativeFormsQuestionType.Date -> if (!values.single().isCospendDate()) "Use YYYY-MM-DD." else null
        NativeFormsQuestionType.DateTime -> values.single().let { value ->
            if (value.length != 16 || value[10] != ' ' || !value.take(10).isCospendDate() || !value.drop(11).isFormsTime()) {
                "Use YYYY-MM-DD HH:MM."
            } else {
                null
            }
        }
        NativeFormsQuestionType.Time -> if (!values.single().isFormsTime()) "Use HH:MM." else null
        NativeFormsQuestionType.Short, NativeFormsQuestionType.Long -> null
        NativeFormsQuestionType.Unsupported -> "This question cannot be answered here."
    }
}

internal fun NativeFormsSubmissionPlan.submitRequest(drafts: Map<Long, String>): NativeFormsSubmissionResult {
    val submit = submit ?: return NativeFormsSubmissionResult.Unavailable
    val errors = answerable.mapNotNull { question ->
        question.validate(drafts[question.id].orEmpty())?.let { question.id to it }
    }.toMap()
    if (errors.isNotEmpty()) return NativeFormsSubmissionResult.Invalid(errors)
    val answers = buildJsonObject {
        answerable.forEach { question ->
            val values = question.answerValues(drafts[question.id].orEmpty())
            if (values.isNotEmpty()) putJsonArray(question.id.toString()) { values.forEach { add(JsonPrimitive(it)) } }
        }
    }
    return NativeFormsSubmissionResult.Ready(
        NativeActionRequest.Submit(
            submit.action,
            mapOf(submit.placeholders.single() to formId.toString(), "answers" to answers.toString()),
            confirmed = true,
        ),
    )
}

private fun String.isFormsTime(): Boolean {
    if (length != 5 || this[2] != ':') return false
    val hour = take(2).toIntOrNull() ?: return false
    val minute = drop(3).toIntOrNull() ?: return false
    return hour in 0..23 && minute in 0..59 && take(2).all(Char::isDigit) && drop(3).all(Char::isDigit)
}

private val SUBMISSIONS_ROUTE = reviewedRoute("/ocs/v2.php/apps/forms/api/v3/forms/{}/submissions")
private const val MAX_FORMS_QUESTIONS = 300
