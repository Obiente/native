package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.putJsonArray

/*
 * Submitting answers to a questionnaire: a survey, a poll, a form, a quiz.
 *
 * The pattern is recognized from data, not from an app identity:
 * - the record holds a `questions` list whose entries have an identity, a prompt, and a type;
 * - the contract declares a POST below the record's own item route (`.../{id}/<responses>`) whose
 *   body has exactly one property declared as a map of string arrays, which carries the answers
 *   keyed by question identity.
 *
 * Question types map by wording (short or long text, single or multiple choice, dropdown, scale,
 * date, time). Choice answers send option identities. Answer formats the contract and the data do
 * not determine (combined date and time, ranges, files, grids, ranking, colors) are not answered;
 * a questionnaire whose required question uses one explains that instead of offering a submit.
 */

internal enum class NativeQuestionKind {
    Short, Long, Multiple, Single, Dropdown, Date, Time, Scale, Unsupported,
}

internal data class NativeQuestion(
    val id: String,
    val text: String,
    val description: String,
    val kind: NativeQuestionKind,
    val required: Boolean,
    val options: List<NativeEditorChoice> = emptyList(),
    val scaleLowest: Int = 1,
    val scaleHighest: Int = 5,
    val minOptions: Int? = null,
    val maxOptions: Int? = null,
)

internal data class NativeQuestionnairePlan(
    val recordId: String,
    val title: String,
    val description: String,
    val questions: List<NativeQuestion>,
    val submit: NativeContractAction?,
    val answersField: String,
    /** Why this person cannot respond here, or null when [submit] is available. */
    val unavailableReason: String?,
) {
    val answerable: List<NativeQuestion> get() = questions.filter { it.kind != NativeQuestionKind.Unsupported }
}

internal sealed interface NativeQuestionnaireResult {
    data class Ready(val request: NativeActionRequest.Submit) : NativeQuestionnaireResult

    data class Invalid(val errors: Map<String, String>) : NativeQuestionnaireResult

    data object Unavailable : NativeQuestionnaireResult
}

internal fun nativeQuestionnairePlan(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    record: NativeRecord,
): NativeQuestionnairePlan? {
    if (!record.actionSafeIdentity || !record.actionBindingProvenanceValid) return null
    val recordId = record.id.takeIf(String::isSafeContractPathValue) ?: return null
    val rawQuestions = QUESTION_LISTS.firstNotNullOfOrNull(record::valueNamed) ?: return null
    val submission = schema.questionnaireSubmission(resource) ?: return null
    val (submit, answersField) = submission
    val segments = submit.action.binding.path.routeSegments()
    val reference = parentReferenceKey(segments[segments.size - 3], submit.placeholders.single())
    val questions = parseQuestions(rawQuestions, recordId, reference) ?: return null
    val reason = when {
        // A permission list that omits responding means the person may only read.
        record.permissionNames()?.none { it in RESPOND_PERMISSIONS } == true -> "You cannot respond to this form."
        CLOSED_FLAGS.any { record.valueNamed(it)?.toBooleanStrictOrNull() == true } || record.inDeclaredClosedState(resource) ->
            "This form is closed."
        LIMIT_FLAGS.any { record.valueNamed(it)?.toBooleanStrictOrNull() == true } -> "This form accepts no more responses."
        RESPOND_FLAGS.firstNotNullOfOrNull(record::valueNamed)?.toBooleanStrictOrNull() != true ->
            "This form does not accept a response from you right now."
        questions.isEmpty() -> "This form has no questions yet."
        questions.any { it.required && it.kind == NativeQuestionKind.Unsupported } ->
            "A required question uses a type that cannot be answered here yet, such as a file upload or a grid."
        else -> null
    }
    return NativeQuestionnairePlan(
        recordId = recordId,
        title = TITLES.firstNotNullOfOrNull(record::valueNamed) ?: "Form",
        description = record.valueNamed("description").orEmpty(),
        questions = questions,
        submit = submit.takeIf { reason == null },
        answersField = answersField,
        unavailableReason = reason,
    )
}

/**
 * Whether a state field of [resource] holds a value the contract itself labels closed. A numeric
 * state without such labels says nothing about which number means closed, so it is not guessed.
 */
private fun NativeRecord.inDeclaredClosedState(resource: ResourceSpec): Boolean = resource.fields.any { field ->
    val labels = field.enumLabels ?: return@any false
    field.id.contractKey() in STATE_FIELDS &&
        values[field.id]?.trim()?.let(labels::get)?.contractKey() in CLOSED_STATE_LABELS
}

/**
 * The single trusted POST `<item route>/<segment>` of [resource] whose body declares exactly one
 * answer map, together with that property's name.
 */
