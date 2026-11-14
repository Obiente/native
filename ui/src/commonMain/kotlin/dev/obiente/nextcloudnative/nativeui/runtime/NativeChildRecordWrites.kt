package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    /** The stored values of every body field as read, which a replacement expects to be unchanged. */
    val snapshot: Map<String, String?> = emptyMap(),
    /** The exact stored JSON of the record, which a replacement echoes for every untouched field. */
    val wire: Map<String, JsonElement> = emptyMap(),
)

/** [records] are the loaded children; their stored references are evidence for choice sources. */
internal fun nativeChildRecordWritePlan(
    schema: NativeAppSchema,
    listActionId: String,
    datasetContext: NativeDatasetContext,
    records: List<NativeRecord> = emptyList(),
): NativeChildRecordWritePlan? {
    val parent = datasetContext.parentRecord?.takeIf { record ->
        record.actionSafeIdentity && record.actionBindingProvenanceValid && datasetContext.parentResourceId != null
    } ?: return null
    val route = schema.action(listActionId)?.nativeCollectionRoute() ?: return null
    val listValues = route.listValues(parent, datasetContext) ?: return null
    val permissions = parent.nativeWritePermissions(datasetContext.currentUserId)?.takeIf { it.any } ?: return null
    val sources = NativeFieldChoiceSources(parent, datasetContext.relatedRecords, records)
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
    // A replacement carries the complete representation: every declared body field echoed exactly
    // as stored. A field the read did not return, or cannot return exactly, keeps the record read-only.
    val echo = updateRoute?.takeIf { replace }?.let { item ->
        nativeFullEchoUpdatePlan(item.write.action, item.write.echoSource(record), edited = emptySet(), contextInputs = item.contextInputs)
    }
    return NativeChildRecord(
        id = id,
        draft = draft,
        unreadable = unreadable,
        namesParent = record.valueNamed(route.parentReferenceKey) == parentId,
        editUnavailableReason = operation?.unavailableReason
            ?: (echo as? NativeFullEchoUpdatePlan.ReadOnly)?.message
            // A merge edit is checked by comparing the typed values sent with the record read back.
            ?: "This app takes edits as a form body, whose result cannot be checked, so editing is not offered here."
                .takeIf { operation != null && updateRoute?.write?.action?.takesJsonBody == false }
            ?: "Editing here would send an incomplete copy of this record.".takeIf { replace && unreadable.isNotEmpty() },
        snapshot = operation?.fields.orEmpty().storedSnapshot(record),
        wire = updateRoute?.write?.echoSource(record) ?: record.wireValues,
    )
}

/**
 * The exact stored JSON a replacement may echo: every declared body field the read returned under
 * its own name, plus a field the editor reads under a reference alias (`payer` stored as
 * `payer_id`) when exactly one alias holds a value that fits the field's declared type. The value
 * travels verbatim; nothing is re-encoded.
 */
private fun NativeContractAction.echoSource(record: NativeRecord): Map<String, JsonElement> {
    val properties = (action.binding.bodySchema as? JsonObject)?.get("properties") as? JsonObject ?: return record.wireValues
    val aliases = properties.mapNotNull { (name, schema) ->
        if (record.wireValues.valueNamed(name) != null) return@mapNotNull null
        val aliasKeys = storedFieldNames(name).toSet() - name.contractKey()
        val value = record.wireValues.entries.filter { it.key.contractKey() in aliasKeys }.singleOrNull()?.value
            ?.takeIf { it.fitsDeclaredSchema(schema) } ?: return@mapNotNull null
        name to value
    }
    return record.wireValues + aliases
}

private val dev.obiente.nextcloudnative.nativeui.model.ActionSpec.takesJsonBody: Boolean
    get() = binding.bodyContentType?.let { it.substringBefore(';').trim().lowercase() == "application/json" } != false

/** Every value [record] stores under the names of these fields, keyed by normalized name. */
private fun List<NativeRecordField>.storedSnapshot(record: NativeRecord): Map<String, String?> {
    val names = flatMapTo(hashSetOf()) { storedFieldNames(it.name) }
    return record.values.entries.filter { it.key.contractKey() in names }.associate { (key, value) -> key.contractKey() to value?.trim() }
}

