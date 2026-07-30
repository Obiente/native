package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.sameDynamicResourceAs

internal data class NativeRecordActionCapabilities(
    val create: NativeRecordFormActionPlan?,
    val edit: NativeRecordFormActionPlan?,
    val delete: NativeRecordDeleteActionPlan?,
    val completion: NativeRecordCompletionActionPlan?,
    val commands: List<NativeRecordCommandActionPlan>,
)

internal enum class NativeRecordFormActionKind {
    Create,
    Edit,
}

internal data class NativeRecordFormActionPlan(
    val kind: NativeRecordFormActionKind,
    val action: ActionSpec,
    val fields: List<FieldSpec>,
    val initialValues: Map<String, String>,
    private val bindingValues: Map<String, String>,
) {
    fun request(
        inputValues: Map<String, String>,
        confirmed: Boolean = false,
    ): NativeActionRequest.Submit {
        require(!action.requiresConfirmation || confirmed) {
            "This action requires explicit confirmation."
        }
        val declaredFields = fields.associateBy(FieldSpec::id)
        require(inputValues.keys.all(declaredFields::containsKey)) {
            "The action contains an undeclared input."
        }
        fields.forEach { field ->
            val supplied = inputValues[field.id]
            require(!field.required || supplied?.trim()?.isNotEmpty() == true) {
                "${field.label} is required."
            }
            supplied?.let { value ->
                require(field.acceptsRecordActionValue(value)) {
                    "${field.label} has an invalid value."
                }
            }
        }
        return NativeActionRequest.Submit(
            action = action,
            values = bindingValues + inputValues.mapValues { (_, value) -> value.trim() },
            confirmed = confirmed,
        )
    }
}

internal data class NativeRecordDeleteActionPlan(
    val action: ActionSpec,
    private val bindingValues: Map<String, String>,
) {
    fun request(confirmed: Boolean): NativeActionRequest.Submit {
        require(confirmed) { "Delete requires explicit confirmation." }
        return NativeActionRequest.Submit(
            action = action,
            values = bindingValues,
            confirmed = true,
        )
    }
}

internal data class NativeRecordCommandActionPlan(
    val action: ActionSpec,
    val effect: ActionEffect,
    private val bindingValues: Map<String, String>,
) {
    val requiresConfirmation: Boolean
        get() = action.requiresConfirmation

    fun request(confirmed: Boolean = false): NativeActionRequest.Submit {
        require(!requiresConfirmation || confirmed) {
            "This action requires explicit confirmation."
        }
        return NativeActionRequest.Submit(
            action = action,
            values = bindingValues,
            confirmed = confirmed,
        )
    }
}

internal data class NativeRecordCompletionActionPlan(
    val action: ActionSpec,
    val field: FieldSpec,
    val currentlyCompleted: Boolean,
    val completedWireValue: String,
    val incompleteWireValue: String,
    val kind: NativeRecordCompletionActionKind,
    private val bindingValues: Map<String, String>,
) {
    fun request(
        completed: Boolean,
        confirmed: Boolean = false,
    ): NativeActionRequest.Submit {
        require(!action.requiresConfirmation || confirmed) {
            "This action requires explicit confirmation."
        }
        if (kind == NativeRecordCompletionActionKind.Toggle) {
            require(completed != currentlyCompleted) {
                "The requested completion state is already active."
            }
        }
        return NativeActionRequest.Submit(
            action = action,
            values = if (kind == NativeRecordCompletionActionKind.Toggle) {
                bindingValues
            } else {
                bindingValues + (
                    field.id to if (completed) completedWireValue else incompleteWireValue
                    )
            },
            confirmed = confirmed,
        )
    }
}

internal enum class NativeRecordCompletionActionKind {
    SetValue,
    Toggle,
}

/**
 * Plans ordinary record mutations from the verified schema and the active navigation context.
 *
 * No app identity, route vocabulary, or product-specific resource name participates in selection.
 * A capability is withheld when more than one action is equally valid or any required identity
 * cannot be bound exactly. Completion is deliberately narrower than a named execute action: it is
 * exposed only when one declared update field can represent both completed and incomplete states.
 */
