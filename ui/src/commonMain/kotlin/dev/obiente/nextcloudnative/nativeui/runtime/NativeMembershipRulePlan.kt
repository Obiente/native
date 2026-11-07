package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.rules.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

/** One completed exact item GET and its actual bindings, owned by the executor's caller. */
data class NativeItemReadAuthority(
    val state: NativeScreenState.Ready,
    val readActionId: String,
    val readBindings: Map<String, String>,
    val currentGeneration: Long = state.generation,
    val canEdit: Boolean,
)

@Serializable
@SerialName("membership")
data class NativeMembershipRulePlan(
    override val actionId: String,
    override val targetId: String,
    override val target: NativeRuleTarget,
    override val identity: NativeRuleIdentityProof,
    override val verificationReadActionId: String,
    override val writeBindings: Map<String, String>,
    override val readBindings: Map<String, String>,
    val proof: NativeMembershipRuleProof,
    override val postcondition: NativeRulePostcondition.Membership,
    override val ruleId: String = "label-membership",
    override val ruleVersion: Int = 1,
) : NativeRuleExecutionPlan {
    override fun toString(): String = "NativeMembershipRulePlan($ruleId v$ruleVersion, <private evidence>)"
}

fun planNativeMembershipRule(
    schema: NativeAppSchema,
    actionId: String,
    targetId: String,
    member: JsonPrimitive,
    authority: NativeItemReadAuthority,
): NativeMembershipRulePlan? {
    val actions = schema.actions + schema.dedicatedActions
    val write = actions.singleOrNull { it.id == actionId } ?: return null
    val proof = SetMembershipRule().proof(schema, PatternRouteFamily(write, actions.filter { it.binding.method == HttpMethod.GET })) ?: return null
    if (authority.readActionId != proof.readActionId || !authority.canEdit || authority.state.generation <= 0L ||
        authority.state.generation != authority.currentGeneration || memberScalar(member, proof.memberType) == null) return null
    val record = authority.state.records.singleOrNull() ?: return null
    if (record.id != targetId || !membershipIdentityMatches(proof, record, authority.readBindings)) return null
    val observation = record.membership(proof.membershipField) as? NativeMembershipObservation.Complete ?: return null
    val projection = when {
        observation.raw.isEmpty() -> NativeMembershipProjection.Empty
        observation.raw.firstOrNull() is JsonPrimitive -> NativeMembershipProjection.Scalar
        else -> NativeMembershipProjection.ObjectId
    }
    if (observation.raw.projectMembership(proof.memberType, projection) !is NativeMembershipProjectionResult.Valid) return null
    val values = proof.bindings.associate { it.writeName to authority.readBindings.getValue(it.readName) } + (proof.memberField to member.content)
    val family = proof.targetRoute.split('/').joinToString("/") { if (it.startsWith('{')) "{}" else it }
    return NativeMembershipRulePlan(write.id, targetId,
        NativeRuleTarget(family, proof.bindings.map { authority.readBindings.getValue(it.readName) }),
        NativeRuleIdentityProof(write.id, proof.readActionId, !record.actionSafeIdentity, authority.state.generation, proof.readActionId),
        proof.readActionId, values, authority.readBindings.toMap(), proof,
        NativeRulePostcondition.Membership(proof.membershipField, proof.memberType, projection, member,
            write.id == proof.assignActionId, observation.raw))
}

/** The terminal record slot is distinct from every parent slot and the member body slot. */
internal fun membershipIdentityMatches(proof: NativeMembershipRuleProof, record: NativeRecord, readBindings: Map<String, String>): Boolean {
    if (readBindings.keys != proof.bindings.map { it.readName }.toSet() ||
        record.safeActionBindingValues(allowUnsafeIdentity = true) == null ||
        record.bindingContext.any { (key, value) -> readBindings[key] != value }) return false
    val target = proof.bindings.lastOrNull() ?: return false
    if (readBindings[target.readName] != record.id) return false
    val raw = record.observedRuleValues + record.wireValues
    val id = raw["id"] ?: return false
    if (memberScalar(id, target.type)?.content != record.id) return false
    val targetNames = setOf("id", "uid", "uuid", "databaseid", target.writeName.membershipKey(), target.readName.membershipKey())
    if (raw.any { (name, value) -> name.membershipKey() in targetNames && memberScalar(value, target.type)?.content != record.id }) return false
    for (binding in proof.bindings) {
        val value = readBindings[binding.readName] ?: return false
        if (value.isBlank() || value.any { it.isISOControl() } || value.length > 256 || value in setOf(".", "..") ||
            '/' in value || '\\' in value || '?' in value || '#' in value || '%' in value) return false
        if (binding.type == NativeMemberScalarType.Integer && value.toLongOrNull()?.toString() != value) return false
        if (binding == target) continue
        val names = setOf(binding.writeName.membershipKey(), binding.readName.membershipKey())
        if (raw.any { (name, field) -> name.membershipKey() in names && memberScalar(field, binding.type)?.content != value }) return false
    }
    return true
}

private fun String.membershipKey() = lowercase().filter(Char::isLetterOrDigit)
