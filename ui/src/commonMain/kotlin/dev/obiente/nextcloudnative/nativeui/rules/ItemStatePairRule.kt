package dev.obiente.nextcloudnative.nativeui.rules

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import kotlinx.serialization.json.*

/** A boolean postcondition selected by a registered contract rule, never by a caller's risk hint. */
data class PatternBooleanState(val field: String, val expected: Boolean, val baseline: Boolean,
    val writeField: String? = null, val writeValue: Boolean = expected)

interface BooleanStatePatternRule : PatternRule {
    fun state(action: ActionSpec, record: NativeRecord, values: Map<String, String>): PatternBooleanState?

    override val riskClass: DynamicOperationRiskClass get() = DynamicOperationRiskClass.idempotentStateSet
    override fun encode(family: PatternRouteFamily, value: JsonElement): JsonElement? =
        (value as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull != null }
    override fun decode(family: PatternRouteFamily, wire: JsonElement): JsonElement? = encode(family, wire)
    override fun postconditionHolds(family: PatternRouteFamily, submitted: JsonElement, observed: JsonElement?): Boolean =
        observed != null && decode(family, observed) != null && submitted == observed
}

/** Reviewed inverse pairs, each scoped to exactly one item. Neither bulk commands nor bodies match. */
class ItemStatePairRule : BooleanStatePatternRule {
    override val ruleId = "item-state-pair"
    override val ruleVersion = 1

    override fun matches(schema: NativeAppSchema, family: PatternRouteFamily): Boolean {
        val write = family.write
        if (!write.isDeclaredStateCandidate() || !write.isBodyFreeCommand()) return false
        val pair = statePairs.singleOrNull { write.command() in setOf(it.on, it.off) } ?: return false
        val inverse = if (write.command() == pair.on) pair.off else pair.on
        return (schema.actions + schema.dedicatedActions).count { other ->
            other.id != write.id && other.isDeclaredStateCandidate() && other.isBodyFreeCommand() &&
                other.binding.method == write.binding.method && other.binding.path.substringBeforeLast('/') == write.binding.path.substringBeforeLast('/') &&
                other.command() == inverse && other.binding.pathParameterTypes == write.binding.pathParameterTypes
        } == 1 && family.reads.any { it.isDeclaredStateRead(write) }
    }

    override fun state(action: ActionSpec, record: NativeRecord, values: Map<String, String>): PatternBooleanState? {
        val pair = statePairs.singleOrNull { action.command() in setOf(it.on, it.off) } ?: return null
        val field = record.booleanStateFields().filter { it.key.stateKey() in pair.fields.keys }.singleOrNull() ?: return null
        val expected = (action.command() == pair.on) == pair.fields.getValue(field.key.stateKey())
        return PatternBooleanState(field.key, expected, field.value)
    }
}

/** Keeps declared one-boolean item updates, with the same verified execution and recovery path. */
class DeclaredBooleanStateRule : BooleanStatePatternRule {
    override val ruleId = "declared-boolean-state"
    override val ruleVersion = 1

    override fun matches(schema: NativeAppSchema, family: PatternRouteFamily): Boolean {
        val write = family.write
        val properties = (write.binding.bodySchema as? JsonObject)?.get("properties") as? JsonObject ?: return false
        val field = write.booleanStateBodyField() ?: return false
        return write.isDeclaredStateCandidate() && write.binding.method in setOf(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.POST) &&
            write.binding.path.substringAfterLast('/').isPlaceholder() &&
            properties.all { (name, property) ->
                val declared = property as? JsonObject ?: return@all false
                (declared["readOnly"] as? JsonPrimitive)?.booleanOrNull != true &&
                    (name == field || name.stateKey().endsWith("id") &&
                        (declared["type"] as? JsonPrimitive)?.content in setOf("integer", "string"))
            } &&
            family.reads.any { it.isDeclaredStateRead(write) }
    }

