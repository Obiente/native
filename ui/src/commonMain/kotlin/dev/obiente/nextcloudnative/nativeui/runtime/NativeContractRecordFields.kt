package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.isExactDynamicIntegerArraySchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Typed record fields derived from a write action's own request-body schema.
 *
 * The schema decides what can be sent: the property names, their JSON types, enums, formats,
 * limits, and the required list. The wording of a property name only decides how a valid value
 * is presented (an amount is money, `date` is a calendar date, `payer` picks one person) and where
 * choices come from (people and categories the parent record or a related resource lists). A
 * property whose meaning is recognized but whose encoding the schema does not determine (for
 * example several people packed into one string) is reported as not encodable, so the caller can
 * leave the write unavailable instead of guessing.
 */

internal enum class NativeRecordFieldRole { Title, Notes, Money, Date, Person, People, Reference, Choice, Other }

internal data class NativeFieldOption(val id: String, val label: String, val active: Boolean = true)

internal sealed interface NativeRecordFieldInput {
    data class Text(val multiline: Boolean, val maxLength: Int?) : NativeRecordFieldInput

    data class Number(val integer: Boolean, val minimum: Double?, val maximum: Double?) : NativeRecordFieldInput

    data object Date : NativeRecordFieldInput

    data object Toggle : NativeRecordFieldInput

    /** One value out of [options]; sent as the option ID. */
    data class Choice(val options: List<NativeFieldOption>) : NativeRecordFieldInput

    /** Several values out of [options]; sent as a JSON array of integer IDs, as the schema declares. */
    data class MultiChoice(val options: List<NativeFieldOption>) : NativeRecordFieldInput
}

internal data class NativeRecordField(
    val name: String,
    val label: String,
    val role: NativeRecordFieldRole,
    val required: Boolean,
    /** Null when the meaning is recognized but the schema does not say how to send a value. */
    val input: NativeRecordFieldInput?,
) {
    /** Required fields and fields with a recognized role are edited; other optional fields are left to the server. */
    val shown: Boolean get() = required || role != NativeRecordFieldRole.Other
}

/** Where reference choices can come from: the parent record and resources loaded beside it. */
internal data class NativeFieldChoiceSources(
    val parent: NativeRecord?,
    val related: Map<String, List<NativeRecord>>,
)

