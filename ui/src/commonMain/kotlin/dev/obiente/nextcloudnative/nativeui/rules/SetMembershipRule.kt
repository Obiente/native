package dev.obiente.nextcloudnative.nativeui.rules

import dev.obiente.nextcloudnative.nativeui.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class NativeMemberScalarType { String, Integer }

/** Positions, not matching placeholder words, relate parents in these exact route templates. */
@Serializable
data class NativeMembershipBinding(val position: Int, val writeName: String, val readName: String, val type: NativeMemberScalarType)

/** The only admitted member location in version 1 of this rule. Path membership is deferred. */
@Serializable
data class NativeMembershipBodySlot(val field: String, val type: NativeMemberScalarType)

@Serializable
data class NativeMembershipRuleProof(
    val assignActionId: String,
    val removeActionId: String,
    val readActionId: String,
    val targetRoute: String,
    val bindings: List<NativeMembershipBinding>,
    val memberSlot: NativeMembershipBodySlot,
    val membershipField: String = "labels",
    val ruleVersion: Int = 1,
) {
    val memberField: String get() = memberSlot.field
    val memberType: NativeMemberScalarType get() = memberSlot.type
}

/** E4 label commands only. Nested relation URLs and access-control memberships are not this rule. */
class SetMembershipRule : PatternRule {
    override val ruleId = "label-membership"
    override val ruleVersion = 1
    override val riskClass = DynamicOperationRiskClass.idempotentStateSet

    override fun matches(schema: NativeAppSchema, family: PatternRouteFamily): Boolean = proof(schema, family) != null

    fun proof(schema: NativeAppSchema, family: PatternRouteFamily): NativeMembershipRuleProof? {
        val write = family.write
        val shape = write.commandShape() ?: return null
        val actions = schema.actions + schema.dedicatedActions
        val inverse = actions.filter { other ->
            other.id != write.id && other.commandShape()?.let {
                it.assign != shape.assign && it.target == shape.target && it.member == shape.member && it.type == shape.type &&
                    other.binding.pathParameterTypes == write.binding.pathParameterTypes
            } == true
        }.singleOrNull() ?: return null
        val reads = family.reads.filter { read ->
            read.binding.method == HttpMethod.GET && read.risk == ActionRisk.readOnly && read.hasMembershipContract() &&
                read.confidence in setOf(Confidence.high, Confidence.verified) && read.binding.bodyContentType == null &&
                read.binding.bodySchema == null && read.binding.bodyFieldNames.isEmpty() && read.binding.queryParameterNames.isEmpty() &&
                exactMembershipRoute(shape.target, read.binding.path) && read.validMembershipPath()
        }
        val read = reads.singleOrNull() ?: return null
        val writeSegments = shape.target.split('/').filter(String::isNotEmpty)
        val readSegments = read.binding.path.split('/').filter(String::isNotEmpty)
        val bindings = writeSegments.mapIndexedNotNull { index, segment ->
            if (!segment.memberPlaceholder()) null else {
                val writeName = segment.removeSurrounding("{", "}")
                val readName = readSegments[index].removeSurrounding("{", "}")
                val type = memberScalarType(write.binding.pathParameterTypes[writeName]) ?: return null
                if (type != memberScalarType(read.binding.pathParameterTypes[readName])) return null
                NativeMembershipBinding(index, writeName, readName, type)
            }
        }
        return NativeMembershipRuleProof(
            if (shape.assign) write.id else inverse.id, if (shape.assign) inverse.id else write.id,
            read.id, shape.target, bindings, NativeMembershipBodySlot(shape.member, shape.type),
        )
    }

    // A membership postcondition requires the baseline and the selected member, not scalar equality.
    override fun encode(family: PatternRouteFamily, value: JsonElement): JsonElement? =
        family.write.commandShape()?.type?.let { type -> value.takeIf { memberScalar(it, type) != null } }
    override fun decode(family: PatternRouteFamily, wire: JsonElement): JsonElement? = encode(family, wire)
    override fun postconditionHolds(family: PatternRouteFamily, submitted: JsonElement, observed: JsonElement?): Boolean = false
}

private data class MembershipCommand(val target: String, val assign: Boolean, val member: String, val type: NativeMemberScalarType)