internal fun nativeRecordActions(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    record: NativeRecord? = null,
    navigationContext: Map<String, String> = emptyMap(),
): NativeRecordActionCapabilities {
    if (schema.resources.count { candidate -> candidate.id == resource.id } != 1) {
        return emptyNativeRecordActionCapabilities()
    }
    if (record?.actionBindingProvenanceValid == false) {
        return emptyNativeRecordActionCapabilities()
    }
    val createSources = nativeRecordBindingSources(
        resource = resource,
        record = record,
        navigationContext = navigationContext,
        includeRecordValues = false,
    ) ?: return emptyNativeRecordActionCapabilities()
    val recordSources = nativeRecordBindingSources(
        resource = resource,
        record = record,
        navigationContext = navigationContext,
        includeRecordValues = true,
    ) ?: return emptyNativeRecordActionCapabilities()
    val resourceActions = schema.actions.filter { action ->
        action.resourceId == resource.id && action.confidence.isSafeRecordActionConfidence()
    }
    val permittedRecordActions = if (record == null) {
        resourceActions
    } else {
        resourceActions.filter { action -> record.permits(action, resource) }
    }
    val verifiedRelationshipFieldIds = schema.relationships
        .asSequence()
        .filter { relationship ->
            // High-confidence inferred relationships remain useful to relation pickers, but only
            // verified relationship evidence may hide and silently bind a write field.
            relationship.confidence == Confidence.verified &&
                relationship.childResourceId.sameDynamicResourceAs(resource.id)
        }
        .mapNotNull { relationship -> relationship.childFieldId }
        .toSet()
    val contextualSources = safeActionBindingValues(
        navigationContext,
        record?.bindingContext.orEmpty(),
    ) ?: return emptyNativeRecordActionCapabilities()
    val completionSemantics = resource.uniqueRecordCompletionSemantics(
        allowReadOnly = permittedRecordActions.any { action -> action.effect == ActionEffect.toggle },
    )

    val create = resourceActions.mapNotNull { action ->
        action.recordFormPlan(
            kind = NativeRecordFormActionKind.Create,
            resource = resource,
            record = null,
            sources = createSources,
            contextualSources = contextualSources,
            relationshipFieldIds = verifiedRelationshipFieldIds,
            completionFieldId = completionSemantics?.field?.id,
        )
    }.singleOrNull()

    val edit = if (record?.actionSafeIdentity == true && !record.hasNativeDeletedState()) {
        permittedRecordActions.mapNotNull { action ->
            action.recordFormPlan(
                kind = NativeRecordFormActionKind.Edit,
                resource = resource,
                record = record,
                sources = recordSources,
                contextualSources = contextualSources,
                relationshipFieldIds = verifiedRelationshipFieldIds,
                completionFieldId = completionSemantics?.field?.id,
            )
        }.singleOrNull()
    } else {
        null
    }

    val delete = if (record?.actionSafeIdentity == true && !record.hasNativeDeletedState()) {
        permittedRecordActions.mapNotNull { action ->
            action.recordDeletePlan(resource, record, recordSources)
        }.singleOrNull()
    } else {
        null
    }

    val completion = if (
        record?.actionSafeIdentity == true &&
        !record.hasNativeDeletedState() &&
        completionSemantics != null
    ) {
        permittedRecordActions.mapNotNull { action ->
            action.recordCompletionPlan(
                resource = resource,
                record = record,
                sources = recordSources,
                semantics = completionSemantics,
            )
        }.singleOrNull()
    } else {
        null
    }
    val commands = if (record?.actionSafeIdentity == true) {
        permittedRecordActions
            .mapNotNull { action ->
                action.recordCommandPlan(
                    resource = resource,
                    record = record,
                    sources = recordSources,
                )
            }
            .groupBy(NativeRecordCommandActionPlan::effect)
            .mapNotNull { (_, candidates) -> candidates.singleOrNull() }
            .sortedBy { plan -> RECORD_COMMAND_EFFECT_ORDER.indexOf(plan.effect) }
    } else {
        emptyList()
    }

    return NativeRecordActionCapabilities(
        create = create,
        edit = edit,
        delete = delete,
        completion = completion,
        commands = commands,
    )
}

