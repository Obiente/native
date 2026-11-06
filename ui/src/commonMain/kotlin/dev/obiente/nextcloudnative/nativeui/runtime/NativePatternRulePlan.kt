package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.rules.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull

/** Current load supplied by the runtime owner, independently of the submitted request. */
data class NativeRuleAuthority(
    val state: NativeScreenState.Ready,
    val sourceActionId: String,
    val navigationValues: Map<String, String> = emptyMap(),
)

@Serializable
data class NativeRuleTarget(val family: String, val identities: List<String>)

@Serializable
data class NativeRuleIdentityProof(
    val actionId: String,
    val sourceActionId: String,
    val observed: Boolean,
    val generation: Long,
    val roundTripReadActionId: String?,
)

/** Immutable recovery evidence. No executable callbacks and no caller-selected risk downgrade. */
@Serializable
data class NativePatternRulePlan(
    val ruleId: String,
    val ruleVersion: Int,
    val actionId: String,
    val targetId: String,
    val target: NativeRuleTarget,
    val identity: NativeRuleIdentityProof,
    val verificationReadActionId: String,
    val writeBindings: Map<String, String>,
    val readBindings: Map<String, String>,
    val stateField: String,
    val expected: Boolean,
    val baseline: Boolean,
) {
    override fun toString(): String = "NativePatternRulePlan($ruleId v$ruleVersion, <private evidence>)"
}

/** Reused by the feed planner and by the executor with its own descriptor and load. */
fun planNativePatternRule(
    schema: NativeAppSchema,
    actionId: String,
    targetId: String,
    authority: NativeRuleAuthority,
    inputValues: Map<String, String> = emptyMap(),
): NativePatternRulePlan? {
    val actions = schema.actions + schema.dedicatedActions
    val write = actions.singleOrNull { it.id == actionId } ?: return null
    val source = actions.singleOrNull { it.id == authority.sourceActionId } ?: return null
    if (source.intent != ActionIntent.list || !source.isDeclaredStateRead(write) ||
        dynamicRouteFamilyRelation(write.binding.path, source.binding.path) != DynamicRouteFamilyRelation.collectionItem) return null
    val records = authority.state.records
    val record = records.singleOrNull { it.id == targetId } ?: return null
    if (record.bindingContext.any { (key, value) -> authority.navigationValues[key] != value }) return null
    val reads = actions.filter { it.isDeclaredStateRead(write) }
    val family = PatternRouteFamily(write, reads)
    val rule = PatternRuleRegistry.shipped.ruleFor(schema, family) as? BooleanStatePatternRule ?: return null
    val itemReads = reads.filter { dynamicRouteFamilyRelation(write.binding.path, it.binding.path) in
        setOf(DynamicRouteFamilyRelation.itemCommand, DynamicRouteFamilyRelation.sameRoute) }
    if (itemReads.size > 1) return null
    val read = itemReads.singleOrNull() ?: source
    val proof = bindObservedIdentity(
        NativeIdentityRoute(write.binding.path, write.binding.pathParameterTypes),
        NativeIdentityRoute(source.binding.path, source.binding.pathParameterTypes),
        itemReads.singleOrNull()?.let { NativeIdentityRoute(it.binding.path, it.binding.pathParameterTypes) },
        record, records, authority.state.generation, authority.state.generation, authority.navigationValues,
    ) as? NativeObservedIdentityProof.Bound ?: return null
    // Type checking applies to every placeholder, including parents. Unknown types never prove a write.
    if (write.binding.pathParameterNames.toSet() != proof.values.keys || write.binding.pathParameterNames.any { name ->
            !provenPathType(write.binding.pathParameterTypes[name], proof.values.getValue(name))
        }) return null
    val targetParameter = write.binding.path.split('/').filter { it.startsWith('{') }.last().removeSurrounding("{", "}")
    if (!record.matchesRuleIdentityType(write.binding.pathParameterTypes[targetParameter])) return null
    val state = rule.state(write, record, inputValues) ?: return null
    val writeValues = proof.values.toMutableMap()
    if (write.binding.bodyFieldNames.isNotEmpty()) {
        val field = state.writeField ?: return null
        writeValues[field] = state.writeValue.toString()
        val properties = (write.binding.bodySchema as? JsonObject)?.get("properties") as? JsonObject ?: return null
        for (name in write.binding.bodyFieldNames.filter { it != field }) {
            val value = if (name == "id") targetId else proof.values.entries.singleOrNull {
                it.key.lowercase().filter(Char::isLetterOrDigit) == name.lowercase().filter(Char::isLetterOrDigit)
            }?.value ?: return null
            val type = ((properties[name] as? JsonObject)?.get("type") as? JsonPrimitive)?.content
            if (!provenPathType(type, value)) return null
            writeValues[name] = value
        }
    }
    val writeSegments = write.binding.path.routeSegments()
    val readValues = linkedMapOf<String, String>()
    read.binding.path.routeSegments().forEachIndexed { index, segment ->
        if (segment.isRoutePlaceholder()) {
            val name = segment.removeSurrounding("{", "}")
            val writeName = writeSegments.getOrNull(index)?.removeSurrounding("{", "}") ?: return null
            val value = proof.values[writeName] ?: return null
            if (!provenPathType(read.binding.pathParameterTypes[name], value)) return null
            readValues[name] = value
        }
    }
    read.binding.requiredQueryParameterNames.forEach { name ->
        readValues[name] = authority.navigationValues[name] ?: return null
    }
    val targetPath = if (writeSegments.last().isRoutePlaceholder()) writeSegments else writeSegments.dropLast(1)
    val identities = targetPath.filter(String::isRoutePlaceholder).map { proof.values.getValue(it.removeSurrounding("{", "}")) }
    return NativePatternRulePlan(
        rule.ruleId, rule.ruleVersion, write.id, record.id,
        NativeRuleTarget(targetPath.joinToString("/", prefix = "/") { if (it.isRoutePlaceholder()) "{}" else it }, identities),
        NativeRuleIdentityProof(write.id, source.id, !record.actionSafeIdentity, authority.state.generation, itemReads.singleOrNull()?.id),
        read.id, writeValues.toMap(), readValues.toMap(), state.field, state.expected, state.baseline,
    )
}

