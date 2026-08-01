package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_LIST_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DynamicIntegerArrayParseResult
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.isExactDynamicIntegerArraySchema
import dev.obiente.nextcloudnative.nativeui.model.parseDynamicIntegerArrayInput
import dev.obiente.nextcloudnative.nativeui.model.repeatableObjectInputSpec
import dev.obiente.nextcloudnative.nativeui.model.sameDynamicResourceAs
import dev.obiente.nextcloudnative.template.scanBracedTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal data class NativeRecordActionCapabilities(
    val create: NativeRecordFormActionPlan?,
    val edit: NativeRecordFormActionPlan?,
    val delete: NativeRecordDeleteActionPlan?,
    val completion: NativeRecordCompletionActionPlan?,
    val commands: List<NativeRecordCommandActionPlan>,
    val commandForms: List<NativeRecordCommandFormActionPlan>,
)

internal data class NativeRecordAuthorityContext(
    val parentResource: ResourceSpec,
    val parentRecord: NativeRecord,
)

internal fun NativeDatasetContext.nativeRecordAuthorityContext(
    schema: NativeAppSchema,
): NativeRecordAuthorityContext? {
    val resourceId = parentResourceId ?: return null
    val record = parentRecord
        ?.takeIf { it.actionBindingProvenanceValid }
        ?: return null
    val resource = schema.resources.singleOrNull { candidate -> candidate.id == resourceId }
        ?: return null
    return NativeRecordAuthorityContext(resource, record)
}

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
        require(kind != NativeRecordFormActionKind.Edit || action.binding.method != HttpMethod.PUT) {
            "Replacement PUT record edits require authoritative completeness and precondition evidence."
        }
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

    fun requestWithStructuredInput(
        scalarInputValues: Map<String, String>,
        repeatableObjectValues: Map<String, List<RepeatableObjectInputRow>>,
        confirmed: Boolean = false,
    ): NativeActionRequest.Submit = request(
        inputValues = fields.encodeRepeatableObjectInput(
            scalarInputValues = scalarInputValues,
            repeatableObjectValues = repeatableObjectValues,
        ),
        confirmed = confirmed,
    )
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