private fun ActionSpec.recordFormPlan(
    kind: NativeRecordFormActionKind,
    resource: ResourceSpec,
    record: NativeRecord?,
    sources: Map<String, String>,
    contextualSources: Map<String, String>,
    relationshipFieldIds: Set<String>,
    completionFieldId: String?,
): NativeRecordFormActionPlan? {
    val expectedIntent = when (kind) {
        NativeRecordFormActionKind.Create -> ActionIntent.create
        NativeRecordFormActionKind.Edit -> ActionIntent.update
    }
    if (
        intent != expectedIntent ||
        risk != ActionRisk.mutating ||
        binding.method !in kind.allowedMethods() ||
        !binding.hasSafeRecordActionBody() ||
        binding.hasOverlappingRecordActionChannels()
    ) {
        return null
    }

    val declaredFields = binding.bodyFieldNames.mapNotNull { bodyName ->
        resource.fields.singleOrNull { field ->
            field.id == bodyName && !field.readOnly && field.kind in RECORD_ACTION_FIELD_KINDS
        }?.copy(required = bodyName in binding.requiredBodyFieldNames)
    }
    val contextualRelationshipValues = if (kind == NativeRecordFormActionKind.Create) {
        declaredFields.mapNotNull { field ->
            field.takeIf { candidate -> candidate.id in relationshipFieldIds }
                ?.let { candidate ->
                    contextualSources[candidate.id]
                        ?.takeIf(String::isSafeRecordBodyValue)
                        ?.let { value -> candidate.id to value }
                }
        }.toMap()
    } else {
        emptyMap()
    }
    val fields = declaredFields.filterNot { field -> field.id in contextualRelationshipValues }
    if (fields.map(FieldSpec::id).distinct().size != fields.size) return null
    if (kind == NativeRecordFormActionKind.Edit && fields.isEmpty()) return null
    if (
        kind == NativeRecordFormActionKind.Edit &&
        completionFieldId != null &&
        fields.all { field -> field.id == completionFieldId }
    ) {
        return null
    }

    val resolved = binding.resolveRecordActionBindings(
        resource = resource,
        record = record,
        sources = sources,
        userInputNames = fields.mapTo(mutableSetOf(), FieldSpec::id),
    ) ?: return null
    val completeBindings = safeActionBindingValues(resolved, contextualRelationshipValues) ?: return null
    val representedBodyFieldIds = buildSet {
        addAll(fields.map(FieldSpec::id))
        addAll(completeBindings.keys)
    }
    if (binding.bodyFieldNames.any { fieldId -> fieldId !in representedBodyFieldIds }) return null
    return NativeRecordFormActionPlan(
        kind = kind,
        action = this,
        fields = fields,
        initialValues = if (record == null) {
            emptyMap()
        } else {
            fields.mapNotNull { field ->
                record.values[field.id]?.let { value -> field.id to value }
            }.toMap()
        },
        bindingValues = completeBindings,
    )
}

private fun ActionSpec.recordDeletePlan(
    resource: ResourceSpec,
    record: NativeRecord,
    sources: Map<String, String>,
): NativeRecordDeleteActionPlan? {
    if (
        intent != ActionIntent.delete ||
        effect !in setOf(ActionEffect.unspecified, ActionEffect.delete) ||
        risk != ActionRisk.destructive ||
        binding.method != HttpMethod.DELETE ||
        !requiresConfirmation
    ) {
        return null
    }
    val resolved = binding.resolveRecordActionBindings(
        resource = resource,
        record = record,
        sources = sources,
        userInputNames = emptySet(),
    ) ?: return null
    return NativeRecordDeleteActionPlan(this, resolved)
}

