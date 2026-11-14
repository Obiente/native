package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.rules.NativeMemberScalarType
import dev.obiente.nextcloudnative.nativeui.rules.memberScalar
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** The shared executor deals in typed evidence; each rule owns its postcondition grammar. */
@Serializable
sealed interface NativeRuleExecutionPlan {
    val ruleId: String
    val ruleVersion: Int
    val actionId: String
    val targetId: String
    val target: NativeRuleTarget
    val identity: NativeRuleIdentityProof
    val verificationReadActionId: String
    val writeBindings: Map<String, String>
    val readBindings: Map<String, String>
    val postcondition: NativeRulePostcondition
}

@Serializable
sealed interface NativeRulePostcondition {
    @Serializable
    data class BooleanState(val field: String, val expected: Boolean, val baseline: Boolean) : NativeRulePostcondition

    @Serializable
    data class Membership(
        val field: String,
        val memberType: NativeMemberScalarType,
        val projection: NativeMembershipProjection,
        val member: JsonPrimitive,
        val assign: Boolean,
        val baseline: JsonArray,
    ) : NativeRulePostcondition
}

@Serializable
enum class NativeMembershipProjection { Scalar, ObjectId, Empty }

/** Missing, null, invalid and bounded-away data are never equivalent to an empty membership. */
@Serializable
sealed interface NativeMembershipObservation {
    @Serializable data object Absent : NativeMembershipObservation
    @Serializable data object Null : NativeMembershipObservation
    @Serializable data object Malformed : NativeMembershipObservation
    @Serializable data object Truncated : NativeMembershipObservation
    @Serializable data class Complete(val raw: JsonArray) : NativeMembershipObservation
}

internal fun membershipObservation(value: JsonElement?): NativeMembershipObservation = when {
    value == null -> NativeMembershipObservation.Absent
    value == JsonNull -> NativeMembershipObservation.Null
    value !is JsonArray -> NativeMembershipObservation.Malformed
    value.size > MAX_MEMBERS || value.toString().length > MAX_MEMBERSHIP_CHARACTERS -> NativeMembershipObservation.Truncated
    else -> NativeMembershipObservation.Complete(value)
}

internal sealed interface NativeMembershipProjectionResult {
    data class Valid(val entries: Map<JsonPrimitive, JsonElement>) : NativeMembershipProjectionResult
    data object Duplicate : NativeMembershipProjectionResult
    data object Malformed : NativeMembershipProjectionResult
}

internal fun JsonArray.projectMembership(type: NativeMemberScalarType, projection: NativeMembershipProjection): NativeMembershipProjectionResult {
    if (membershipObservation(this) !is NativeMembershipObservation.Complete) return NativeMembershipProjectionResult.Malformed
    val selectedProjection = if (projection == NativeMembershipProjection.Empty) {
        if (firstOrNull() is JsonPrimitive) NativeMembershipProjection.Scalar else NativeMembershipProjection.ObjectId
    } else projection
    val entries = linkedMapOf<JsonPrimitive, JsonElement>()
    for (entry in this) {
        val rawId = when (selectedProjection) {
            NativeMembershipProjection.Scalar -> entry
            NativeMembershipProjection.ObjectId -> (entry as? JsonObject)?.get("id")
            NativeMembershipProjection.Empty -> return NativeMembershipProjectionResult.Malformed
        }
        val id = memberScalar(rawId, type) ?: return NativeMembershipProjectionResult.Malformed
        if (entries.put(id, entry) != null) return NativeMembershipProjectionResult.Duplicate
    }
    return NativeMembershipProjectionResult.Valid(entries)
}

internal fun NativeRecord.membership(field: String): NativeMembershipObservation =
    membershipObservations[field] ?: membershipObservation(ruleObservation(field))

internal fun NativeRulePostcondition.baselineMatches(record: NativeRecord): Boolean = when (this) {
    is NativeRulePostcondition.BooleanState -> record.ruleObservation(field) == JsonPrimitive(baseline)
    is NativeRulePostcondition.Membership -> {
        val actual = (record.membership(field) as? NativeMembershipObservation.Complete)?.raw
        val original = baseline.projectMembership(memberType, projection) as? NativeMembershipProjectionResult.Valid
        original != null && (actual?.projectMembership(memberType, projection) as? NativeMembershipProjectionResult.Valid)?.entries == original.entries
    }
}

internal fun NativeRulePostcondition.observationAvailable(record: NativeRecord): Boolean = when (this) {
    is NativeRulePostcondition.BooleanState -> true
    is NativeRulePostcondition.Membership -> (record.membership(field) as? NativeMembershipObservation.Complete)?.raw
        ?.projectMembership(memberType, projection) is NativeMembershipProjectionResult.Valid
}

internal fun NativeRuleExecutionPlan.verifyRuleState(records: List<NativeRecord>): NativeWriteVerification {
    val record = records.singleOrNull { it.id == targetId } ?: return NativeWriteVerification.Unidentified
    if (record.safeActionBindingValues(allowUnsafeIdentity = true) == null) return NativeWriteVerification.Unidentified
    if (!postcondition.observationAvailable(record)) return NativeWriteVerification.Unidentified
    val holds = when (val condition = postcondition) {
        is NativeRulePostcondition.BooleanState -> record.ruleObservation(condition.field) == JsonPrimitive(condition.expected)
        is NativeRulePostcondition.Membership -> condition.holds(record)
    }
    return if (holds) NativeWriteVerification.Confirmed else NativeWriteVerification.NotApplied("The app did not confirm the requested change.")
}

private fun NativeRulePostcondition.Membership.holds(record: NativeRecord): Boolean {
    val original = (baseline.projectMembership(memberType, projection) as? NativeMembershipProjectionResult.Valid)?.entries ?: return false
    val raw = (record.membership(field) as? NativeMembershipObservation.Complete)?.raw ?: return false
    val actual = (raw.projectMembership(memberType, projection) as? NativeMembershipProjectionResult.Valid)?.entries ?: return false
    // A set ignores order, but keeps the full representation of every untouched member.
    return (member in actual) == assign && actual.filterKeys { it != member } == original.filterKeys { it != member }
}

internal fun NativeRuleExecutionPlan.sameOperation(other: NativeRuleExecutionPlan): Boolean = when {
    this is NativePatternRulePlan && other is NativePatternRulePlan ->
        copy(identity = identity.copy(generation = 0), baseline = other.baseline) == other.copy(identity = other.identity.copy(generation = 0))
    this is NativeMembershipRulePlan && other is NativeMembershipRulePlan ->
        postcondition.baseline.projectMembership(postcondition.memberType, postcondition.projection) ==
            other.postcondition.baseline.projectMembership(other.postcondition.memberType, other.postcondition.projection) &&
            copy(identity = identity.copy(generation = 0), postcondition = postcondition.copy(baseline = other.postcondition.baseline)) ==
                other.copy(identity = other.identity.copy(generation = 0))
    else -> false
}

internal val NativeActionRequest.Submit.executionRulePlan: NativeRuleExecutionPlan? get() = membershipPlan ?: rulePlan

private const val MAX_MEMBERS = 256
private const val MAX_MEMBERSHIP_CHARACTERS = 16 * 1024