private fun provenPathType(type: String?, value: String): Boolean = when (type) {
    "integer" -> value.toLongOrNull() != null && value.toLong().toString() == value
    "string" -> value.isNotEmpty()
    else -> false
}

internal fun NativeRecord.matchesRuleIdentityType(type: String?): Boolean {
    val identity = (observedRuleValues + wireValues).entries.filter { (key, value) ->
        key.lowercase().filter(Char::isLetterOrDigit).let { it == "id" || it.endsWith("id") } &&
            (value as? JsonPrimitive)?.content == id
    }.map { it.value as? JsonPrimitive ?: return false }
    return identity.isNotEmpty() && identity.all { (type == "string") == it.isString && provenPathType(type, it.content) }
}

/** Exact boolean agreement only. A different type is a contradiction, never a scalar adjustment. */
internal fun NativePatternRulePlan.verifyRuleState(records: List<NativeRecord>): NativeWriteVerification {
    val targetRecord = records.singleOrNull { it.id == targetId } ?: return NativeWriteVerification.Unidentified
    if (targetRecord.safeActionBindingValues(allowUnsafeIdentity = true) == null) return NativeWriteVerification.Unidentified
    val state = targetRecord.ruleObservation(stateField) as? JsonPrimitive
    return if (state != null && !state.isString && state.booleanOrNull == expected) NativeWriteVerification.Confirmed
    else NativeWriteVerification.NotApplied("The app did not confirm the requested item state.")
}

internal fun NativeRecord.ruleObservation(field: String) = wireValues[field] ?: observedRuleValues[field]

/** Explicit recovery never automatically replays. Only RepeatSameOperation permits a send. */
enum class NativeRuleRecoveryIntent { CheckStatus, RepeatSameOperation }

interface NativeRuleRecoveryExecutor {
    suspend fun recoverRuleOperations(): NativeActionExecutionResult
    /** Presentation availability only. Execution always reconciles and revalidates independently. */
    suspend fun canRepeatRuleOperation(request: NativeActionRequest.Submit): Boolean
}