private fun NativeAppSchema.questionnaireSubmission(resource: ResourceSpec): Pair<NativeContractAction, String>? {
    val itemRoutes = actions.filter { action ->
        action.resourceId == resource.id && action.binding.method == HttpMethod.GET && action.risk == ActionRisk.readOnly &&
            action.binding.path.routeSegments().let { it.isNotEmpty() && it.last().isRoutePlaceholder() }
    }.map { it.binding.path.routeSegments().map { segment -> segment.takeUnless(String::isRoutePlaceholder) } }.distinct()
    val matches = itemRoutes.flatMap { item ->
        actions.mapNotNull { action ->
            val segments = action.binding.path.routeSegments()
            if (segments.size != item.size + 1 || segments.last().isRoutePlaceholder()) return@mapNotNull null
            val shape = item + segments.last()
            val write = contractWrite(NativeContractWriteKind.Create, shape)?.takeIf { it.action.id == action.id }
                ?: return@mapNotNull null
            if (write.placeholders.size != 1) return@mapNotNull null
            val answers = write.action.answerMapField() ?: return@mapNotNull null
            write to answers
        }
    }
    return matches.distinctBy { it.first.action.id }.singleOrNull()
}

private fun ActionSpec.answerMapField(): String? {
    val properties = (binding.bodySchema as? JsonObject)?.get("properties") as? JsonObject ?: return null
    return properties.entries.filter { (name, schema) -> name in binding.bodyFieldNames && schema.isStringArrayMap() }
        .singleOrNull()?.key
}

private fun JsonElement.isStringArrayMap(): Boolean {
    val schema = this as? JsonObject ?: return false
    if ((schema["type"] as? JsonPrimitive)?.contentOrNull != "object") return false
    val values = schema["additionalProperties"] as? JsonObject ?: return false
    val items = values["items"] as? JsonObject ?: return false
    return (values["type"] as? JsonPrimitive)?.contentOrNull == "array" &&
        (items["type"] as? JsonPrimitive)?.contentOrNull == "string"
}

private fun NativeRecord.permissionNames(): List<String>? {
    val raw = valueNamed("permissions") ?: return null
    val parsed = raw.contractJsonOrNull() as? JsonArray
    return (parsed?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: raw.split(',')).map(String::contractKey)
}