internal data class NativeRecordCommandFormActionPlan(
    val action: ActionSpec,
    val effect: ActionEffect,
    val fields: List<FieldSpec>,
    val initialValues: Map<String, String>,
    private val bindingValues: Map<String, String>,
    private val fieldSchemas: Map<String, JsonElement?>,
) {
    val requiresConfirmation: Boolean
        get() = action.requiresConfirmation

    fun request(
        inputValues: Map<String, String>,
        confirmed: Boolean = false,
    ): NativeActionRequest.Submit {
        require(!requiresConfirmation || confirmed) {
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
                require(field.acceptsRecordCommandFormValue(value, fieldSchemas[field.id])) {
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

    fun requestWithStructuredInput(
        scalarInputValues: Map<String, String>,
        repeatableObjectValues: Map<String, List<RepeatableObjectInputRow>>,
        confirmed: Boolean = false,
    ): NativeActionRequest.Submit = request(
        inputValues = fields.encodeRepeatableObjectInput(
            scalarInputValues = scalarInputValues,
            repeatableObjectValues = repeatableObjectValues,
        ),
        confirmed = confirmed,
    )
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
    authorityContext: NativeRecordAuthorityContext? = null,
): NativeRecordActionCapabilities {
    val authoritativeResource = schema.resources
        .filter { candidate -> candidate.id == resource.id }
        .singleOrNull()
        ?: return emptyNativeRecordActionCapabilities()
    if (record?.actionBindingProvenanceValid == false) {
        return emptyNativeRecordActionCapabilities()
    }
    val createSources = nativeRecordBindingSources(
        resource = authoritativeResource,
        record = record,
        navigationContext = navigationContext,
        includeRecordValues = false,
    ) ?: return emptyNativeRecordActionCapabilities()
    val recordSources = nativeRecordBindingSources(
        resource = authoritativeResource,
        record = record,
        navigationContext = navigationContext,
        includeRecordValues = true,
    ) ?: return emptyNativeRecordActionCapabilities()
    val resourceActions = schema.actions.filter { action ->
        action.resourceId == authoritativeResource.id &&
            action.confidence.isSafeRecordActionConfidence()
    }
    val permittedRecordActions = if (record == null) {
        resourceActions
    } else {
        resourceActions.filter { action ->
            record.permits(action, authoritativeResource, authorityContext)
        }
    }
    val permittedRecordCommandFormActions = if (record == null) {
        emptyList()
    } else {
        schema.actions.filter { action ->
            action.confidence.isSafeRecordActionConfidence() &&
                action.binding.singleRecordPathIdentityName(authoritativeResource) != null &&
                record.permits(action, authoritativeResource, authorityContext)
        }
    }
    val verifiedRelationshipFieldIds = schema.relationships
        .asSequence()
        .filter { relationship ->
            // High-confidence inferred relationships remain useful to relation pickers, but only
            // verified relationship evidence may hide and silently bind a write field.
            relationship.confidence == Confidence.verified &&
                relationship.childResourceId.sameDynamicResourceAs(authoritativeResource.id)
        }
        .mapNotNull { relationship -> relationship.childFieldId }
        .toSet()
    val contextualSources = safeActionBindingValues(
        navigationContext,
        record?.bindingContext.orEmpty(),
    ) ?: return emptyNativeRecordActionCapabilities()
    val completionSemantics = authoritativeResource.uniqueRecordCompletionSemantics(
        allowReadOnly = permittedRecordActions.any { action -> action.effect == ActionEffect.toggle },
    )

    val create = resourceActions.mapNotNull { action ->
        action.recordFormPlan(
            kind = NativeRecordFormActionKind.Create,
            resource = authoritativeResource,
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
                resource = authoritativeResource,
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
            action.recordDeletePlan(authoritativeResource, record, recordSources)
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
                resource = authoritativeResource,
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
                    resource = authoritativeResource,
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
    val commandForms = if (record?.actionSafeIdentity == true) {
        permittedRecordCommandFormActions
            .mapNotNull { action ->
                val fieldResource = schema.resources.singleOrNull { candidate ->
                    candidate.id == action.resourceId
                } ?: return@mapNotNull null
                action.recordCommandFormPlan(
                    identityResource = authoritativeResource,
                    fieldResource = fieldResource,
                    record = record,
                    sources = recordSources,
                )
            }
            .groupBy(NativeRecordCommandFormActionPlan::effect)
            .mapNotNull { (_, candidates) -> candidates.singleOrNull() }
            .sortedBy { plan -> RECORD_COMMAND_FORM_EFFECT_ORDER.indexOf(plan.effect) }
    } else {
        emptyList()
    }

    return NativeRecordActionCapabilities(
        create = create,
        edit = edit,
        delete = delete,
        completion = completion,
        commands = commands,
        commandForms = commandForms,
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
        !kind.acceptsEffect(effect) ||
        risk != ActionRisk.mutating ||
        binding.method !in kind.allowedMethods() ||
        !binding.hasSafeRecordActionBody() ||
        binding.hasOverlappingRecordActionChannels()
    ) {
        return null
    }

    val declaredFields = binding.bodyFieldNames.mapNotNull { bodyName ->
        resource.fields.singleOrNull { field ->
            field.id == bodyName &&
                !field.readOnly &&
                field.isSupportedRecordFormField(binding.bodyFieldSchema(bodyName))
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
    if (kind == NativeRecordFormActionKind.Edit && binding.method == HttpMethod.PUT) {
        // NativeRecord currently proves selected-record identity and binding provenance, but it
        // cannot prove that a collection row is a complete replacement representation or carry an
        // applicable version/ETag precondition. Even a present nullable field is dropped from the
        // string-valued initial draft, so allowing a generic PUT edit could still clear omitted
        // server state. Semantic whole-resource commands use their separately gated command plan.
        return null
    }
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

private fun ActionSpec.recordCommandFormPlan(
    identityResource: ResourceSpec,
    fieldResource: ResourceSpec,
    record: NativeRecord,
    sources: Map<String, String>,
): NativeRecordCommandFormActionPlan? {
    if (
        !record.canApplyNativeRecordEffect(effect) ||
        !hasRecordCommandFormSemantics() ||
        binding.method !in RECORD_COMMAND_METHODS ||
        binding.bodyFieldNames.isEmpty() ||
        binding.allowsObservedBodyFields ||
        !binding.hasSafeRecordActionBody() ||
        binding.hasOverlappingRecordActionChannels() ||
        binding.singleRecordPathIdentityName(identityResource) == null ||
        risk == ActionRisk.readOnly ||
        (risk == ActionRisk.destructive && !requiresConfirmation)
    ) {
        return null
    }
    val bodyFieldNames = binding.bodyFieldNames
    if (bodyFieldNames.distinct().size != bodyFieldNames.size) return null
    val bodySchemas = bodyFieldNames.associateWith(binding::bodyFieldSchema)
    val fields = bodyFieldNames.mapNotNull { bodyName ->
        fieldResource.fields.singleOrNull { field ->
            field.id == bodyName &&
                !field.readOnly &&
                field.isSupportedRecordCommandFormField(bodySchemas[bodyName])
        }?.copy(required = bodyName in binding.requiredBodyFieldNames)
    }
    if (fields.size != bodyFieldNames.size || fields.isEmpty()) return null
    val resolved = binding.resolveRecordActionBindings(
        resource = identityResource,
        record = record,
        sources = sources,
        userInputNames = fields.mapTo(mutableSetOf(), FieldSpec::id),
    ) ?: return null
    val representedBodyFieldIds = fields.mapTo(mutableSetOf(), FieldSpec::id)
    if (binding.bodyFieldNames.any { fieldId -> fieldId !in representedBodyFieldIds }) return null
    return NativeRecordCommandFormActionPlan(
        action = this,
        effect = effect,
        fields = fields,
        initialValues = fields.mapNotNull { field ->
            record.values[field.id]?.let { value -> field.id to value }
        }.toMap(),
        bindingValues = resolved,
        fieldSchemas = bodySchemas,
    )
}

private fun ActionSpec.hasRecordCommandFormSemantics(): Boolean = when (effect) {
    ActionEffect.assign -> intent in setOf(ActionIntent.update, ActionIntent.execute)
    ActionEffect.update ->
        intent == ActionIntent.update &&
            binding.method == HttpMethod.PUT &&
            resultRecoveryActionId != null
    ActionEffect.copy,
    ActionEffect.move,
    ActionEffect.execute,
    ActionEffect.unspecified,
    -> intent == ActionIntent.execute
    else -> false
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
    if (binding.singleRecordPathIdentityName(resource) == null) return null
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
        binding.bodyFieldNames.isNotEmpty() ||
        binding.bodyContentType != null
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
        // Set-value completion intentionally emits a partial body. ApiBinding cannot currently
        // prove both a complete authoritative replacement and a version/ETag precondition, so an
        // ordinary PUT must not be treated as an inline completion capability.
        binding.method != HttpMethod.PATCH ||
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
    if (!hasExactRecordBindingDeclaration()) return null
    val resolved = linkedMapOf<String, String>()
    val requiredPathNames = (pathParameterNames + requiredPathParameterNames).distinct()
    requiredPathNames.forEach { name ->
        val value = resolveRecordActionValue(name, resource, record, sources)
            ?.takeIf(String::isSafeRecordPathValue)
            ?: sources["id"].takeIf {
                isProvenSingleParentIdentityAlias(name) &&
                    !name.isRecordIdentityNameFor(resource)
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
    if (!hasExactRecordBindingDeclaration()) return null
    val pathNames = (pathParameterNames + requiredPathParameterNames).distinct()
    val queryNames = (queryParameterNames + requiredQueryParameterNames).distinct()
    val bodyNames = (bodyFieldNames + requiredBodyFieldNames).distinct()
    val channels = listOf(pathNames, queryNames, bodyNames)
        .flatMap { names -> names.map(String::recordSemanticId) }
    if (channels.size != channels.distinct().size) return null

    if (singleRecordPathIdentityName(resource) == null) return null

    val resolved = linkedMapOf<String, String>()
    pathNames.forEach { name ->
        val value = resolveRecordActionValue(name, resource, record, sources)
            ?.takeIf(String::isSafeRecordPathValue)
            ?: sources["id"].takeIf {
                isProvenSingleParentIdentityAlias(name) &&
                    !name.isRecordIdentityNameFor(resource)
            }
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

private fun ApiBinding.hasExactRecordBindingDeclaration(): Boolean {
    val channels = listOf(
        pathParameterNames to requiredPathParameterNames,
        queryParameterNames to requiredQueryParameterNames,
        bodyFieldNames to requiredBodyFieldNames,
    )
    if (channels.any { (declared, required) ->
            declared.distinct().size != declared.size ||
                required.distinct().size != required.size ||
                required.any { name -> name !in declared } ||
                (declared + required).any { name -> !name.isSafeRecordBindingName() }
        }
    ) {
        return false
    }
    val scan = path.substringBefore('?').scanBracedTemplate()
    if (scan.malformed) return false
    val placeholders = scan.tokens.map { token -> token.name }
    return placeholders.distinct().size == placeholders.size &&
        placeholders.toSet() == pathParameterNames.toSet()
}

private fun ApiBinding.singleRecordPathIdentityName(resource: ResourceSpec): String? {
    val pathNames = (pathParameterNames + requiredPathParameterNames).distinct()
    val identityName = pathNames.singleOrNull { name ->
        name.isRecordIdentityNameFor(resource)
    } ?: return null
    return identityName.takeIf { name ->
        "{$name}" in path.substringBefore('?').split('/')
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
            FieldKind.boolean -> if (
                !field.requiresIndependentNativeTaskEvidence() ||
                hasIndependentNativeTaskEvidence(field)
            ) {
                NativeRecordCompletionSemantics(field, "true", "false")
            } else {
                null
            }
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

private fun NativeRecordFormActionKind.acceptsEffect(effect: ActionEffect): Boolean = when (this) {
    NativeRecordFormActionKind.Create -> effect in setOf(ActionEffect.unspecified, ActionEffect.create)
    NativeRecordFormActionKind.Edit -> effect in setOf(ActionEffect.unspecified, ActionEffect.update)
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

private fun ApiBinding.bodyFieldSchema(fieldId: String): JsonElement? =
    ((bodySchema as? JsonObject)?.get("properties") as? JsonObject)?.get(fieldId)

private fun FieldSpec.isSupportedRecordFormField(schema: JsonElement?): Boolean =
    if (repeatableObjectInput != null) {
        kind == FieldKind.objectValue && repeatableObjectInput == schema.repeatableObjectInputSpec()
    } else {
        kind in RECORD_ACTION_FIELD_KINDS
    }

private fun FieldSpec.isSupportedRecordCommandFormField(schema: JsonElement?): Boolean {
    repeatableObjectInput?.let { structured ->
        return kind == FieldKind.objectValue && structured == schema.repeatableObjectInputSpec()
    }
    if (kind !in RECORD_ACTION_FIELD_KINDS) return false
    return when (format) {
        DYNAMIC_INTEGER_ARRAY_FORMAT ->
            kind == FieldKind.integer && schema.isExactDynamicIntegerArraySchema()
        DYNAMIC_STRING_ARRAY_FORMAT,
        DYNAMIC_STRING_LIST_FORMAT,
        -> kind in setOf(FieldKind.string, FieldKind.longText) &&
            schema.isExactStringArraySchema(format)
        else -> schema.isCompatibleRecordCommandScalarSchema(this)
    }
}

private fun List<FieldSpec>.encodeRepeatableObjectInput(
    scalarInputValues: Map<String, String>,
    repeatableObjectValues: Map<String, List<RepeatableObjectInputRow>>,
): Map<String, String> {
    require(scalarInputValues.keys.intersect(repeatableObjectValues.keys).isEmpty()) {
        "An input cannot be supplied as both scalar and structured."
    }
    val fieldsById = associateBy(FieldSpec::id)
    require(scalarInputValues.keys.all(fieldsById::containsKey)) {
        "The action contains an undeclared scalar input."
    }
    require(repeatableObjectValues.keys.all(fieldsById::containsKey)) {
        "The action contains an undeclared structured input."
    }
    require(scalarInputValues.keys.all { fieldId ->
        fieldsById.getValue(fieldId).repeatableObjectInput == null
    }) {
        "A structured input cannot be supplied as opaque text."
    }
    return scalarInputValues + repeatableObjectValues.mapValues { (fieldId, rows) ->
        val spec = fieldsById.getValue(fieldId).repeatableObjectInput
            ?: error("A scalar input cannot be supplied as structured rows.")
        spec.encode(rows)
    }
}

private data class RecordCommandScalarSchema(
    val type: String,
    val format: String?,
    val enumValues: List<String>?,
    val minimum: String?,
    val maximum: String?,
    val minimumLength: Int?,
    val maximumLength: Int?,
) {
    fun accepts(value: String): Boolean {
        return when (type) {
            "string" -> {
                if (minimumLength?.let { value.length < it } == true) return false
                if (maximumLength?.let { value.length > it } == true) return false
                enumValues?.contains(value) != false
            }
            "integer" -> {
                val parsed = value.toLongOrNull() ?: return false
                if (minimum?.toLongOrNull()?.let { parsed < it } == true) return false
                if (maximum?.toLongOrNull()?.let { parsed > it } == true) return false
                true
            }
            "number" -> {
                val parsed = value.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return false
                if (minimum?.toDoubleOrNull()?.let { parsed < it } == true) return false
                if (maximum?.toDoubleOrNull()?.let { parsed > it } == true) return false
                true
            }
            "boolean" -> value.toBooleanStrictOrNull() != null
            else -> false
        }
    }

    companion object {
        fun create(element: JsonElement?): RecordCommandScalarSchema? {
            val schema = element as? JsonObject ?: return null
            if (!schema.keys.all(RECORD_COMMAND_SCALAR_SCHEMA_KEYS::contains)) return null
            val type = schema.schemaString("type") ?: return null
            if (type !in RECORD_COMMAND_SCALAR_TYPES) return null
            if (!schema.hasOptionalSchemaString("format")) return null
            if (!schema.hasOptionalSchemaBoolean("nullable")) return null
            val enumValues = schema.schemaStringEnum()
            if ("enum" in schema && (type != "string" || enumValues == null)) return null

            val minimum = schema.schemaNumber("minimum")
            val maximum = schema.schemaNumber("maximum")
            if (
                (type in setOf("integer", "number") &&
                    (!schema.hasOptionalSchemaNumber("minimum") ||
                        !schema.hasOptionalSchemaNumber("maximum"))) ||
                (type !in setOf("integer", "number") &&
                    ("minimum" in schema || "maximum" in schema))
            ) {
                return null
            }
            if (
                type == "integer" &&
                (minimum?.toLongOrNull() == null && minimum != null ||
                    maximum?.toLongOrNull() == null && maximum != null)
            ) {
                return null
            }
            if (
                minimum != null &&
                maximum != null &&
                when (type) {
                    "integer" -> minimum.toLong() > maximum.toLong()
                    "number" -> minimum.toDouble() > maximum.toDouble()
                    else -> false
                }
            ) {
                return null
            }

            val minimumLength = schema.schemaNonNegativeInt("minLength")
            val maximumLength = schema.schemaNonNegativeInt("maxLength")
            if (
                (type == "string" &&
                    (!schema.hasOptionalSchemaNonNegativeInt("minLength") ||
                        !schema.hasOptionalSchemaNonNegativeInt("maxLength"))) ||
                (type != "string" && ("minLength" in schema || "maxLength" in schema)) ||
                (minimumLength != null && maximumLength != null && minimumLength > maximumLength)
            ) {
                return null
            }
            return RecordCommandScalarSchema(
                type = type,
                format = schema.schemaString("format"),
                enumValues = enumValues,
                minimum = minimum,
                maximum = maximum,
                minimumLength = minimumLength,
                maximumLength = maximumLength,
            )
        }
    }
}

private fun JsonElement?.isCompatibleRecordCommandScalarSchema(field: FieldSpec): Boolean {
    val schema = RecordCommandScalarSchema.create(this) ?: return false
    if (field.format != schema.format) return false
    return when (schema.type) {
        "string" -> when {
            schema.enumValues != null ->
                field.kind == FieldKind.enumeration &&
                    field.enumValues == schema.enumValues
            schema.format == "date" -> field.kind == FieldKind.date
            schema.format == "date-time" -> field.kind == FieldKind.dateTime
            else -> field.kind in RECORD_COMMAND_STRING_FIELD_KINDS
        }
        "integer" -> field.kind == FieldKind.integer
        "number" -> field.kind in setOf(FieldKind.decimal, FieldKind.currency)
        "boolean" -> field.kind == FieldKind.boolean
        else -> false
    }
}

private fun JsonElement?.acceptsExactRecordCommandScalar(
    value: String,
    field: FieldSpec,
): Boolean {
    val schema = RecordCommandScalarSchema.create(this) ?: return false
    return isCompatibleRecordCommandScalarSchema(field) && schema.accepts(value)
}

private fun JsonElement?.isExactStringArraySchema(expectedFormat: String): Boolean {
    val objectSchema = this as? JsonObject ?: return false
    if ((objectSchema["type"] as? JsonPrimitive)?.contentOrNull != "array") return false
    if ((objectSchema["format"] as? JsonPrimitive)?.contentOrNull != expectedFormat) return false
    if (objectSchema.keys.any { key -> key !in RECORD_COMMAND_STRING_ARRAY_SCHEMA_KEYS }) return false
    val items = objectSchema["items"] as? JsonObject ?: return false
    return (items["type"] as? JsonPrimitive)?.contentOrNull == "string" &&
        items.keys.all { key -> key in RECORD_COMMAND_STRING_ARRAY_ITEM_SCHEMA_KEYS }
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
    repeatableObjectInput?.let { structured ->
        return runCatching { structured.canonicalJson(normalized) }.isSuccess
    }
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

private fun FieldSpec.acceptsRecordCommandFormValue(
    value: String,
    schema: JsonElement?,
): Boolean {
    val normalized = value.trim()
    if (
        normalized.length > MAX_RECORD_BODY_VALUE_LENGTH ||
        normalized.any(Char::isUnsafeRecordTextControl)
    ) {
        return false
    }
    if (normalized.isEmpty()) return !required
    return when (format) {
        DYNAMIC_INTEGER_ARRAY_FORMAT ->
            parseDynamicIntegerArrayInput(normalized, schema) is DynamicIntegerArrayParseResult.Valid
        DYNAMIC_STRING_ARRAY_FORMAT,
        DYNAMIC_STRING_LIST_FORMAT,
        -> normalized.isSafeRecordStringArray()
        else -> {
            repeatableObjectInput?.let { structured ->
                return runCatching { structured.canonicalJson(normalized) }.isSuccess
            }
            schema.acceptsExactRecordCommandScalar(normalized, this)
        }
    }
}

private fun String.isSafeRecordStringArray(): Boolean {
    val array = runCatching { Json.parseToJsonElement(this) }.getOrNull() as? JsonArray ?: return false
    if (array.size > MAX_RECORD_COMMAND_ARRAY_ITEMS) return false
    return array.all { element ->
        val value = (element as? JsonPrimitive)
            ?.takeIf { primitive -> primitive.isString }
            ?.contentOrNull
            ?: return@all false
        value.length <= MAX_RECORD_BODY_VALUE_LENGTH && value.none(Char::isUnsafeRecordTextControl)
    }
}

private fun JsonObject.schemaString(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonObject.schemaBoolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.schemaNumber(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.contentOrNull
        ?.takeIf { value -> value.toDoubleOrNull()?.isFinite() == true }

private fun JsonObject.schemaNonNegativeInt(name: String): Int? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.longOrNull
        ?.takeIf { value -> value in 0..Int.MAX_VALUE }
        ?.toInt()

private fun JsonObject.hasOptionalSchemaString(name: String): Boolean =
    name !in this || schemaString(name) != null

private fun JsonObject.hasOptionalSchemaBoolean(name: String): Boolean =
    name !in this || schemaBoolean(name) != null

private fun JsonObject.hasOptionalSchemaNumber(name: String): Boolean =
    name !in this || schemaNumber(name) != null

private fun JsonObject.hasOptionalSchemaNonNegativeInt(name: String): Boolean =
    name !in this || schemaNonNegativeInt(name) != null

private fun JsonObject.schemaStringEnum(): List<String>? {
    val array = this["enum"] as? JsonArray ?: return null
    val values = array.mapNotNull { element ->
        (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
    }
    return values.takeIf {
        values.isNotEmpty() &&
            values.size == array.size &&
            values.distinct().size == values.size
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
 * Honors exact record-level capability fields and affirmative parent authority. Endpoint existence
 * is never permission evidence: selected-record mutations fail closed when both sources are absent
 * or unscoped. A declared capability whose current-record value is absent or unparseable is unknown
 * and cannot authorize a write. Explicit denials also cannot be overridden by parent authority.
 */
internal fun NativeRecord.permits(
    action: ActionSpec,
    resource: ResourceSpec,
    authorityContext: NativeRecordAuthorityContext?,
): Boolean {
    val capabilityEntries = resource.fields.mapNotNull { field ->
        val semanticId = field.id.lowercase().filter(Char::isLetterOrDigit)
        if (
            semanticId !in setOf(
                "isadmin",
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
    }
    if (capabilityEntries.groupingBy { entry -> entry.first }.eachCount().any { (_, count) -> count != 1 }) {
        return false
    }
    val capabilityFields = capabilityEntries.toMap()

    fun declaredCapability(id: String): Boolean? {
        val fieldId = capabilityFields[id] ?: return null
        return values[fieldId]?.nativeCapabilityBooleanOrNull()
    }

    val selectedAdminEvidence = if ("isadmin" in capabilityFields) {
        when (declaredCapability("isadmin")) {
            true -> NativeAuthorityEvidence.Allowed
            false -> NativeAuthorityEvidence.Absent
            null -> NativeAuthorityEvidence.Denied
        }
    } else {
        NativeAuthorityEvidence.Absent
    }
    val generalWriteEvidence = buildList {
        if ("readonly" in capabilityFields) {
            add(
                if (declaredCapability("readonly") == false) {
                    NativeAuthorityEvidence.Allowed
                } else {
                    NativeAuthorityEvidence.Denied
                },
            )
        }
        listOf("writable", "canwrite").forEach { id ->
            if (id in capabilityFields) {
                add(
                    if (declaredCapability(id) == true) {
                        NativeAuthorityEvidence.Allowed
                    } else {
                        NativeAuthorityEvidence.Denied
                    },
                )
            }
        }
    }
    if (
        selectedAdminEvidence == NativeAuthorityEvidence.Denied ||
        NativeAuthorityEvidence.Denied in generalWriteEvidence
    ) {
        return false
    }

    val deletion = action.isRecordDeletion()
    val scopedCapabilities = setOf("canedit", "canupdate", "candelete")
        .filter(capabilityFields::containsKey)
    val currentEvidence = if (deletion) {
        if ("candelete" !in scopedCapabilities) {
            NativeAuthorityEvidence.Absent
        } else if (declaredCapability("candelete") == true) {
            NativeAuthorityEvidence.Allowed
        } else {
            NativeAuthorityEvidence.Denied
        }
    } else {
        val applicable = setOf("canedit", "canupdate").filter(scopedCapabilities::contains)
        when {
            action.intent !in setOf(ActionIntent.update, ActionIntent.execute) ->
                NativeAuthorityEvidence.Allowed
            applicable.isEmpty() -> NativeAuthorityEvidence.Absent
            applicable.all { id -> declaredCapability(id) == true } ->
                NativeAuthorityEvidence.Allowed
            else -> NativeAuthorityEvidence.Denied
        }
    }
    return when (currentEvidence) {
        NativeAuthorityEvidence.Allowed -> true
        NativeAuthorityEvidence.Denied,
        NativeAuthorityEvidence.Ambiguous,
        -> false
        NativeAuthorityEvidence.Absent,
        NativeAuthorityEvidence.Unscoped,
        -> when {
            selectedAdminEvidence == NativeAuthorityEvidence.Allowed -> true
            authorityContext?.authorityEvidence(action, resource) == NativeAuthorityEvidence.Allowed -> true
            scopedCapabilities.isNotEmpty() -> false
            deletion -> false
            action.intent in setOf(ActionIntent.update, ActionIntent.execute) ->
                NativeAuthorityEvidence.Allowed in generalWriteEvidence
            else -> false
        }
    }
}

private enum class NativeAuthorityEvidence {
    Allowed,
    Denied,
    Absent,
    Ambiguous,
    Unscoped,
}

private fun ActionSpec.isRecordDeletion(): Boolean = when (effect) {
    ActionEffect.delete,
    ActionEffect.permanentDelete,
    -> true
    ActionEffect.clear,
    ActionEffect.leave,
    -> false
    else -> intent == ActionIntent.delete
}

private fun NativeRecordAuthorityContext.authorityEvidence(
    action: ActionSpec,
    resource: ResourceSpec,
): NativeAuthorityEvidence {
    if (!parentRecord.actionBindingProvenanceValid) return NativeAuthorityEvidence.Denied
    val adminFields = parentResource.fields.filter { field ->
        field.id.recordSemanticId() == "isadmin" && field.kind == FieldKind.boolean
    }
    if (adminFields.size > 1) return NativeAuthorityEvidence.Ambiguous
    adminFields.singleOrNull()?.let { field ->
        when (parentRecord.values[field.id]?.nativeCapabilityBooleanOrNull()) {
            true -> return NativeAuthorityEvidence.Allowed
            false -> Unit
            null -> return NativeAuthorityEvidence.Denied
        }
    }

    val permissionFields = parentResource.fields.filter { field ->
        field.id.recordSemanticId() == "permissions" && field.kind == FieldKind.objectValue
    }
    if (permissionFields.isEmpty()) {
        return if (adminFields.isEmpty()) {
            NativeAuthorityEvidence.Unscoped
        } else {
            NativeAuthorityEvidence.Absent
        }
    }
    if (permissionFields.size != 1) return NativeAuthorityEvidence.Ambiguous
    val permissionField = permissionFields.single()
    val permissions = parentRecord.structuredValues[permissionField.id] as? NativeStructuredValue.ObjectValue
        ?: return NativeAuthorityEvidence.Denied
    if (permissions.omittedEntries > 0) return NativeAuthorityEvidence.Ambiguous
    val capabilityIds = action.authorityCapabilityIds(resource)
    if (capabilityIds.isEmpty()) return NativeAuthorityEvidence.Denied
    val matches = permissions.entries.filter { entry ->
        entry.key.recordSemanticId() in capabilityIds
    }
    if (matches.isEmpty()) return NativeAuthorityEvidence.Absent
    if (matches.size != 1) return NativeAuthorityEvidence.Ambiguous
    val scalar = matches.single().value as? NativeStructuredValue.Scalar
        ?: return NativeAuthorityEvidence.Denied
    if (scalar.kind != NativeStructuredScalarKind.boolean) return NativeAuthorityEvidence.Denied
    return when (scalar.value?.nativeCapabilityBooleanOrNull()) {
        true -> NativeAuthorityEvidence.Allowed
        false -> NativeAuthorityEvidence.Denied
        null -> NativeAuthorityEvidence.Denied
    }
}

private fun ActionSpec.authorityCapabilityIds(resource: ResourceSpec): Set<String> {
    val verbs = when {
        isRecordDeletion() -> setOf("delete", "remove")
        effect == ActionEffect.create || intent == ActionIntent.create -> setOf("create", "add")
        effect == ActionEffect.assign -> setOf("assign", "update")
        effect == ActionEffect.move -> setOf("move", "update")
        effect == ActionEffect.copy -> setOf("copy", "create")
        intent == ActionIntent.update -> setOf("edit", "update", "write")
        intent == ActionIntent.execute -> setOf(effect.name.recordSemanticId(), "execute")
        else -> emptySet()
    }
    if (verbs.isEmpty()) return emptySet()
    val resourceNames = listOf(resource.id, resource.name)
    val routeNouns = binding.path
        .substringBefore('?')
        .split('/')
        .filter { segment ->
            segment.isNotBlank() && !(segment.startsWith('{') && segment.endsWith('}'))
        }
        .filter { segment ->
            resourceNames.any { name -> segment.sameDynamicResourceAs(name) }
        }
        .flatMapTo(linkedSetOf()) { segment -> segment.recordAuthorityNounVariants() }
    if (routeNouns.isEmpty()) return emptySet()
    val resourceNouns = resourceNames.flatMapTo(linkedSetOf(), String::recordAuthorityNounVariants)
    val nouns = routeNouns.intersect(resourceNouns)
    return verbs.flatMapTo(linkedSetOf()) { verb ->
        nouns.map { noun -> "can$verb$noun" }
    }
}

private fun String.recordAuthorityNounVariants(): Set<String> {
    val words = lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotBlank)
    val bases = buildSet {
        words.joinToString("").takeIf(String::isNotBlank)?.let(::add)
        words.lastOrNull()?.let(::add)
    }
    return buildSet {
        bases.forEach { normalized ->
            add(normalized)
            when {
                normalized.endsWith("ies") && normalized.length > 3 ->
                    add(normalized.dropLast(3) + "y")
                normalized.endsWith("ses") ||
                    normalized.endsWith("xes") ||
                    normalized.endsWith("zes") ||
                    normalized.endsWith("ches") ||
                    normalized.endsWith("shes") -> {
                    add(normalized.dropLast(2))
                    add(normalized.dropLast(1))
                }
                normalized.endsWith('s') && !normalized.endsWith("ss") ->
                    add(normalized.dropLast(1))
                else -> add("${normalized}s")
            }
        }
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
    commandForms = emptyList(),
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
private val RECORD_COMMAND_FORM_EFFECT_ORDER = listOf(
    ActionEffect.copy,
    ActionEffect.move,
    ActionEffect.assign,
    ActionEffect.update,
    ActionEffect.execute,
    ActionEffect.unspecified,
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
private val RECORD_COMMAND_STRING_FIELD_KINDS = setOf(
    FieldKind.string,
    FieldKind.longText,
    FieldKind.currency,
    FieldKind.userReference,
)
private val RECORD_COMMAND_SCALAR_TYPES = setOf(
    "string",
    "integer",
    "number",
    "boolean",
)
private val RECORD_COMMAND_SCALAR_SCHEMA_KEYS = setOf(
    "\$comment",
    "default",
    "deprecated",
    "description",
    "enum",
    "example",
    "examples",
    "format",
    "maxLength",
    "maximum",
    "minLength",
    "minimum",
    "nullable",
    "readOnly",
    "title",
    "type",
    "writeOnly",
    "x-nextcloud-native-wire-name",
)
private val RECORD_COMMAND_STRING_ARRAY_SCHEMA_KEYS = setOf(
    "\$comment",
    "default",
    "deprecated",
    "description",
    "example",
    "examples",
    "format",
    "items",
    "nullable",
    "readOnly",
    "title",
    "type",
    "writeOnly",
    "x-nextcloud-native-wire-name",
)
private val RECORD_COMMAND_STRING_ARRAY_ITEM_SCHEMA_KEYS = setOf(
    "\$comment",
    "default",
    "deprecated",
    "description",
    "example",
    "examples",
    "nullable",
    "readOnly",
    "title",
    "type",
    "writeOnly",
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
private const val MAX_RECORD_COMMAND_ARRAY_ITEMS = 256