private fun ActionSpec.recordCommandPlan(
    resource: ResourceSpec,
    record: NativeRecord,
    sources: Map<String, String>,
): NativeRecordCommandActionPlan? {
    if (!record.canApplyNativeRecordEffect(effect)) return null
    val safeEffect = when (effect) {
        in REVERSIBLE_RECORD_COMMAND_EFFECTS -> {
            intent == ActionIntent.execute &&
                risk == ActionRisk.mutating &&
                !requiresConfirmation
        }
        in DESTRUCTIVE_RECORD_COMMAND_EFFECTS -> {
            intent in setOf(ActionIntent.execute, ActionIntent.delete) &&
                risk == ActionRisk.destructive &&
                requiresConfirmation
        }
        else -> false
    }
    if (
        !safeEffect ||
        binding.method !in RECORD_COMMAND_METHODS ||
        binding.hasOverlappingRecordActionChannels() ||
        !binding.hasSafeRecordActionBody() ||
        binding.allowsObservedBodyFields ||
        (binding.bodyContentType != null && binding.bodyFieldNames.isEmpty())
    ) {
        return null
    }
    val resolved = binding.resolveRecordCommandBindings(
        resource = resource,
        record = record,
        sources = sources,
    ) ?: return null
    return NativeRecordCommandActionPlan(
        action = this,
        effect = effect,
        bindingValues = resolved,
    )
}

private fun NativeRecord.canApplyNativeRecordEffect(effect: ActionEffect): Boolean {
    val deleted = hasNativeDeletedState()
    val archived = hasNativeArchivedState()
    return when (effect) {
        ActionEffect.archive -> !deleted && !archived
        ActionEffect.unarchive -> !deleted && archived
        ActionEffect.restore,
        ActionEffect.permanentDelete,
        -> deleted
        ActionEffect.copy -> !deleted
        else -> true
    }
}

private fun NativeRecord.hasNativeDeletedState(): Boolean = hasNativeRecordState(
    setOf("deleted", "deletedat", "isdeleted", "removedat", "trashed", "trashedat"),
)

private fun NativeRecord.hasNativeArchivedState(): Boolean = hasNativeRecordState(
    setOf("archived", "archivedat", "isarchived"),
)

private fun NativeRecord.hasNativeRecordState(fieldIds: Set<String>): Boolean = values.any { (key, value) ->
    key.lowercase().filter(Char::isLetterOrDigit) in fieldIds &&
        value?.trim()?.let { state ->
            state.isNotEmpty() &&
                !state.equals("false", ignoreCase = true) &&
                state != "0" &&
                !state.equals("null", ignoreCase = true)
        } == true
}

private fun ActionSpec.recordCompletionPlan(
    resource: ResourceSpec,
    record: NativeRecord,
    sources: Map<String, String>,
    semantics: NativeRecordCompletionSemantics,
): NativeRecordCompletionActionPlan? {
    if (effect == ActionEffect.toggle) {
        return recordToggleCompletionPlan(
            resource = resource,
            record = record,
            sources = sources,
            semantics = semantics,
        )
    }
    if (
        intent != ActionIntent.update ||
        risk != ActionRisk.mutating ||
        requiresConfirmation ||
        binding.method !in RECORD_UPDATE_METHODS ||
        !binding.hasSafeRecordActionBody() ||
        binding.hasOverlappingRecordActionChannels() ||
        binding.bodyFieldNames.count { it == semantics.field.id } != 1
    ) {
        return null
    }
    val currentValue = record.values[semantics.field.id] ?: return null
    val currentlyCompleted = semantics.read(currentValue) ?: return null
    val resolved = binding.resolveRecordActionBindings(
        resource = resource,
        record = record,
        sources = sources,
        userInputNames = setOf(semantics.field.id),
    ) ?: return null
    return NativeRecordCompletionActionPlan(
        action = this,
        field = semantics.field,
        currentlyCompleted = currentlyCompleted,
        completedWireValue = semantics.completedWireValue,
        incompleteWireValue = semantics.incompleteWireValue,
        kind = NativeRecordCompletionActionKind.SetValue,
        bindingValues = resolved,
    )
}