private fun ActionSpec.commandShape(): MembershipCommand? {
    val command = binding.path.substringAfterLast('/').lowercase()
    if (command !in setOf("assignlabel", "removelabel")) return null
    val target = binding.path.substringBeforeLast('/')
    if (!target.substringAfterLast('/').memberPlaceholder() || !validMembershipPath()) return null
    // The general classifier conservatively marks "remove" as R7. Only this exact inverse
    // label command may reinterpret that naming result; destructive effects still fail closed.
    val labelRemoval = command == "removelabel" && effect == ActionEffect.execute && risk == ActionRisk.destructive
    if (!hasMembershipContract() || confidence !in setOf(Confidence.high, Confidence.verified) ||
        risk != ActionRisk.mutating && !labelRemoval ||
        binding.method != HttpMethod.POST || binding.queryParameterNames.isNotEmpty() ||
        binding.bodyContentType?.substringBefore(';') != "application/json" || binding.allowsObservedBodyFields || sharePermissionFieldId != null) return null
    if (operationRiskClass in setOf(DynamicOperationRiskClass.externalEffect, DynamicOperationRiskClass.sharing,
            DynamicOperationRiskClass.administration, DynamicOperationRiskClass.credential) ||
        operationRiskClass == DynamicOperationRiskClass.destructive && !labelRemoval) return null
    // Inspect operation and route words as well as risk metadata. Names never override R8-R10.
    val routeWords = if ("/apps/" in binding.path) binding.path.substringAfter("/apps/").substringAfter('/') else binding.path
    val words = (routeWords + " " + binding.operationId + " " + label).replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase().split(Regex("[^a-z0-9]+"))
    if (words.any { it in ACCESS_WORDS }) return null
    val body = binding.bodySchema as? JsonObject ?: return null
    val properties = body["properties"] as? JsonObject ?: return null
    val member = properties.keys.singleOrNull()?.takeIf { it.lowercase().replace("_", "") == "labelid" } ?: return null
    val property = properties[member] as? JsonObject ?: return null
    val type = memberScalarType((property["type"] as? JsonPrimitive)?.content) ?: return null
    if ((property["readOnly"] as? JsonPrimitive)?.booleanOrNull == true ||
        (property["nullable"] as? JsonPrimitive)?.booleanOrNull == true ||
        binding.bodyFieldNames != listOf(member) || binding.requiredBodyFieldNames != listOf(member) ||
        member in binding.pathParameterNames) return null
    return MembershipCommand(target, command == "assignlabel", member, type)
}

internal fun exactMembershipRoute(left: String, right: String): Boolean {
    val a = left.split('/')
    val b = right.split('/')
    return a.size == b.size && a.indices.all { a[it] == b[it] || a[it].memberPlaceholder() && b[it].memberPlaceholder() }
}

private fun ActionSpec.validMembershipPath(): Boolean {
    val names = binding.path.split('/').filter { it.memberPlaceholder() }.map { it.removeSurrounding("{", "}") }
    return names.isNotEmpty() && names.distinct().size == names.size &&
        names.toSet() == binding.pathParameterNames.toSet() && names.toSet() == binding.requiredPathParameterNames.toSet() &&
        names.all { memberScalarType(binding.pathParameterTypes[it]) != null }
}

private fun ActionSpec.hasMembershipContract(): Boolean = evidence.any {
    it.source in setOf(EvidenceSource.openApi, EvidenceSource.verifiedAppPackage, EvidenceSource.appStoreLinkedSourceTag)
}
private fun String.memberPlaceholder() = startsWith('{') && endsWith('}')
internal fun memberScalarType(type: String?): NativeMemberScalarType? = when (type) {
    "string" -> NativeMemberScalarType.String
    "integer" -> NativeMemberScalarType.Integer
    else -> null
}
internal fun memberScalar(value: JsonElement?, type: NativeMemberScalarType): JsonPrimitive? {
    val scalar = value as? JsonPrimitive ?: return null
    if (scalar is JsonNull || scalar.content.length !in 1..256 || scalar.content.any { it.isISOControl() }) return null
    return scalar.takeIf {
        when (type) {
            NativeMemberScalarType.String -> it.isString
            NativeMemberScalarType.Integer -> !it.isString && it.longOrNull?.toString() == it.content
        }
    }
}
private val ACCESS_WORDS = setOf("share", "shares", "sharing", "acl", "acls", "permission", "permissions", "user", "users",
    "group", "groups", "member", "members", "participant", "participants", "role", "roles", "admin", "password", "credential", "credentials")
