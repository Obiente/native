package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Writes for records that live inside a parent record: an expense in a project, an entry in a
 * ledger, a task in a list. The collection read that opened the screen defines the route
 * (`.../{parent}/{collection}`); the contract's own create, update, and delete actions at the
 * matching positions define the operations; and their request-body schemas define the fields.
 *
 * The expense pattern (money, one paying person, several people it was for) adds presentation
 * only: field order, today's date, and everyone active preselected. When a field of a recognized
 * pattern cannot be encoded from the schema, that write is unavailable with an explanation, so a
 * record is never created or replaced without one of its essential parts.
 */

internal data class NativeChildRecordOperation(
    val write: NativeContractAction,
    val fields: List<NativeRecordField>,
    /** Why this operation cannot be offered, or null when it can. */
    val unavailableReason: String?,
) {
    val shownFields: List<NativeRecordField> get() = fields.filter(NativeRecordField::shown).sortedBy { it.role.order }
}

internal data class NativeChildRecordWritePlan(
    val route: NativeCollectionRoute,
    val parentId: String,
    val listValues: List<String>,
    val create: NativeChildRecordOperation?,
    val update: NativeChildRecordOperation?,
    val updateRoute: NativeItemWrite?,
    val delete: NativeItemWrite?,
) {
    /** Money paid by one person for several people: the shape of a shared expense. */
    val expense: Boolean
        get() = (create ?: update)?.fields.orEmpty().map(NativeRecordField::role).containsAll(
            setOf(NativeRecordFieldRole.Money, NativeRecordFieldRole.Person, NativeRecordFieldRole.People),
        )
}

/** One child record opened for editing: its identity and the draft read back from it. */
internal data class NativeChildRecord(
    val id: String,
    val draft: Map<String, String>,
    /** Shown fields whose current value could not be read; they are kept unchanged. */
    val unreadable: Set<String>,
    val namesParent: Boolean,
    /** Why this record cannot be edited, or null. */
    val editUnavailableReason: String?,
)

internal fun nativeChildRecordWritePlan(
    schema: NativeAppSchema,
    listActionId: String,
    datasetContext: NativeDatasetContext,
): NativeChildRecordWritePlan? {
    val parent = datasetContext.parentRecord?.takeIf { record ->
        record.actionSafeIdentity && record.actionBindingProvenanceValid && datasetContext.parentResourceId != null
    } ?: return null
    val route = schema.action(listActionId)?.nativeCollectionRoute() ?: return null
    val listValues = route.listValues(parent, datasetContext) ?: return null
    val permissions = parent.nativeWritePermissions(datasetContext.currentUserId)?.takeIf { it.any } ?: return null
    val sources = NativeFieldChoiceSources(parent, datasetContext.relatedRecords)
    fun operation(write: NativeContractAction): NativeChildRecordOperation? {
        val fields = write.action.nativeRecordFields(route.listPlaceholders.toSet() + write.placeholders, sources) ?: return null
        val shown = fields.filter(NativeRecordField::shown)
        val reason = when {
            shown.none { it.input != null } -> "This app's contract does not describe fields that can be filled in here."
            else -> shown.firstOrNull { it.input == null }?.let { field ->
                "This app's contract does not describe how to send ${field.label.lowercase()}, so this is not changed here."
            }
        }
        return NativeChildRecordOperation(write, fields, reason)
    }
    val create = schema.contractWrite(NativeContractWriteKind.Create, route.listShape)
        ?.takeIf { permissions.create && it.placeholders.size == listValues.size }?.let(::operation)
    val updateRoute = schema.itemWrite(NativeContractWriteKind.Update, route)?.takeIf { permissions.update }
    val update = updateRoute?.let { item ->
        operation(item.write)?.let { op ->
            if (item.write.action.binding.method == HttpMethod.PUT && !item.write.declaresReplacementRead) {
                op.copy(unavailableReason = "This app's contract replaces the whole record without a way to verify the result, so it is not edited here.")
            } else {
                op
            }
        }
    }
    val delete = schema.itemWrite(NativeContractWriteKind.Delete, route)?.takeIf { permissions.delete }
    if (create == null && update == null && delete == null) return null
    return NativeChildRecordWritePlan(route, parent.id, listValues, create, update, updateRoute.takeIf { update != null }, delete)
}

/** Reads one child record of this plan's parent, or null when it cannot be bound to a write. */
internal fun NativeChildRecordWritePlan.record(record: NativeRecord): NativeChildRecord? {
    if (!record.actionSafeIdentity || !record.actionBindingProvenanceValid) return null
    val id = record.id.takeIf(String::isSafeContractPathValue) ?: return null
    val operation = update
    val draft = linkedMapOf<String, String>()
    val unreadable = linkedSetOf<String>()
    operation?.shownFields?.forEach { field ->
        val current = field.currentValue(record)
        if (current == null) unreadable += field.name else draft[field.name] = current
    }
    val replace = updateRoute?.write?.action?.binding?.method == HttpMethod.PUT
    // A replacement must carry every value the record holds for its body, or it would clear one.
    val incomplete = replace && (unreadable.isNotEmpty() || operation?.fields.orEmpty().any { field ->
        !field.shown && record.valueNamed(field.name) != null
    })
    return NativeChildRecord(
        id = id,
        draft = draft,
        unreadable = unreadable,
        namesParent = record.valueNamed(route.parentReferenceKey) == parentId,
        editUnavailableReason = operation?.unavailableReason
            ?: "Editing here would send an incomplete copy of this record.".takeIf { incomplete },
    )
}