private fun ActionSpec.recordToggleCompletionPlan(
    resource: ResourceSpec,
    record: NativeRecord,
    sources: Map<String, String>,
    semantics: NativeRecordCompletionSemantics,
): NativeRecordCompletionActionPlan? {
    val identityParameterNames = (
        binding.pathParameterNames +
            binding.requiredPathParameterNames
        ).distinct().filter { name -> name.isRecordIdentityNameFor(resource) }
    if (
        intent != ActionIntent.execute ||
        risk != ActionRisk.mutating ||
        requiresConfirmation ||
        binding.method !in RECORD_TOGGLE_METHODS ||
        identityParameterNames.size != 1 ||
        identityParameterNames.none { name ->
            "{$name}" in binding.path.substringBefore('?').split('/')
        } ||
        binding.bodyFieldNames.isNotEmpty() ||
        binding.requiredBodyFieldNames.isNotEmpty() ||
        binding.queryParameterNames.isNotEmpty() ||
        binding.requiredQueryParameterNames.isNotEmpty()
    ) {
        return null
    }
    val currentValue = record.values[semantics.field.id] ?: return null
    val currentlyCompleted = semantics.read(currentValue) ?: return null
    val resolved = binding.resolveRecordActionBindings(
        resource = resource,
        record = record,
        sources = sources,
        userInputNames = emptySet(),
    ) ?: return null
    return NativeRecordCompletionActionPlan(
        action = this,
        field = semantics.field,
        currentlyCompleted = currentlyCompleted,
        completedWireValue = semantics.completedWireValue,
        incompleteWireValue = semantics.incompleteWireValue,
        kind = NativeRecordCompletionActionKind.Toggle,
        bindingValues = resolved,
    )
}

private fun ApiBinding.resolveRecordActionBindings(
    resource: ResourceSpec,
    record: NativeRecord?,
    sources: Map<String, String>,
    userInputNames: Set<String>,
): Map<String, String>? {
    val resolved = linkedMapOf<String, String>()
    val requiredPathNames = (pathParameterNames + requiredPathParameterNames).distinct()
    requiredPathNames.forEach { name ->
        val value = resolveRecordActionValue(name, resource, record, sources)
            ?: sources["id"].takeIf {
                record == null &&
                    requiredPathNames.size == 1 &&
                    isProvenSingleParentIdentityAlias(name)
            }
            ?.takeIf(String::isSafeRecordPathValue)
            ?: return null
        resolved[name] = value
    }
    requiredQueryParameterNames.distinct().forEach { name ->
        val value = resolveRecordActionValue(name, resource, record, sources)
            ?.takeIf(String::isSafeRecordQueryValue)
            ?: return null
        resolved[name] = value
    }
    requiredBodyFieldNames.distinct().forEach { name ->
        if (name in userInputNames) return@forEach
        val value = resolveRecordActionValue(name, resource, record, sources)
            ?.takeIf(String::isSafeRecordBodyValue)
            ?: return null
        resolved[name] = value
    }
    return resolved
}

private fun ApiBinding.resolveRecordCommandBindings(
    resource: ResourceSpec,
    record: NativeRecord,
    sources: Map<String, String>,
): Map<String, String>? {
    val pathNames = (pathParameterNames + requiredPathParameterNames).distinct()
    val queryNames = (queryParameterNames + requiredQueryParameterNames).distinct()
    val bodyNames = (bodyFieldNames + requiredBodyFieldNames).distinct()
    val channels = listOf(pathNames, queryNames, bodyNames)
        .flatMap { names -> names.map(String::recordSemanticId) }
    if (channels.size != channels.distinct().size) return null

    val recordIdentityNames = pathNames.filter { name -> name.isRecordIdentityNameFor(resource) }
    if (
        recordIdentityNames.size != 1 ||
        recordIdentityNames.none { name ->
            "{$name}" in path.substringBefore('?').split('/')
        }
    ) {
        return null
    }

    val resolved = linkedMapOf<String, String>()
    pathNames.forEach { name ->
        val value = resolveRecordActionValue(name, resource, record, sources)
            ?.takeIf(String::isSafeRecordPathValue)
            ?: return null
        resolved[name] = value
    }
    queryNames.forEach { name ->
        val value = resolveRecordActionValue(name, resource, record, sources)
            ?.takeIf(String::isSafeRecordQueryValue)
            ?: return null
        resolved[name] = value
    }
    bodyNames.forEach { name ->
        val value = resolveRecordActionValue(name, resource, record, sources)
            ?.takeIf(String::isSafeRecordBodyValue)
            ?: return null
        resolved[name] = value
    }
    return resolved.takeIf { values ->
        values.size == pathNames.size + queryNames.size + bodyNames.size
    }
}