/** Body fields a person can edit, in contract order, or null when the action declares no schema. */
internal fun ActionSpec.nativeRecordFields(
    excludedNames: Set<String>,
    sources: NativeFieldChoiceSources,
): List<NativeRecordField>? {
    val body = binding.bodySchema as? JsonObject ?: return null
    val properties = body["properties"] as? JsonObject ?: return null
    val required = (body["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet() +
        binding.requiredBodyFieldNames
    val excluded = excludedNames.mapTo(hashSetOf(), String::contractKey)
    return properties.entries.mapNotNull { (name, rawSchema) ->
        if (name !in binding.bodyFieldNames || name.contractKey() in excluded) return@mapNotNull null
        val schema = rawSchema.nonNullSchema() ?: return@mapNotNull NativeRecordField(
            name, name.fieldLabel(), name.roleByName(), name in required, input = null,
        )
        if ((schema["readOnly"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
        nativeRecordField(name, schema, name in required, sources)
    }
}

private fun nativeRecordField(
    name: String,
    schema: JsonObject,
    required: Boolean,
    sources: NativeFieldChoiceSources,
): NativeRecordField {
    val key = name.contractKey()
    val type = schema.schemaType()
    val label = (schema["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.take(80)
        ?: name.fieldLabel()
    fun field(role: NativeRecordFieldRole, input: NativeRecordFieldInput?) = NativeRecordField(name, label, role, required, input)
    val enum = (schema["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { value -> value is JsonNull }?.content }
    if (!enum.isNullOrEmpty()) return field(NativeRecordFieldRole.Choice, NativeRecordFieldInput.Choice(enum.map { NativeFieldOption(it, it.fieldLabel()) }))
    val stem = key.referenceStem()
    val people = key in PEOPLE_WORDS || stem in PEOPLE_WORDS
    val person = key in PERSON_WORDS || stem in PERSON_WORDS
    if (type == "array") {
        val options = sources.options(stem, people || person)
        val role = if (people || person) NativeRecordFieldRole.People else NativeRecordFieldRole.Reference
        return when {
            options == null -> field(if (people || person) role else NativeRecordFieldRole.Other, null)
            schema.isExactDynamicIntegerArraySchema() && options.all { it.id.toLongOrNull() != null } ->
                field(role, NativeRecordFieldInput.MultiChoice(options))
            else -> field(role, null)
        }
    }
    if (people) return field(NativeRecordFieldRole.People, null)
    if (type in setOf("integer", "string") && (person || key != stem)) {
        val options = sources.options(stem, person)
        val integer = type == "integer"
        if (options != null && (!integer || options.all { it.id.toLongOrNull() != null })) {
            return field(if (person) NativeRecordFieldRole.Person else NativeRecordFieldRole.Reference, NativeRecordFieldInput.Choice(options))
        }
        if (person) return field(NativeRecordFieldRole.Person, null)
    }
    val format = (schema["format"] as? JsonPrimitive)?.contentOrNull
    return when (type) {
        "number", "integer" -> field(
            if (key in MONEY_WORDS) NativeRecordFieldRole.Money else NativeRecordFieldRole.Other,
            NativeRecordFieldInput.Number(
                integer = type == "integer",
                minimum = (schema["minimum"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
                maximum = (schema["maximum"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
            ),
        )
        "boolean" -> field(NativeRecordFieldRole.Other, NativeRecordFieldInput.Toggle)
        "string" -> when {
            format == "date" || (format == null && (key == "date" || key.endsWith("date"))) ->
                field(NativeRecordFieldRole.Date, NativeRecordFieldInput.Date)
            format != null -> field(NativeRecordFieldRole.Other, null)
            else -> field(
                when (key) {
                    in TITLE_WORDS -> NativeRecordFieldRole.Title
                    in NOTE_WORDS -> NativeRecordFieldRole.Notes
                    else -> NativeRecordFieldRole.Other
                },
                NativeRecordFieldInput.Text(
                    multiline = key in NOTE_WORDS,
                    maxLength = (schema["maxLength"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
                ),
            )
        }
        else -> field(name.roleByName(), null)
    }
}

/** Choices for a reference named [stem]: a related resource or a parent field holding its plural. */
private fun NativeFieldChoiceSources.options(stem: String, person: Boolean): List<NativeFieldOption>? {
    val names = buildSet {
        add(stem + "s")
        add(stem + "es")
        if (stem.endsWith("y")) add(stem.dropLast(1) + "ies")
        if (person) addAll(PEOPLE_COLLECTIONS)
    }
    related.entries.firstOrNull { (resourceId, _) -> resourceId.contractKey() in names }?.let { (_, records) ->
        return records.mapNotNull { record -> record.toFieldOption() }.takeIf { it.size == records.size && it.isNotEmpty() }
    }
    val parentValue = parent?.values?.entries?.firstOrNull { it.key.contractKey() in names }?.value ?: return null
    val entries = when (val parsed = parentValue.contractJsonOrNull()) {
        is JsonArray -> parsed.toList()
        is JsonObject -> parsed.values.toList()
        else -> return null
    }
    if (entries.size > MAX_FIELD_OPTIONS) return null
    val options = entries.map { entry -> (entry as? JsonObject)?.toFieldOption() ?: return null }
    return options.takeIf { list -> list.map(NativeFieldOption::id).distinct().size == list.size }
}

private fun NativeRecord.toFieldOption(): NativeFieldOption? {
    if (!actionSafeIdentity) return null
    val label = OPTION_LABELS.firstNotNullOfOrNull(::valueNamed) ?: return null
    val active = OPTION_ACTIVE.firstNotNullOfOrNull(::valueNamed)?.toBooleanStrictOrNull() ?: true
    return NativeFieldOption(id.takeIf(String::isSafeContractPathValue) ?: return null, label.take(200), active)
}

private fun JsonObject.toFieldOption(): NativeFieldOption? {
    val byKey = entries.associate { it.key.contractKey() to it.value }
    val id = (byKey["id"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeIf(String::isSafeContractPathValue)
        ?: return null
    val label = OPTION_LABELS.firstNotNullOfOrNull { (byKey[it] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        ?: return null
    val active = OPTION_ACTIVE.firstNotNullOfOrNull { (byKey[it] as? JsonPrimitive)?.booleanOrNull } ?: true
    return NativeFieldOption(id, label.take(200), active)
}

/** The schema without its `null` alternative, or null when it is a reference or a union. */
private fun JsonElement.nonNullSchema(): JsonObject? {
    val schema = this as? JsonObject ?: return null
    if ("\$ref" in schema) return null
    val alternatives = listOf("oneOf", "anyOf").firstNotNullOfOrNull { schema[it] as? JsonArray } ?: return schema
    val concrete = alternatives.mapNotNull { it as? JsonObject }.filterNot { it.schemaType() == "null" }
    return concrete.singleOrNull()?.takeIf { "\$ref" !in it }
}

private fun JsonObject.schemaType(): String? = when (val type = get("type")) {
    is JsonPrimitive -> type.contentOrNull
    is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.singleOrNull { it != "null" }
    else -> null
}

/** `payerId` and `payer_ids` refer to a `payer`; plain names are their own stem. */
private fun String.referenceStem(): String = when {
    length > 3 && endsWith("ids") -> dropLast(3)
    length > 2 && endsWith("id") -> dropLast(2)
    else -> this
}

private fun String.roleByName(): NativeRecordFieldRole {
    val key = contractKey()
    return when {
        key in PEOPLE_WORDS || key.referenceStem() in PEOPLE_WORDS -> NativeRecordFieldRole.People
        key in PERSON_WORDS || key.referenceStem() in PERSON_WORDS -> NativeRecordFieldRole.Person
        else -> NativeRecordFieldRole.Other
    }
}

/** `paymentModeId` becomes "Payment mode"; `payedFor` becomes "Payed for". */
internal fun String.fieldLabel(): String {
    val words = replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ').trim()
        .split(' ').filter(String::isNotEmpty)
        .let { parts -> if (parts.size > 1 && parts.last().equals("id", ignoreCase = true)) parts.dropLast(1) else parts }
    return words.joinToString(" ").lowercase().replaceFirstChar(Char::uppercaseChar).ifEmpty { this }
}

private val TITLE_WORDS = setOf("what", "name", "title", "label", "subject", "summary")
private val NOTE_WORDS = setOf("comment", "comments", "description", "note", "notes", "details", "body")
private val MONEY_WORDS = setOf("amount", "price", "cost", "total", "sum", "value", "expense")
private val PERSON_WORDS = setOf(
    "payer", "paidby", "payedby", "owner", "assignee", "author", "member", "participant", "user", "creditor", "debtor",
)
private val PEOPLE_WORDS = setOf(
    "payedfor", "paidfor", "owers", "ower", "beneficiaries", "beneficiary", "members", "participants", "users",
    "splitwith", "sharedwith", "assignees",
)
private val PEOPLE_COLLECTIONS = setOf("members", "participants", "users", "people", "persons")
private val OPTION_LABELS = listOf("name", "displayname", "label", "title", "username")
private val OPTION_ACTIVE = listOf("activated", "active", "enabled")
private const val MAX_FIELD_OPTIONS = 500
