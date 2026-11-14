package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.rules.NativeMemberScalarType
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlinx.serialization.json.*

internal fun DynamicWriteContext.rebuildRulePlan(plan: NativeRuleExecutionPlan, values: Map<String, String>): NativeRuleExecutionPlan? {
    val schema = descriptor.toNativeAppSchema()
    return when (plan) {
        is NativePatternRulePlan -> ruleAuthority()?.let { planNativePatternRule(schema, action.id, plan.targetId, it, values) }
        is NativeMembershipRulePlan -> membershipAuthority()?.let {
            planNativeMembershipRule(schema, action.id, plan.targetId, plan.postcondition.member, it)
        }
    }
}

/** Validate stored bindings and read selection without treating old evidence as a fresh load. */
internal fun DynamicRuleRecoveryEvidence.hasConsistentPlan(schema: NativeAppSchema): Boolean = when (val stored = plan) {
    is NativePatternRulePlan -> consistentBooleanPlan(schema, stored)
    is NativeMembershipRulePlan -> {
        val type = stored.proof.bindings.lastOrNull()?.type
        val wireId = when (type) {
            NativeMemberScalarType.Integer -> stored.targetId.toLongOrNull()?.let(::JsonPrimitive)
            NativeMemberScalarType.String -> JsonPrimitive(stored.targetId)
            null -> null
        }
        val inverseId = if (stored.actionId == stored.proof.assignActionId) stored.proof.removeActionId else stored.proof.assignActionId
        if (wireId == null || inverseWrite?.id != inverseId) false else {
            val record = NativeRecord(stored.targetId, mapOf("id" to stored.targetId), actionSafeIdentity = !stored.identity.observed,
                wireValues = mapOf("id" to wireId), membershipObservations = mapOf(stored.postcondition.field to
                    NativeMembershipObservation.Complete(stored.postcondition.baseline)))
            val rebuilt = planNativeMembershipRule(schema, write.id, stored.targetId, stored.postcondition.member,
                NativeItemReadAuthority(NativeScreenState.Ready(listOf(record), stored.identity.generation),
                    sourceRead.id, stored.readBindings, canEdit = true))
            rebuilt == stored
        }
    }
}

private fun DynamicRuleRecoveryEvidence.consistentBooleanPlan(schema: NativeAppSchema, plan: NativePatternRulePlan): Boolean {
    val path = write.binding.path.split('/')
    val targetName = path.lastOrNull { it.startsWith('{') }?.removeSurrounding("{", "}") ?: return false
    val type = write.binding.pathParameters.singleOrNull { it.name == targetName }?.schema as? JsonObject ?: return false
    val wireId = when ((type["type"] as? JsonPrimitive)?.content) {
        "integer" -> JsonPrimitive(plan.targetId.toLongOrNull() ?: return false)
        "string" -> JsonPrimitive(plan.targetId)
        else -> return false
    }
    val navigation = sourceRead.binding.path.split('/').mapIndexedNotNull { index, segment ->
        if (!segment.startsWith('{')) null else segment.removeSurrounding("{", "}") to
            (plan.writeBindings[path[index].removeSurrounding("{", "}")] ?: return false)
    }.toMap() + plan.readBindings.filterKeys { name -> (sourceRead.binding.queryParameters + read.binding.queryParameters).any { it.name == name } }
    val baseline = NativeRecord(plan.targetId, mapOf("id" to plan.targetId, plan.stateField to plan.baseline.toString()),
        actionSafeIdentity = !plan.identity.observed, wireValues = mapOf("id" to wireId, plan.stateField to JsonPrimitive(plan.baseline)))
    val rebuilt = planNativePatternRule(schema, write.id, plan.targetId,
        NativeRuleAuthority(NativeScreenState.Ready(listOf(baseline), plan.identity.generation), sourceRead.id, navigation), plan.writeBindings)
    return rebuilt == plan
}