internal fun NativeChildRecordWritePlan.newDraft(today: String): Map<String, String> = buildMap {
    create?.shownFields?.forEach { field ->
        when {
            field.input == NativeRecordFieldInput.Date && field.role == NativeRecordFieldRole.Date && today.isIsoCalendarDate() ->
                put(field.name, today)
            expense && field.role == NativeRecordFieldRole.People -> (field.input as? NativeRecordFieldInput.MultiChoice)
                ?.let { input -> put(field.name, input.options.filter(NativeFieldOption::active).joinToString(",") { it.id }) }
            field.input == NativeRecordFieldInput.Toggle -> put(field.name, "false")
        }
    }
}

internal sealed interface NativeChildRecordRequestResult {
    data class Ready(val request: NativeActionRequest.Submit) : NativeChildRecordRequestResult

    data class Invalid(val errors: Map<String, String>) : NativeChildRecordRequestResult

    data object NoChanges : NativeChildRecordRequestResult

    data class Unavailable(val reason: String) : NativeChildRecordRequestResult
}

internal fun NativeChildRecordWritePlan.createRequest(draft: Map<String, String>): NativeChildRecordRequestResult {
    val create = create ?: return NativeChildRecordRequestResult.Unavailable("Adding is not offered here.")
    create.unavailableReason?.let { return NativeChildRecordRequestResult.Unavailable(it) }
    val fields = create.shownFields
    validate(fields, draft, existing = null).takeIf(Map<*, *>::isNotEmpty)?.let { return NativeChildRecordRequestResult.Invalid(it) }
    val values = fields.mapNotNull { field -> field.encode(draft[field.name].orEmpty())?.let { field.name to it } }.toMap()
    return ready(create.write, create.write.placeholders.zip(listValues).toMap() + values)
}

/** PATCH sends the changed fields; a verified PUT sends every field read back from the record. */
internal fun NativeChildRecordWritePlan.updateRequest(record: NativeChildRecord, draft: Map<String, String>): NativeChildRecordRequestResult {
    val update = update ?: return NativeChildRecordRequestResult.Unavailable("Editing is not offered here.")
    val route = updateRoute ?: return NativeChildRecordRequestResult.Unavailable("Editing is not offered here.")
    record.editUnavailableReason?.let { return NativeChildRecordRequestResult.Unavailable(it) }
    val identities = route.values(listValues, parentId, record.id, record.namesParent)
        ?: return NativeChildRecordRequestResult.Unavailable("This record cannot be matched to its parent safely.")
    val editable = update.shownFields.filter { it.input != null && it.name !in record.unreadable }
    val changed = editable.filter { field -> draft[field.name]?.let { it != record.draft[field.name].orEmpty() } == true }
    if (changed.isEmpty()) return NativeChildRecordRequestResult.NoChanges
    val sent = if (route.write.action.binding.method == HttpMethod.PUT) editable else changed
    val merged = sent.associate { field -> field.name to (draft[field.name] ?: record.draft[field.name].orEmpty()) }
    validate(sent, merged, existing = record).takeIf(Map<*, *>::isNotEmpty)?.let { return NativeChildRecordRequestResult.Invalid(it) }
    val values = sent.mapNotNull { field ->
        val value = merged.getValue(field.name)
        val encoded = if (field.input is NativeRecordFieldInput.Text) value else field.encode(value)
        when {
            encoded != null -> field.name to encoded
            // An empty value the record already had empty is simply not sent.
            record.draft[field.name].isNullOrBlank() -> null
            else -> return NativeChildRecordRequestResult.Invalid(mapOf(field.name to "This value cannot be cleared here."))
        }
    }.toMap()
    return ready(route.write, identities + values)
}

internal fun NativeChildRecordWritePlan.deleteRequest(record: NativeChildRecord): NativeChildRecordRequestResult {
    val delete = delete ?: return NativeChildRecordRequestResult.Unavailable("Deleting is not offered here.")
    val identities = delete.values(listValues, parentId, record.id, record.namesParent)
        ?: return NativeChildRecordRequestResult.Unavailable("This record cannot be matched to its parent safely.")
    return ready(delete.write, identities)
}

