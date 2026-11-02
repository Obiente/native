package dev.obiente.nextcloudnative.nativeui.rules

import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.DynamicOperationRiskClass
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import kotlinx.serialization.json.JsonElement

/*
 * Pattern rules (evidence tier E4). A rule is a reviewed, versioned reading of a contract shape
 * that recurs across apps, such as "two sibling body-free commands under one item route set a
 * boolean state". A rule may read names and declared types. It may never invent an endpoint,
 * method, path parameter, payload field, permission, target identity, or idempotency guarantee:
 * every operation it reads must already exist in the app's declared contract.
 *
 * A match alone offers nothing. The first write under a rule on a device (per account, app ID,
 * exact app version, route, rule ID, and rule version) is a probation write whose read-back must
 * confirm the rule; a contradiction quarantines that key. Rules are registered with fixtures from
 * at least two apps, and a rule never special-cases an app ID or version.
 *
 * Phase one ships the interface and an empty registry; the first rules arrive with their fixtures.
 */

/** A write and the declared reads of its route family, as the rule sees them. */
data class PatternRouteFamily(
    val write: ActionSpec,
    /** Declared reads in the write's route family: item reads first, then collections. */
    val reads: List<ActionSpec>,
)

/** One reviewed rule. Implementations are stateless and deterministic. */
interface PatternRule {
    /** Stable lowercase identifier, for example `item-state-pair`. Never an app ID. */
    val ruleId: String

    /** Incremented whenever the rule's reading changes; a new version restarts probation. */
    val ruleVersion: Int

    /** The class the rule's writes are admitted as. */
    val riskClass: DynamicOperationRiskClass

    /** True when [family] in [schema] has exactly the shape this rule reads. */
    fun matches(schema: NativeAppSchema, family: PatternRouteFamily): Boolean

    /** The exact wire value for a decoded [value], or null when the rule cannot encode it. */
    fun encode(family: PatternRouteFamily, value: JsonElement): JsonElement?

    /** The decoded value of an observed wire value, or null when it does not fit the rule's grammar. */
    fun decode(family: PatternRouteFamily, wire: JsonElement): JsonElement?

    /** True when the read-back [observed] value shows that [submitted] was applied. */
    fun postconditionHolds(family: PatternRouteFamily, submitted: JsonElement, observed: JsonElement?): Boolean
}

/**
 * The registry of reviewed rules. Rule IDs are unique; several matching rules for one family are
 * ambiguous and none applies.
 */
class PatternRuleRegistry(rules: List<PatternRule> = emptyList()) {
    val rules: List<PatternRule> = rules.toList()

    init {
        require(this.rules.map(PatternRule::ruleId).distinct().size == this.rules.size) { "Pattern rule IDs must be unique." }
        require(this.rules.all { it.ruleId.isRuleId() && it.ruleVersion >= 1 }) { "Pattern rule identities must be safe." }
    }

    /** The single rule that reads [family], or null when none or several match. */
    fun ruleFor(schema: NativeAppSchema, family: PatternRouteFamily): PatternRule? =
        rules.filter { rule -> rule.matches(schema, family) }.singleOrNull()

    companion object {
        /** The rules shipped with this build. */
        val shipped: PatternRuleRegistry = PatternRuleRegistry()
    }
}

private fun String.isRuleId(): Boolean = length in 1..64 && all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