    override fun state(action: ActionSpec, record: NativeRecord, values: Map<String, String>): PatternBooleanState? {
        val field = action.booleanStateBodyField() ?: return null
        val expected = values[field]?.toBooleanStrictOrNull() ?: return null
        val aliases = if (field.stateKey() in readFields) readFields else starFields
        val fields = record.booleanStateFields().filter { it.key.stateKey() in aliases }
        val read = fields.singleOrNull { it.key == field } ?: fields.singleOrNull() ?: return null
        val readExpected = if (aliases.getValue(field.stateKey()) == aliases.getValue(read.key.stateKey())) expected else !expected
        return PatternBooleanState(read.key, readExpected, read.value, field, expected)
    }
}

internal fun ActionSpec.isDeclaredStateRead(write: ActionSpec): Boolean =
    binding.method == HttpMethod.GET && risk == ActionRisk.readOnly && binding.bodyContentType == null &&
        evidence.any { it.source in STATE_CONTRACT_EVIDENCE } &&
        dynamicRouteFamilyRelation(write.binding.path, binding.path) != null

private fun ActionSpec.isDeclaredStateCandidate(): Boolean =
    risk == ActionRisk.mutating && binding.method in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH) &&
        confidence in setOf(Confidence.high, Confidence.verified) && evidence.any { it.source in STATE_CONTRACT_EVIDENCE } &&
        operationRiskClass !in setOf(DynamicOperationRiskClass.externalEffect, DynamicOperationRiskClass.destructive,
            DynamicOperationRiskClass.sharing, DynamicOperationRiskClass.administration, DynamicOperationRiskClass.credential) &&
        binding.queryParameterNames.isEmpty() && binding.requiredPathParameterNames.isNotEmpty()

private fun ActionSpec.isBodyFreeCommand(): Boolean =
    binding.bodyContentType == null && binding.bodySchema == null && binding.bodyFieldNames.isEmpty() &&
        binding.path.substringBeforeLast('/').substringAfterLast('/').isPlaceholder()

/** A known item-state command still requires a plan when its inverse is missing. */
internal fun ActionSpec.needsItemStateProof(): Boolean =
    isBodyFreeCommand() && statePairs.any { command() == it.on || command() == it.off }

internal fun ActionSpec.booleanStateBodyField(): String? =
    ((binding.bodySchema as? JsonObject)?.get("properties") as? JsonObject)?.entries?.filter { (name, property) ->
        name.stateKey() in stateFieldNames && ((property as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "boolean"
    }?.singleOrNull()?.key

private fun ActionSpec.command() = binding.path.substringAfterLast('/').stateKey()
private fun String.isPlaceholder() = startsWith('{') && endsWith('}')
private fun String.stateKey() = lowercase().filter(Char::isLetterOrDigit)
private fun NativeRecord.booleanStateFields(): List<Map.Entry<String, Boolean>> =
    (observedRuleValues + wireValues).mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { key to it }
    }.toMap().entries.toList()

private data class StatePair(val on: String, val off: String, val fields: Map<String, Boolean>)
private val readFields = mapOf("read" to true, "isread" to true, "seen" to true, "unread" to false, "isunread" to false, "unseen" to false)
private val starFields = mapOf("starred" to true, "isstarred" to true, "favorite" to true, "favourite" to true)
private val statePairs = listOf(
    StatePair("read", "unread", readFields), StatePair("markread", "markunread", readFields),
    StatePair("seen", "unseen", readFields), StatePair("markseen", "markunseen", readFields),
    StatePair("star", "unstar", starFields), StatePair("favorite", "unfavorite", starFields),
    StatePair("favourite", "unfavourite", starFields),
)
private val stateFieldNames = readFields.keys + starFields.keys
private val STATE_CONTRACT_EVIDENCE = setOf(EvidenceSource.openApi, EvidenceSource.verifiedAppPackage, EvidenceSource.appStoreLinkedSourceTag)