private fun validate(fields: List<NativeRecordField>, draft: Map<String, String>, existing: NativeChildRecord?): Map<String, String> =
    fields.mapNotNull { field ->
        val value = draft[field.name].orEmpty().trim()
        val previous = existing?.draft?.get(field.name)
        val input = field.input
        val problem = when {
            input == null -> null
            value.isEmpty() -> "${field.label} is required.".takeIf { field.required }
            else -> field.problem(input, value, previous)
        }
        problem?.let { field.name to it }
    }.toMap()

private fun NativeRecordField.problem(input: NativeRecordFieldInput, value: String, previous: String?): String? =
        when (input) {
            is NativeRecordFieldInput.Text -> input.maxLength?.takeIf { value.length > it }?.let { "Use at most $it characters." }
            is NativeRecordFieldInput.Number -> {
                val number = value.toDoubleOrNull()?.takeIf(Double::isFinite)
                when {
                    number == null || (input.integer && value.toLongOrNull() == null) -> "Enter a number."
                    input.minimum != null && number < input.minimum -> "Enter at least ${formatGridNumber(input.minimum)}."
                    input.maximum != null && number > input.maximum -> "Enter at most ${formatGridNumber(input.maximum)}."
                    else -> null
                }
            }
            NativeRecordFieldInput.Date -> "Use YYYY-MM-DD.".takeUnless { value.isIsoCalendarDate() }
            NativeRecordFieldInput.Toggle -> "Choose yes or no.".takeUnless { value == "true" || value == "false" }
            // An existing record may keep a choice that is no longer active.
            is NativeRecordFieldInput.Choice -> "Choose one of the options.".takeUnless {
                input.options.any { it.id == value && (it.active || value == previous) }
            }
            is NativeRecordFieldInput.MultiChoice -> {
                val ids = value.split(',').map(String::trim).filter(String::isNotEmpty)
                val kept = previous.orEmpty().split(',').toSet()
                "Choose from the listed options.".takeUnless {
                    ids.all { id -> input.options.any { it.id == id && (it.active || id in kept) } }
                }
            }
        }

/** The request value of one field, or null when an empty optional value is simply not sent. */
private fun NativeRecordField.encode(draft: String): String? {
    val value = draft.trim()
    return when (input) {
        null -> null
        is NativeRecordFieldInput.Text -> draft.takeIf { it.isNotEmpty() }
        is NativeRecordFieldInput.MultiChoice -> value.split(',').map(String::trim).filter(String::isNotEmpty)
            .distinct().mapNotNull(String::toLongOrNull).sorted().takeIf { it.isNotEmpty() || required }
            ?.joinToString(",", prefix = "[", postfix = "]")
        else -> value.takeIf(String::isNotEmpty)
    }
}

/** The current value of this field in [record], in draft form, or null when it cannot be read. */
private fun NativeRecordField.currentValue(record: NativeRecord): String? {
    val stem = name.contractKey().let { key ->
        when {
            key.length > 3 && key.endsWith("ids") -> key.dropLast(3)
            key.length > 2 && key.endsWith("id") -> key.dropLast(2)
            else -> key
        }
    }
    val names = listOf(name, stem + "id", stem + "_id", stem + "ids")
    val raw = names.firstNotNullOfOrNull(record::valueNamed)
    return when (val input = input) {
        null -> null
        is NativeRecordFieldInput.MultiChoice -> {
            val ids = (raw.contractJsonOrNull() as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                ?: raw?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
                ?: return null
            ids.takeIf { list -> list.all { id -> input.options.any { it.id == id } } }?.joinToString(",")
        }
        is NativeRecordFieldInput.Choice -> raw?.takeIf { value -> input.options.any { it.id == value } }
            ?: "".takeIf { raw == null }
        is NativeRecordFieldInput.Number -> raw?.toDoubleOrNull()?.takeIf(Double::isFinite)
            ?.let { if (input.integer) it.toLong().toString() else formatGridNumber(it) } ?: "".takeIf { raw == null }
        NativeRecordFieldInput.Date -> raw?.take(10)?.takeIf(String::isIsoCalendarDate) ?: "".takeIf { raw == null }
        NativeRecordFieldInput.Toggle -> raw?.toBooleanStrictOrNull()?.toString() ?: "false".takeIf { raw == null }
        is NativeRecordFieldInput.Text -> raw.orEmpty()
    }
}

private val NativeRecordFieldRole.order: Int
    get() = when (this) {
        NativeRecordFieldRole.Title -> 0
        NativeRecordFieldRole.Money -> 1
        NativeRecordFieldRole.Date -> 2
        NativeRecordFieldRole.Person -> 3
        NativeRecordFieldRole.People -> 4
        NativeRecordFieldRole.Reference, NativeRecordFieldRole.Choice -> 5
        NativeRecordFieldRole.Other -> 6
        NativeRecordFieldRole.Notes -> 7
    }

private fun ready(write: NativeContractAction, values: Map<String, String>) =
    NativeChildRecordRequestResult.Ready(NativeActionRequest.Submit(write.action, values, confirmed = true))