private fun ApiBinding.isProvenSingleParentIdentityAlias(parameterName: String): Boolean {
    if (!parameterName.endsWith("Id", ignoreCase = true) || parameterName.length <= 2) return false
    val parentResourceId = parameterName.dropLast(2)
    val segments = path.substringBefore('?').split('/').filter(String::isNotBlank)
    val placeholder = "{$parameterName}"
    return segments.indices.any { index ->
        segments[index] == placeholder &&
            index > 0 &&
            segments[index - 1].sameDynamicResourceAs(parentResourceId)
    }
}

private fun resolveRecordActionValue(
    name: String,
    resource: ResourceSpec,
    record: NativeRecord?,
    sources: Map<String, String>,
): String? {
    if (
        record?.actionSafeIdentity == true &&
        name.isRecordIdentityNameFor(resource)
    ) {
        return record.id
    }
    return sources[name]
}

private fun nativeRecordBindingSources(
    resource: ResourceSpec,
    record: NativeRecord?,
    navigationContext: Map<String, String>,
    includeRecordValues: Boolean,
): Map<String, String>? {
    if (record?.actionBindingProvenanceValid == false) return null
    fun safeSource(source: Map<String, String>): Map<String, String>? =
        source.takeIf { values ->
            values.all { (name, value) ->
                name.isSafeRecordBindingName() && value.isSafeRecordBodyValue()
            }
        }

    val sources = mutableListOf<Map<String, String>>()
    sources += safeSource(navigationContext) ?: return null
    sources += safeSource(record?.bindingContext.orEmpty()) ?: return null
    if (includeRecordValues && record != null) {
        val observed = linkedMapOf<String, String>()
        resource.fields.forEach { field ->
            record.values[field.id]?.let { value ->
                val canonicalIdentityValue =
                    record.actionSafeIdentity &&
                        (
                            field.id.isRecordIdentityNameFor(resource) ||
                                value == record.id
                            )
                if (!canonicalIdentityValue) {
                    if (!field.id.isSafeRecordBindingName() || !value.isSafeRecordBodyValue()) return null
                    observed[field.id] = value
                }
            }
        }
        sources += observed
    }
    return safeActionBindingValues(*sources.toTypedArray())
}