private fun parseQuestions(raw: String, recordId: String, ownerKey: String): List<NativeQuestion>? {
    val array = raw.contractJsonOrNull() as? JsonArray ?: return null
    if (array.size > MAX_QUESTIONS) return null
    val parsed = array.map { element ->
        val question = element as? JsonObject ?: return null
        val byKey = question.entries.associate { it.key.contractKey() to it.value }
        val id = (byKey["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isSafeContractPathValue) ?: return null
        // A question that names its questionnaire must name this one.
        val owner = (byKey[ownerKey] as? JsonPrimitive)?.contentOrNull
        if (owner != null && owner != recordId) return null
        val order = (byKey["order"] as? JsonPrimitive)?.longOrNull ?: 0L
        order to byKey.toQuestion(id)
    }
    if (parsed.map { it.second.id }.distinct().size != parsed.size) return null
    return parsed.sortedBy { it.first }.map { it.second }
}

private fun Map<String, JsonElement>.toQuestion(id: String): NativeQuestion {
    fun text(vararg keys: String) = keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }.orEmpty()
    val settings = (this["extrasettings"] ?: this["settings"]) as? JsonObject ?: JsonObject(emptyMap())
    val settingsByKey = settings.entries.associate { it.key.contractKey() to it.value as? JsonPrimitive }
    fun setting(vararg keys: String) = keys.firstNotNullOfOrNull { settingsByKey[it] }
    val ranged = setting("daterange", "timerange", "range")?.booleanOrNull == true
    val declared = when (text("type").contractKey()) {
        "short", "text", "shorttext", "string" -> NativeQuestionKind.Short
        "long", "longtext", "paragraph", "textarea" -> NativeQuestionKind.Long
        "multiple", "checkbox", "checkboxes", "multiplechoice" -> NativeQuestionKind.Multiple
        "multipleunique", "radio", "single", "singlechoice" -> NativeQuestionKind.Single
        "dropdown", "select" -> NativeQuestionKind.Dropdown
        "date" -> NativeQuestionKind.Date
        "time" -> NativeQuestionKind.Time
        "linearscale", "scale", "rating" -> NativeQuestionKind.Scale
        else -> NativeQuestionKind.Unsupported
    }
    val options = (this["options"] as? JsonArray).orEmpty().mapNotNull { element ->
        val option = (element as? JsonObject)?.entries?.associate { it.key.contractKey() to it.value } ?: return@mapNotNull null
        val optionType = (option["optiontype"] as? JsonPrimitive)?.contentOrNull
        if (optionType != null && optionType != "choice") return@mapNotNull null
        val optionId = (option["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isSafeContractPathValue)
            ?: return@mapNotNull null
        val label = listOf("text", "label", "title", "name").firstNotNullOfOrNull { (option[it] as? JsonPrimitive)?.contentOrNull }
        NativeEditorChoice(optionId, label.orEmpty().take(500))
    }
    val choice = declared in setOf(NativeQuestionKind.Multiple, NativeQuestionKind.Single, NativeQuestionKind.Dropdown)
    val kind = when {
        ranged && declared in setOf(NativeQuestionKind.Date, NativeQuestionKind.Time) -> NativeQuestionKind.Unsupported
        choice && options.isEmpty() -> NativeQuestionKind.Unsupported
        else -> declared
    }
    val lowest = setting("optionslowest", "lowest", "min")?.longOrNull?.toInt()?.takeIf { it in 0..1 } ?: 1
    val highest = setting("optionshighest", "highest", "max")?.longOrNull?.toInt()?.takeIf { it in 2..10 } ?: 5
    return NativeQuestion(
        id = id,
        text = text("text", "question", "title", "label").ifBlank { "Question" }.take(1000),
        description = text("description").take(2000),
        kind = kind,
        required = listOf("isrequired", "required", "mandatory").any { (this[it] as? JsonPrimitive)?.booleanOrNull == true },
        options = options,
        scaleLowest = lowest,
        scaleHighest = highest,
        minOptions = setting("optionslimitmin", "minoptions")?.longOrNull?.toInt()?.takeIf { it > 0 },
        maxOptions = setting("optionslimitmax", "maxoptions")?.longOrNull?.toInt()?.takeIf { it > 0 },
    )
}

/** The values one draft answer sends; a multiple-choice draft is comma-separated option IDs. */
internal fun NativeQuestion.answerValues(draft: String): List<String> = when (kind) {
    NativeQuestionKind.Multiple -> draft.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    NativeQuestionKind.Long -> listOf(draft).filter(String::isNotBlank)
    else -> listOf(draft.trim()).filter(String::isNotEmpty)
}

internal fun NativeQuestion.validate(draft: String): String? {
    val values = answerValues(draft)
    if (values.isEmpty()) return if (required) "An answer is required." else null
    val optionIds = options.mapTo(hashSetOf(), NativeEditorChoice::id)
    return when (kind) {
        NativeQuestionKind.Multiple -> when {
            values.any { it !in optionIds } -> "Choose from the listed options."
            minOptions != null && values.size < minOptions -> "Choose at least $minOptions."
            maxOptions != null && values.size > maxOptions -> "Choose at most $maxOptions."
            else -> null
        }
        NativeQuestionKind.Single, NativeQuestionKind.Dropdown ->
            if (values.single() !in optionIds) "Choose one of the options." else null
        NativeQuestionKind.Scale ->
            if (values.single().toIntOrNull() !in scaleLowest..scaleHighest) "Choose $scaleLowest to $scaleHighest." else null
        NativeQuestionKind.Date -> if (!values.single().isIsoCalendarDate()) "Use YYYY-MM-DD." else null
        NativeQuestionKind.Time -> if (!values.single().isClockTime()) "Use HH:MM." else null
        NativeQuestionKind.Short, NativeQuestionKind.Long -> null
        NativeQuestionKind.Unsupported -> "This question cannot be answered here."
    }
}

internal fun NativeQuestionnairePlan.submitRequest(drafts: Map<String, String>): NativeQuestionnaireResult {
    val submit = submit ?: return NativeQuestionnaireResult.Unavailable
    val errors = answerable.mapNotNull { question ->
        question.validate(drafts[question.id].orEmpty())?.let { question.id to it }
    }.toMap()
    if (errors.isNotEmpty()) return NativeQuestionnaireResult.Invalid(errors)
    val answers = buildJsonObject {
        answerable.forEach { question ->
            val values = question.answerValues(drafts[question.id].orEmpty())
            if (values.isNotEmpty()) putJsonArray(question.id) { values.forEach { add(JsonPrimitive(it)) } }
        }
    }
    return NativeQuestionnaireResult.Ready(
        NativeActionRequest.Submit(
            submit.action,
            mapOf(submit.placeholders.single() to recordId, answersField to answers.toString()),
            confirmed = true,
        ),
    )
}

private val QUESTION_LISTS = listOf("questions")
private val TITLES = listOf("title", "name")
private val RESPOND_PERMISSIONS = setOf("submit", "respond", "answer", "reply")
private val RESPOND_FLAGS = listOf("cansubmit", "canrespond", "cananswer")
private val CLOSED_FLAGS = listOf("isclosed", "closed", "isexpired", "expired", "isarchived", "archived")
private val STATE_FIELDS = setOf("state", "status")
private val CLOSED_STATE_LABELS = setOf("closed", "expired", "archived")
private val LIMIT_FLAGS = listOf("ismaxsubmissionsreached", "maxsubmissionsreached", "limitreached")
private const val MAX_QUESTIONS = 300