/** How an unconfirmed edit may be repeated: a replacement only after a fresh read. */
internal val NativeChildRecordWritePlan.updateRetry: NativeEditorWriteRetry
    get() = if (updateRoute?.write?.action?.binding?.method == HttpMethod.PUT) NativeEditorWriteRetry.AfterRefresh else NativeEditorWriteRetry.Idempotent

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

/**
 * PATCH sends the changed fields, after the executor has read the record again and found the
 * edited fields unchanged since the editor opened. A verified PUT is a full echo: changed fields are encoded from
 * the draft and every other declared body field travels exactly as stored, never re-encoded (so a
 * stored date-time is not shortened to the date the editor shows). Immediately before the write
 * the record is read again through the contract's same-route read, and nothing is sent when any
 * stored value differs from the copy the editor opened. After the write the record is read back
 * and compared with what was sent.
 */
internal fun NativeChildRecordWritePlan.updateRequest(record: NativeChildRecord, draft: Map<String, String>): NativeChildRecordRequestResult {
    val update = update ?: return NativeChildRecordRequestResult.Unavailable("Editing is not offered here.")
    val route = updateRoute ?: return NativeChildRecordRequestResult.Unavailable("Editing is not offered here.")
    record.editUnavailableReason?.let { return NativeChildRecordRequestResult.Unavailable(it) }
    val identities = route.values(listValues, parentId, record.id, record.namesParent)
        ?: return NativeChildRecordRequestResult.Unavailable("This record cannot be matched to its parent safely.")
    val editable = update.shownFields.filter { it.input != null && it.name !in record.unreadable }
    val changed = editable.filter { field -> draft[field.name]?.let { it != record.draft[field.name].orEmpty() } == true }
    if (changed.isEmpty()) return NativeChildRecordRequestResult.NoChanges
    val replace = route.write.action.binding.method == HttpMethod.PUT
    val sent = changed
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
    val editedNames = values.keys
    val previous = editedNames.mapNotNull { name -> record.wire.valueNamed(name)?.let { name to it } }.toMap()
    if (!replace) {
        val untouched = update.fields.map(NativeRecordField::name).filter { it !in editedNames }
            .mapNotNull { name -> record.wire.valueNamed(name)?.let { name to it } }.toMap()
        return ready(route.write, identities + values, readBack = NativeWriteReadBack(
            record.id, previous, untouched, echoUntouched = false,
            editedFields = editedNames, absentPrevious = editedNames - previous.keys,
            preReadBaseline = true,
            storedValues = route.write::echoSource,
        ))
    }
    val echo = when (val plan = nativeFullEchoUpdatePlan(route.write.action, record.wire, editedNames, route.contextInputs)) {
        is NativeFullEchoUpdatePlan.Echo -> plan.echo
        is NativeFullEchoUpdatePlan.ReadOnly -> return NativeChildRecordRequestResult.Unavailable(plan.message)
        is NativeFullEchoUpdatePlan.Sparse -> emptyMap()
    }
    val expected = echo + previous
    val precondition = NativeWritePrecondition(
        readValues = identities.filterKeys { it in route.write.placeholders },
        targetId = record.id,
    ) { current ->
        val stored = route.write.echoSource(current)
        expected.all { (name, value) -> sameWireValue(stored.valueNamed(name), value) }
    }
    return ready(route.write, identities + values, precondition, NativeWriteReadBack(
        record.id, previous, echo, echoUntouched = true,
        editedFields = editedNames, absentPrevious = editedNames - previous.keys,
        storedValues = route.write::echoSource,
    ))
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
    val raw = record.storedFieldValue(name)
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

private fun ready(
    write: NativeContractAction,
    values: Map<String, String>,
    precondition: NativeWritePrecondition? = null,
    readBack: NativeWriteReadBack? = null,
) = NativeChildRecordRequestResult.Ready(
    NativeActionRequest.Submit(write.action, values, confirmed = true, precondition = precondition, readBack = readBack),
)