private data class NativeRecordCompletionSemantics(
    val field: FieldSpec,
    val completedWireValue: String,
    val incompleteWireValue: String,
) {
    fun read(value: String): Boolean? = when (field.kind) {
        FieldKind.boolean -> when (value.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        FieldKind.enumeration -> when (value) {
            completedWireValue -> true
            incompleteWireValue -> false
            else -> null
        }
        else -> null
    }
}

private fun ResourceSpec.uniqueRecordCompletionSemantics(
    allowReadOnly: Boolean = false,
): NativeRecordCompletionSemantics? {
    val candidates = fields.mapNotNull { field ->
        if (field.readOnly && !allowReadOnly) return@mapNotNull null
        val score = field.recordCompletionScore()
        if (score == 0) return@mapNotNull null
        val semantics = when (field.kind) {
            FieldKind.boolean -> NativeRecordCompletionSemantics(field, "true", "false")
            FieldKind.enumeration -> {
                val values = field.enumValues.orEmpty()
                val completed = values.singleOrNull {
                    it.recordSemanticId() in RECORD_COMPLETED_VALUES
                }
                val incomplete = values.singleOrNull {
                    it.recordSemanticId() in RECORD_INCOMPLETE_VALUES
                }
                if (completed == null || incomplete == null) null else {
                    NativeRecordCompletionSemantics(field, completed, incomplete)
                }
            }
            else -> null
        } ?: return@mapNotNull null
        semantics to score
    }.sortedByDescending { (_, score) -> score }
    val best = candidates.firstOrNull() ?: return null
    return best.first.takeIf { candidates.drop(1).none { (_, score) -> score == best.second } }
}

private fun FieldSpec.recordCompletionScore(): Int {
    if (kind !in setOf(FieldKind.boolean, FieldKind.enumeration)) return 0
    val id = id.recordSemanticId()
    val label = label.recordSemanticId()
    return when {
        id in RECORD_COMPLETION_FIELD_NAMES -> 400
        label in RECORD_COMPLETION_FIELD_NAMES -> 300
        else -> 0
    }
}

private fun NativeRecordFormActionKind.allowedMethods(): Set<HttpMethod> = when (this) {
    NativeRecordFormActionKind.Create -> setOf(HttpMethod.POST)
    NativeRecordFormActionKind.Edit -> RECORD_UPDATE_METHODS
}

private fun ApiBinding.hasSafeRecordActionBody(): Boolean =
    bodyFieldNames.isEmpty() || bodyContentType?.substringBefore(';')?.trim()?.lowercase() in
        RECORD_ACTION_BODY_CONTENT_TYPES

private fun ApiBinding.hasOverlappingRecordActionChannels(): Boolean {
    val routeParameters = (
        pathParameterNames +
            requiredPathParameterNames +
            queryParameterNames +
            requiredQueryParameterNames
        ).mapTo(hashSetOf(), String::recordSemanticId)
    return bodyFieldNames.any { bodyName -> bodyName.recordSemanticId() in routeParameters }
}

private fun String.isRecordIdentityNameFor(resource: ResourceSpec): Boolean {
    val name = recordSemanticId()
    if (name == "id") return true
    if (!name.endsWith("id") || name.length <= 2) return false
    val stem = name.dropLast(2)
    return listOf(resource.id, resource.name).any { alias ->
        stem.sameDynamicResourceAs(alias)
    }
}

private fun FieldSpec.acceptsRecordActionValue(value: String): Boolean {
    val normalized = value.trim()
    if (
        normalized.length > MAX_RECORD_BODY_VALUE_LENGTH ||
        normalized.any(Char::isUnsafeRecordTextControl)
    ) {
        return false
    }
    if (normalized.isEmpty()) return !required
    return when (kind) {
        FieldKind.integer -> normalized.toLongOrNull() != null
        FieldKind.decimal,
        FieldKind.currency,
        -> normalized.toDoubleOrNull() != null
        FieldKind.boolean -> normalized in setOf("true", "false")
        FieldKind.enumeration -> enumValues?.contains(normalized) == true
        else -> true
    }
}

private fun String.recordSemanticId(): String = lowercase().filter(Char::isLetterOrDigit)

private fun String.isSafeRecordBindingName(): Boolean =
    length in 1..128 && all { character ->
        character.isLetterOrDigit() || character == '_' || character == '-' || character == '.'
    }

private fun String.isSafeRecordPathValue(): Boolean =
    isNotBlank() &&
        length <= MAX_RECORD_IDENTITY_VALUE_LENGTH &&
        none { character ->
            character == '/' || character == '\\' || character.isISOControl()
        }

private fun String.isSafeRecordQueryValue(): Boolean =
    isNotBlank() &&
        length <= MAX_RECORD_QUERY_VALUE_LENGTH &&
        none(Char::isISOControl)

private fun String.isSafeRecordBodyValue(): Boolean =
    length <= MAX_RECORD_BODY_VALUE_LENGTH && none(Char::isUnsafeRecordTextControl)

private fun Char.isUnsafeRecordTextControl(): Boolean =
    isISOControl() && this !in setOf('\n', '\r', '\t')

private fun Confidence.isSafeRecordActionConfidence(): Boolean =
    this == Confidence.high || this == Confidence.verified

/**
 * Honors exact record-level capability fields when a contract exposes them. A declared capability
 * whose current-record value is absent or unparseable is unknown and therefore cannot authorize a
 * write. A trusted endpoint with no applicable per-record capability field remains unconditional.
 */
private fun NativeRecord.permits(action: ActionSpec, resource: ResourceSpec): Boolean {
    val capabilityFields = resource.fields.mapNotNull { field ->
        val semanticId = field.id.lowercase().filter(Char::isLetterOrDigit)
        if (
            semanticId !in setOf(
                "readonly",
                "writable",
                "canwrite",
                "canedit",
                "canupdate",
                "candelete",
            )
        ) {
            return@mapNotNull null
        }
        semanticId to field.id
    }.toMap()
    if (capabilityFields.isEmpty()) return true

    fun declaredCapability(id: String): Boolean? {
        val fieldId = capabilityFields[id] ?: return null
        return values[fieldId]?.nativeCapabilityBooleanOrNull()
    }
    if ("readonly" in capabilityFields && declaredCapability("readonly") != false) return false
    if ("writable" in capabilityFields && declaredCapability("writable") != true) return false
    if ("canwrite" in capabilityFields && declaredCapability("canwrite") != true) return false

    val deletion = when (action.effect) {
        ActionEffect.delete,
        ActionEffect.permanentDelete,
        -> true
        ActionEffect.clear,
        ActionEffect.leave,
        -> false
        else -> action.intent == ActionIntent.delete
    }
    return if (deletion) {
        "candelete" !in capabilityFields || declaredCapability("candelete") == true
    } else {
        val applicable = setOf("canedit", "canupdate").filter(capabilityFields::containsKey)
        action.intent !in setOf(ActionIntent.update, ActionIntent.execute) ||
            applicable.isEmpty() ||
            applicable.all { id -> declaredCapability(id) == true }
    }
}

private fun String.nativeCapabilityBooleanOrNull(): Boolean? = when (trim().lowercase()) {
    "true", "1", "yes" -> true
    "false", "0", "no" -> false
    else -> null
}

private fun emptyNativeRecordActionCapabilities() = NativeRecordActionCapabilities(
    create = null,
    edit = null,
    delete = null,
    completion = null,
    commands = emptyList(),
)

private val RECORD_UPDATE_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
private val RECORD_TOGGLE_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
private val RECORD_COMMAND_METHODS = setOf(
    HttpMethod.POST,
    HttpMethod.PUT,
    HttpMethod.PATCH,
    HttpMethod.DELETE,
)
private val REVERSIBLE_RECORD_COMMAND_EFFECTS = setOf(
    ActionEffect.archive,
    ActionEffect.unarchive,
    ActionEffect.restore,
    ActionEffect.copy,
)
private val DESTRUCTIVE_RECORD_COMMAND_EFFECTS = setOf(
    ActionEffect.permanentDelete,
    ActionEffect.clear,
    ActionEffect.leave,
)
private val RECORD_COMMAND_EFFECT_ORDER = listOf(
    ActionEffect.archive,
    ActionEffect.unarchive,
    ActionEffect.restore,
    ActionEffect.copy,
    ActionEffect.clear,
    ActionEffect.leave,
    ActionEffect.permanentDelete,
)
private val RECORD_ACTION_BODY_CONTENT_TYPES = setOf(
    "application/json",
    "application/x-www-form-urlencoded",
)
private val RECORD_ACTION_FIELD_KINDS = setOf(
    FieldKind.string,
    FieldKind.longText,
    FieldKind.integer,
    FieldKind.decimal,
    FieldKind.boolean,
    FieldKind.date,
    FieldKind.dateTime,
    FieldKind.currency,
    FieldKind.userReference,
    FieldKind.enumeration,
)
private val RECORD_COMPLETION_FIELD_NAMES = setOf(
    "complete",
    "completed",
    "done",
    "finished",
    "iscomplete",
    "iscompleted",
    "isdone",
    "status",
    "state",
)
private val RECORD_COMPLETED_VALUES = setOf(
    "closed",
    "complete",
    "completed",
    "done",
    "finished",
)
private val RECORD_INCOMPLETE_VALUES = setOf(
    "active",
    "incomplete",
    "new",
    "open",
    "pending",
    "todo",
)
private const val MAX_RECORD_IDENTITY_VALUE_LENGTH = 256
private const val MAX_RECORD_QUERY_VALUE_LENGTH = 2_048
private const val MAX_RECORD_BODY_VALUE_LENGTH = 65_536
